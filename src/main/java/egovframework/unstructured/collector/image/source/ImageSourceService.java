package egovframework.unstructured.collector.image.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.image.batch.ImageTrace;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageTarget;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 보라미에서 수집할 수용자 사진을 고른다 — <b>3단계 조회</b>. 원천 DB 는 음성과 같은 라우터(로컬 H2 / 개발계 / 운영)다.
 *
 * <ol>
 *   <li><b>최신 이미지</b> — {@code TB_IRIM_BSIF_DS} 에서 {@code IMAGE_SE_CD='1'}(사진) 중 수용자별 {@code IMAGE_SN} 최대 1건의
 *       {@code IMAGE_CMMN_FILE_ID}. 수용자 순으로 페이지를 넘긴다</li>
 *   <li><b>문서ID</b> — {@code TB_SMSM_CMFI_BS} 에서 {@code CMMN_FILE_ID = IMAGE_CMMN_FILE_ID} 의 {@code DOC_ID}</li>
 *   <li><b>FILEKEY</b> — {@code ASYSCONTENTELEMENT} 에서 {@code ELEMENTID = DOC_ID} 의 {@code FILEKEY}</li>
 * </ol>
 *
 * <p>2·3단계는 페이지마다 한 번에(IN 목록 + LEFT JOIN) 묻는다 — 건마다 묻지 않는다. 없는 건은 비운 채 돌려주고,
 * 배치가 그 건을 'FILEKEY 추출' 실패로 남긴다(조용히 빠뜨리지 않는다).</p>
 *
 * <p>SQL 은 Oracle 12c+ · PostgreSQL · H2 에서 같은 문장이 돌게 쓴다({@code ROW_NUMBER() OVER} ·
 * {@code OFFSET … ROWS FETCH NEXT … ROWS ONLY}). 조회만 하고 트랜잭션을 열지 않는다 — 문장마다 커넥션을 빌렸다 곧 돌려준다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ImageSourceService {

    private final JdbcTemplate jdbc;
    private final BoramiTableNames tables;
    private final VoiceProperties voiceProps;
    private final ImageProperties props;
    private final ImageTrace trace;

    /**
     * 조회 조건.
     *
     * @param corrNos      이 수용자들만(비우면 전체)
     * @param corrNoPrefix 이 접두의 수용자만 — 시뮬레이션 데이터({@code SIMIMG…}). 비우면 실제 수집이라 SIM 행을 뺀다
     * @param limit        최대 수용자 수(비우면 {@code image.max-per-run})
     */
    public record Selection(List<String> corrNos, String corrNoPrefix, Integer limit) {

        public static Selection all(Integer limit) {
            return new Selection(null, null, limit);
        }
    }

    /** 1단계 — 수용자별 최신 사진. 수용자 순. */
    public List<ImageTarget> findLatest(Selection sel) {
        int max = sel.limit() != null && sel.limit() > 0 ? sel.limit() : props.maxPerRun();
        int page = Math.max(1, Math.min(props.pageSize(), max));
        List<ImageTarget> out = new ArrayList<>();
        if (sel.corrNos() != null && !sel.corrNos().isEmpty()) {
            for (int i = 0; i < sel.corrNos().size() && out.size() < max; i += 500) {
                List<String> chunk = sel.corrNos().subList(i, Math.min(sel.corrNos().size(), i + 500));
                out.addAll(queryLatest(chunk, sel.corrNoPrefix(), 0, max - out.size()));
            }
            return out;
        }
        for (int offset = 0; out.size() < max; offset += page) {
            List<ImageTarget> rows = queryLatest(null, sel.corrNoPrefix(), offset, Math.min(page, max - out.size()));
            out.addAll(rows);
            if (rows.size() < page) {
                break;
            }
        }
        return out;
    }

    private List<ImageTarget> queryLatest(List<String> corrNos, String prefix, int offset, int limit) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT CORR_NO, IMAGE_SN, IMAGE_CMMN_FILE_ID FROM (")
                .append(" SELECT B.CORR_NO, B.IMAGE_SN, B.IMAGE_CMMN_FILE_ID,")
                .append("        ROW_NUMBER() OVER (PARTITION BY B.CORR_NO ORDER BY B.IMAGE_SN DESC) AS RN")
                .append("   FROM ").append(tables.irimBsifDs()).append(" B")
                .append("  WHERE B.IMAGE_SE_CD = :seCd");
        MapSqlParameterSource p = new MapSqlParameterSource("seCd", props.imageSeCd());
        if (prefix != null && !prefix.isBlank()) {
            sql.append(" AND B.CORR_NO LIKE :prefix");
            p.addValue("prefix", prefix + "%");
        } else {
            // 실제 수집 — 6번 탭이 재실행(멱등성) 검증을 위해 남겨 둔 SIM 행을 집어가지 않는다
            sql.append(" AND B.CORR_NO NOT LIKE :simPrefix");
            p.addValue("simPrefix", ImageSimulationService.PREFIX + "%");
        }
        if (corrNos != null) {
            sql.append(" AND B.CORR_NO IN (:corrNos)");
            p.addValue("corrNos", corrNos);
        }
        sql.append(") T WHERE RN = 1 ORDER BY CORR_NO OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
        p.addValue("offset", offset).addValue("limit", Math.max(1, limit));
        List<ImageTarget> rows = new NamedParameterJdbcTemplate(jdbc).query(sql.toString(), p, (rs, i) ->
                ImageTarget.latest(rs.getString("CORR_NO"), rs.getInt("IMAGE_SN"), rs.getString("IMAGE_CMMN_FILE_ID")));
        if (offset == 0) {
            // ① 실행 기록 — 첫 페이지만(값을 채운 SQL · 결과 표본)
            List<Map<String, Object>> out = new ArrayList<>();
            for (ImageTarget t : rows) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("CORR_NO", t.corrNo());
                m.put("IMAGE_SN", t.imageSn());
                m.put("IMAGE_CMMN_FILE_ID", t.imageCmmnFileId());
                out.add(m);
            }
            trace.add(ImageTrace.Step.QUERY, "수용자별 최신 사진 — IMAGE_SE_CD='1' 중 IMAGE_SN 최대(첫 페이지)", "SQL",
                    inline(sql.toString(), p) + ";",
                    ImageTrace.table(List.of("CORR_NO", "IMAGE_SN", "IMAGE_CMMN_FILE_ID"), out, rows.size(), 5));
        }
        return rows;
    }

    /** 표시용 — 이름 붙은 파라미터를 값으로 채운다(실행은 바인딩 그대로). IN 목록은 앞 5개만. */
    private static String inline(String sql, MapSqlParameterSource p) {
        String out = sql;
        for (String name : p.getParameterNames()) {
            Object v = p.getValue(name);
            String lit;
            if (v instanceof java.util.Collection<?> c) {
                List<String> vals = new ArrayList<>();
                int i = 0;
                for (Object o : c) {
                    if (i++ >= 5) {
                        vals.add("/* 외 " + (c.size() - 5) + "개 */");
                        break;
                    }
                    vals.add(ImageTrace.lit(o));
                }
                lit = String.join(", ", vals);
            } else {
                lit = ImageTrace.lit(v);
            }
            out = out.replaceAll(":" + name + "\\b", java.util.regex.Matcher.quoteReplacement(lit));
        }
        return out;
    }

    /**
     * 2·3단계 — 문서ID · FILEKEY(· 파일명 · 암호화 여부)를 채운다. 없는 건은 비운 채 돌려준다.
     */
    public List<ImageTarget> attachFileKeys(List<ImageTarget> latest) {
        List<String> ids = latest.stream().map(ImageTarget::imageCmmnFileId).filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        Map<String, FileRow> byId = new HashMap<>();
        String sql = "SELECT C.CMMN_FILE_ID, C.DOC_ID, C.FILE_NM, C.CMMN_FILE_ENC_YN, X.FILEKEY"
                + "  FROM " + tables.smsmCmfiBs() + " C"
                + "  LEFT JOIN " + tables.asysContentElement() + " X ON X.ELEMENTID = C.DOC_ID"
                + " WHERE C.CMMN_FILE_ID IN (:ids)";
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        for (int i = 0; i < ids.size(); i += 500) {
            named.query(sql, new MapSqlParameterSource("ids", ids.subList(i, Math.min(ids.size(), i + 500))), rs -> {
                byId.put(rs.getString("CMMN_FILE_ID"), new FileRow(rs.getString("DOC_ID"), rs.getString("FILEKEY"),
                        rs.getString("FILE_NM"), rs.getString("CMMN_FILE_ENC_YN")));
            });
        }
        if (!ids.isEmpty()) {
            // ②③ 실행 기록 — 첫 묶음의 조인(값을 채운 SQL · 결과 표본)
            List<String> first = ids.subList(0, Math.min(ids.size(), 500));
            String shown = inline(sql, new MapSqlParameterSource("ids", first)) + ";";
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String id : first) {
                FileRow f = byId.get(id);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("CMMN_FILE_ID", id);
                m.put("DOC_ID", f == null ? null : f.docId());
                m.put("FILE_NM", f == null ? null : f.fileName());
                m.put("CMMN_FILE_ENC_YN", f == null ? null : f.encYn());
                m.put("FILEKEY", f == null ? null : f.fileKey());
                rows.add(m);
            }
            trace.add(ImageTrace.Step.DOC_ID, "공통파일기본 — CMMN_FILE_ID → DOC_ID · 파일명 · 암호화 여부 (②③ 을 한 번의 LEFT JOIN 으로)",
                    "SQL", shown, ImageTrace.table(List.of("CMMN_FILE_ID", "DOC_ID", "FILE_NM", "CMMN_FILE_ENC_YN"), rows, ids.size(), 5));
            trace.add(ImageTrace.Step.FILEKEY, "XVARM — ELEMENTID = DOC_ID → FILEKEY (같은 조인의 X 쪽)", "SQL", shown,
                    ImageTrace.table(List.of("DOC_ID", "FILEKEY"), rows, ids.size(), 5));
        }
        String encYes = voiceProps.source().flag().encrypted();
        List<ImageTarget> out = new ArrayList<>(latest.size());
        for (ImageTarget t : latest) {
            FileRow f = byId.get(t.imageCmmnFileId());
            // 암호화 여부가 비어 있으면 암호화된 것으로 본다 — 보라미 첨부는 기본이 암호화다
            out.add(f == null ? t : t.withFile(f.docId(), f.fileKey(), f.fileName(),
                    f.encYn() == null || f.encYn().isBlank() || encYes.equalsIgnoreCase(f.encYn().trim())));
        }
        return out;
    }

    private record FileRow(String docId, String fileKey, String fileName, String encYn) {}

    /** 조회에 쓰는 테이블(화면·로그용). */
    public Map<String, String> tables() {
        return Map.of("image", tables.irimBsifDs(), "cmfi", tables.smsmCmfiBs(), "xvarm", tables.asysContentElement());
    }
}
