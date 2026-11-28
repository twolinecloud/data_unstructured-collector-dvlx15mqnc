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
 * {@code OFFSET 0 ROWS FETCH NEXT … ROWS ONLY} · {@code CORR_NO} 키셋 커서). 조회만 하고 트랜잭션을 열지 않는다 — 문장마다 커넥션을 빌렸다 곧 돌려준다.</p>
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
    /** 운영 선별 테스트 조건(환경 변수 SOURCE_TEST_*) — 비어 있으면 기존 기준 */
    private final egovframework.unstructured.collector.common.config.SourceTestFilter testFilter;

    /**
     * 조회 조건.
     *
     * @param corrNos      이 수용자들만(비우면 전체)
     * @param corrNoPrefix 이 접두의 수용자만 — 시뮬레이션 데이터({@code SIMIMG…}). 비우면 실제 수집이라 SIM 행을 뺀다
     * @param limit        이번 실행의 최대 수용자 수 — 비우면(또는 0 이하) 상한 없이 끝까지. 청크 크기와 다르다
     */
    public record Selection(List<String> corrNos, String corrNoPrefix, Integer limit) {

        public static Selection all(Integer limit) {
            return new Selection(null, null, limit);
        }
    }

    /** IN 목록 한 묶음 — Oracle IN 1,000개 제한보다 작게. */
    static final int IN_BATCH = 500;

    /**
     * 수용상태로 거르지 않는 합성 데이터 접두 — 6번 탭 SIM({@code SIMIMG}) · 대시보드 더미({@code DMYIMG}). 둘 다 수용자기본
     * ({@code TB_IRIM_PRBS_BS})에는 <b>일부러 쓰지 않는다</b>(정형 수집기가 그 표를 읽는다 · {@code DummyDataService}). 운영에는 없다.
     */
    static final List<String> SYNTHETIC_PREFIXES = java.util.Arrays.stream(egovframework.unstructured.collector.mock.DummyTarget.values())
            .map(egovframework.unstructured.collector.mock.DummyTarget::imagePrefix).distinct().toList();

    /**
     * 1단계 — 수용자별 최신 사진을 청크로 읽는 커서. {@link Cursor#next()} 가 0건을 돌려줄 때까지 부른다.
     *
     * @param chunkSize 한 번에 읽을 수용자 수({@code image.chunk-size})
     */
    public Cursor open(Selection sel, int chunkSize) {
        Selection eff = applyTestFilter(sel);
        Cursor c = new Cursor(eff, Math.max(1, chunkSize));
        if (!testFilter.corrNos().isEmpty() && c.corrNos == null) {
            c.exhausted = true;   // 교정번호 교집합이 비었다 — '목록 없음(=전체)' 으로 넘어가지 않게 0건으로 끝낸다
        }
        return c;
    }

    /**
     * 운영 선별 테스트 조건을 조회 조건에 겹친다. 조건이 없으면 받은 그대로.
     *
     * <ul>
     *   <li>교정번호 — 요청 목록이 없으면 테스트 목록, 있으면 둘의 교집합(비면 0건)</li>
     *   <li>상한 — 요청 상한과 테스트 상한 중 작은 쪽</li>
     *   <li>기관코드 — SQL 에서 수용자기본({@code CORR_INSTT_CD})으로 건다({@link #queryLatest})</li>
     *   <li>기간 — <b>사진에는 적용하지 않는다</b>(사진 테이블 변경일시 기준 미확정 · 전산과 협의 ③). 그래서 기간만 주면
     *       현재 수용자 전원을 훑게 되므로 조회를 막는다 — 사진도 선별하려면 기관 · 교정번호 · 상한 중 하나를 함께</li>
     * </ul>
     */
    Selection applyTestFilter(Selection sel) {
        testFilter.requireValid();
        if (!testFilter.active()) {
            return sel;
        }
        if (!testFilter.narrowsWithoutPeriod()) {
            throw new IllegalStateException("운영 테스트 조건이 기간(SOURCE_TEST_FROM/TO)뿐이다 — 사진은 기간 조건이 없어 전원 조회가 된다. "
                    + "사진도 선별하려면 SOURCE_TEST_CORR_NO · SOURCE_TEST_INSTT_CD · SOURCE_TEST_MAX_ROWS 중 하나를 함께 지정");
        }
        List<String> corr = sel.corrNos();
        List<String> test = testFilter.corrNos();
        if (!test.isEmpty()) {
            corr = corr == null || corr.isEmpty() ? test : corr.stream().filter(test::contains).toList();
        }
        Integer limit = sel.limit() != null && sel.limit() > 0 ? sel.limit() : null;
        Integer max = testFilter.maxRows();
        if (max != null) {
            limit = limit == null ? max : Math.min(limit, max);
        }
        Selection out = new Selection(corr, sel.corrNoPrefix(), limit);
        log.warn("[운영 테스트 조건] 사진 최종 조회 조건 — {} · 교정번호 {} · 상한 {} (요청: 교정번호 {} · 상한 {}) · 기간 조건은 사진에 미적용",
                testFilter.describe(), corr == null ? "전체" : corr.size() + "명", limit == null ? "없음" : limit + "명",
                sel.corrNos() == null ? "전체" : sel.corrNos().size() + "명", sel.limit());
        return out;
    }

    /**
     * {@code CORR_NO} 키셋 커서 — {@code CORR_NO > 마지막 교정번호} 오름차순으로 청크 크기만큼씩.
     *
     * <p><b>커서가 {@code CORR_NO} 하나로 충분한 이유</b>: 원장 {@code TB_IRIM_BSIF_DS} 는 PK 가 {@code (CORR_NO, IMAGE_SN)} 이라
     * 한 수용자에 사진이 여러 장 있다. 그러나 조회는 {@code ROW_NUMBER() OVER (PARTITION BY CORR_NO ORDER BY IMAGE_SN DESC) = 1} 로
     * <b>수용자당 최신 1행</b>만 남긴 뒤 정렬·자르기를 한다 — 결과에서 {@code CORR_NO} 는 유일하다. 커서 조건은 안쪽(파티션 전)에 걸지만
     * 파티션 키가 같은 {@code CORR_NO} 라 수용자 단위로 통째로 빠지거나 남는다(한 수용자의 사진이 청크 경계에서 갈리지 않는다).
     * 수집 대상은 수용자당 최신 사진 1장이라 '나머지 사진'은 원래 받지 않는다.</p>
     *
     * <p>{@code corrNos} 를 준 수동 실행은 목록을 {@value #IN_BATCH} 개씩 차례로 묻는다(목록이 유한하다 — 목록을 다 돌면 0건).</p>
     */
    public final class Cursor {

        private final Selection sel;
        private final int chunkSize;
        private final List<String> corrNos;
        private final Integer cap;
        private String lastCorrNo;
        private int listPos;
        private int chunks;
        private int taken;
        private boolean exhausted;
        private boolean recorded;

        private Cursor(Selection sel, int chunkSize) {
            this.sel = sel;
            this.chunkSize = chunkSize;
            this.corrNos = sel.corrNos() == null || sel.corrNos().isEmpty() ? null
                    : sel.corrNos().stream().filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                    .distinct().toList();
            this.cap = sel.limit() != null && sel.limit() > 0 ? sel.limit() : null;
        }

        /** 다음 청크. 0건이면 끝(그 뒤로도 계속 0건). */
        public List<ImageTarget> next() {
            if (exhausted) {
                return List.of();
            }
            int want = cap == null ? chunkSize : Math.min(chunkSize, cap - taken);
            if (want <= 0) {
                exhausted = true;
                return List.of();
            }
            List<ImageTarget> rows = corrNos != null ? nextFromList(want) : nextByKey(want);
            if (rows.isEmpty()) {
                exhausted = true;
                return rows;
            }
            chunks++;
            taken += rows.size();
            return rows;
        }

        private List<ImageTarget> nextByKey(int want) {
            List<ImageTarget> rows = queryLatest(null, sel.corrNoPrefix(), lastCorrNo, want, !recorded);
            recorded = true;
            if (!rows.isEmpty()) {
                String last = rows.get(rows.size() - 1).corrNo();
                // 커서가 앞으로 가지 않으면(정렬·비교 규칙이 어긋난 DB) 같은 청크를 끝없이 돌게 된다 — 멈추고 알린다
                if (last == null || last.equals(lastCorrNo)
                        || rows.stream().map(ImageTarget::corrNo).distinct().count() != rows.size()) {
                    throw new IllegalStateException("사진 조회 커서가 진행하지 않는다 — 마지막 교정번호 " + lastCorrNo + " → " + last);
                }
                lastCorrNo = last;
            }
            return rows;
        }

        private List<ImageTarget> nextFromList(int want) {
            List<ImageTarget> out = new ArrayList<>();
            while (out.isEmpty() && listPos < corrNos.size()) {
                List<String> batch = corrNos.subList(listPos, Math.min(corrNos.size(), listPos + Math.min(IN_BATCH, want)));
                listPos += batch.size();
                out.addAll(queryLatest(batch, sel.corrNoPrefix(), null, batch.size(), !recorded));
                recorded = true;
            }
            return out;
        }

        /** 지금까지 읽은 청크 수(0건 청크 제외). */
        public int chunks() {
            return chunks;
        }

        /** 지금까지 읽은 수용자 수. */
        public int taken() {
            return taken;
        }

        /** 마지막으로 읽은 교정번호(키셋 커서) — {@code corrNos} 실행이면 비어 있다. */
        public String lastCorrNo() {
            return lastCorrNo;
        }
    }

    /** 수용상태 필터 값 — 공백 제거 · 빈 값 제외. 비면 거르지 않는다. */
    List<String> activeStatusCodes() {
        List<String> codes = props.activeStatusCodes() == null ? List.of() : props.activeStatusCodes();
        return codes.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static boolean isSynthetic(String prefix) {
        return prefix != null && SYNTHETIC_PREFIXES.stream().anyMatch(s -> prefix.trim().startsWith(s));
    }

    private List<ImageTarget> queryLatest(List<String> corrNos, String prefix, String afterCorrNo, int limit, boolean record) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT CORR_NO, IMAGE_SN, IMAGE_CMMN_FILE_ID FROM (")
                .append(" SELECT B.CORR_NO, B.IMAGE_SN, B.IMAGE_CMMN_FILE_ID,")
                .append("        ROW_NUMBER() OVER (PARTITION BY B.CORR_NO ORDER BY B.IMAGE_SN DESC) AS RN")
                .append("   FROM ").append(tables.irimBsifDs()).append(" B")
                .append("  WHERE B.IMAGE_SE_CD = :seCd");
        MapSqlParameterSource p = new MapSqlParameterSource("seCd", props.imageSeCd());
        boolean prefixed = prefix != null && !prefix.isBlank();
        if (prefixed) {
            sql.append(" AND B.CORR_NO LIKE :prefix");
            p.addValue("prefix", prefix.trim() + "%");
        } else {
            // 실제 수집 — 6번 탭이 재실행(멱등성) 검증을 위해 남겨 둔 SIM 행을 집어가지 않는다
            sql.append(" AND B.CORR_NO NOT LIKE :simPrefix");
            p.addValue("simPrefix", ImageSimulationService.PREFIX + "%");
        }
        if (afterCorrNo != null) {
            // 키셋 커서 — 파티션 키와 같은 CORR_NO 라 수용자 단위로 갈린다(위 Cursor 설명)
            sql.append(" AND B.CORR_NO > :lastCorrNo");
            p.addValue("lastCorrNo", afterCorrNo);
        }
        if (corrNos != null) {
            sql.append(" AND B.CORR_NO IN (:corrNos)");
            p.addValue("corrNos", corrNos);
        }
        // 현재 수용자만 — 수용자기본(CORR_NO 1:1)의 수용상태. JOIN 대신 EXISTS: 수용자기본에 같은 CORR_NO 가 여럿이어도 사진 행이
        //   불어나지 않는다(커서 유일성 유지). 합성 데이터(SIM·DMY)는 수용자기본에 없으므로 거르지 않는다
        List<String> codes = activeStatusCodes();
        if (!codes.isEmpty() && !(prefixed && isSynthetic(prefix))) {
            String exists = "EXISTS (SELECT 1 FROM " + tables.irimPrbsBs() + " P"
                    + " WHERE P.CORR_NO = B.CORR_NO AND P.ACMD_STTUS_CD IN (:acmdSttusCds))";
            p.addValue("acmdSttusCds", codes);
            if (prefixed) {
                sql.append(" AND ").append(exists);
            } else {
                StringBuilder exempt = new StringBuilder();
                for (int i = 0; i < SYNTHETIC_PREFIXES.size(); i++) {
                    if (SYNTHETIC_PREFIXES.get(i).equals(ImageSimulationService.PREFIX)) {
                        continue;   // SIM 은 위에서 이미 뺐다
                    }
                    exempt.append("B.CORR_NO LIKE :synthetic").append(i).append(" OR ");
                    p.addValue("synthetic" + i, SYNTHETIC_PREFIXES.get(i) + "%");
                }
                sql.append(" AND (").append(exempt).append(exists).append(")");
            }
        }
        List<String> testInstt = testFilter.insttCds();
        if (!testInstt.isEmpty()) {
            // 운영 테스트 조건 — 교정기관코드. 사진 표에는 기관 컬럼이 없어 수용자기본으로 건다(합성 데이터는 수용자기본에 없어 빠진다)
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(tables.irimPrbsBs()).append(" PI")
                    .append(" WHERE PI.CORR_NO = B.CORR_NO AND PI.CORR_INSTT_CD IN (:testInsttCds))");
            p.addValue("testInsttCds", testInstt);
        }
        sql.append(") T WHERE RN = 1 ORDER BY CORR_NO OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY");
        p.addValue("limit", Math.max(1, limit));
        List<ImageTarget> rows = new NamedParameterJdbcTemplate(jdbc).query(sql.toString(), p, (rs, i) ->
                ImageTarget.latest(rs.getString("CORR_NO"), rs.getInt("IMAGE_SN"), rs.getString("IMAGE_CMMN_FILE_ID")));
        if (record) {
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
     *
     * <p>XVARM 조인({@code voice.source.xvarm-join})이 {@code NONE} 이면 3단계를 건너뛴다 — XVARM 표를 읽지 않고 FILEKEY 는
     * 비운다(2026-11-28 XVARM 제거 1단계 · 동기화 방식은 DOC_ID 로 찾는다).</p>
     */
    public List<ImageTarget> attachFileKeys(List<ImageTarget> latest) {
        List<String> ids = latest.stream().map(ImageTarget::imageCmmnFileId).filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        Map<String, FileRow> byId = new HashMap<>();
        boolean xvarm = voiceProps.source().xvarmJoin() == VoiceProperties.XvarmJoin.TRACE;
        String sql = xvarm
                ? "SELECT C.CMMN_FILE_ID, C.DOC_ID, C.FILE_NM, C.CMMN_FILE_ENC_YN, X.FILEKEY"
                        + "  FROM " + tables.smsmCmfiBs() + " C"
                        + "  LEFT JOIN " + tables.asysContentElement() + " X ON X.ELEMENTID = C.DOC_ID"
                        + " WHERE C.CMMN_FILE_ID IN (:ids)"
                : "SELECT C.CMMN_FILE_ID, C.DOC_ID, C.FILE_NM, C.CMMN_FILE_ENC_YN, CAST(NULL AS VARCHAR(1)) AS FILEKEY"
                        + "  FROM " + tables.smsmCmfiBs() + " C"
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
            if (xvarm) {
                trace.add(ImageTrace.Step.DOC_ID, "공통파일기본 — CMMN_FILE_ID → DOC_ID · 파일명 · 암호화 여부 (②③ 을 한 번의 LEFT JOIN 으로)",
                        "SQL", shown, ImageTrace.table(List.of("CMMN_FILE_ID", "DOC_ID", "FILE_NM", "CMMN_FILE_ENC_YN"), rows, ids.size(), 5));
                trace.add(ImageTrace.Step.FILEKEY, "XVARM — ELEMENTID = DOC_ID → FILEKEY (같은 조인의 X 쪽)", "SQL", shown,
                        ImageTrace.table(List.of("DOC_ID", "FILEKEY"), rows, ids.size(), 5));
            } else {
                trace.add(ImageTrace.Step.DOC_ID, "공통파일기본 — CMMN_FILE_ID → DOC_ID · 파일명 · 암호화 여부 (XVARM 조인 없음)",
                        "SQL", shown, ImageTrace.table(List.of("CMMN_FILE_ID", "DOC_ID", "FILE_NM", "CMMN_FILE_ENC_YN"), rows, ids.size(), 5));
                trace.add(ImageTrace.Step.FILEKEY, "XVARM 조인 안 함 — voice.source.xvarm-join=NONE (DOC_ID 로 찾는다 · FILEKEY 비움)",
                        "CODE", "# XVARM(ASYSCONTENTELEMENT)을 읽지 않는다", "FILEKEY = NULL (" + ids.size() + "건)");
            }
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
        return Map.of("image", tables.irimBsifDs(), "cmfi", tables.smsmCmfiBs(), "xvarm", tables.asysContentElement(),
                "inmate", tables.irimPrbsBs());
    }
}
