package egovframework.unstructured.collector.image.source;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.image.config.ImageProperties;
import egovframework.unstructured.collector.image.model.ImageTarget;
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

    /**
     * 조회 조건.
     *
     * @param corrNos      이 수용자들만(비우면 전체)
     * @param corrNoPrefix 이 접두의 수용자만 — 시뮬레이션 데이터({@code SIMIMG…})
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
        }
        if (corrNos != null) {
            sql.append(" AND B.CORR_NO IN (:corrNos)");
            p.addValue("corrNos", corrNos);
        }
        sql.append(") T WHERE RN = 1 ORDER BY CORR_NO OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
        p.addValue("offset", offset).addValue("limit", Math.max(1, limit));
        return new NamedParameterJdbcTemplate(jdbc).query(sql.toString(), p, (rs, i) ->
                ImageTarget.latest(rs.getString("CORR_NO"), rs.getInt("IMAGE_SN"), rs.getString("IMAGE_CMMN_FILE_ID")));
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
