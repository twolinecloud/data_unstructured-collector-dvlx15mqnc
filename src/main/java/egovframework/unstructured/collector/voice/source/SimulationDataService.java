package egovframework.unstructured.collector.voice.source;

import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.util.SilentWav;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 시뮬레이션 데이터 — <b>DB 메타(보라미·XVARM) + 물리 더미 음성 파일</b>을 한 번에 만들고 지운다.
 *
 * <p><b>구성 (접견 7 · 전화 7 = 14건)</b>
 * <ul>
 *   <li><b>일배치용 10건</b>(트랙별 5) — 어제 <b>00:00:00~23:59:59</b> 에 고르게. 첫 건과 마지막 건이 일배치 창
 *       {@code [어제 00:00, 오늘 00:00)} 의 양 끝에 놓여 경계까지 같이 본다.</li>
 *   <li><b>주기배치용 4건</b>(트랙별 2) — 접견 <b>지금-6분 / -18분</b>, 전화 <b>지금-4분 / -16분</b>.
 *       주기배치는 10분마다 돌지만 창은 20분({@code voice.batch.periodic-lag-min})이라,
 *       뒤엣것은 <b>10분 창만 봤다면 놓쳤을 지연 건</b>이다 — 20분 창이 실제로 주워 오는지 보는 표본.</li>
 * </ul>
 * 접견은 4단(re·im·sm·xvarm) 1:1:1:1, 전화는 im 통화내역 + im 특이수용자 1:1 로
 * 조인이 서는 유효 메타를 넣고, DB 의 파일명과 1:1 인 경량 더미 파일(0~1KB)을 XVARM 원본 스토리지에 쓴다.</p>
 *
 * <p><b>같은 코드가 H2(로컬)와 개발계 PostgreSQL 을 상대한다.</b> 테이블명은 {@link BoramiTableNames} 가 스키마를 붙여 주고,
 * 값은 전부 바인딩({@code ?})이라 DB 문법 차이가 없다. 스펙의 NOT NULL 컬럼(테이블 정의서 기준)을 모두 채운다 —
 * 개발계 실제 테이블에는 H2 Mock 보다 컬럼이 많기 때문이다. 개발계에 없는 두 테이블(공통파일기본·XVARM)은
 * XVARM 모드가 {@code MOCK_DEV} 일 때 {@link #ensureXvarmMockTables()} 가 먼저 만든다.</p>
 *
 * <p><b>식별</b>: 우리가 넣은 행은 전부 {@code SIM} 접두다 (교정번호 {@code SIM…}, 녹취 {@code SIM-MEET-nnn}, 통화 {@code SIM-PHONE-nnn},
 * 공통파일 {@code SIMCMFInnnn}, 문서 {@code SIMDOCnnnn}). 초기화는 이 접두만 지우므로 운영·다른 사람 데이터는 건드리지 않는다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class SimulationDataService {

    /** 시연 기본 — 트랙별 일배치 5건(어제 하루) + 주기배치 2건(10분 창 1 · 10~20분 1) = 7건, 합계 14건. */
    public static final int DAILY_PER_KIND = 5;
    public static final int PERIODIC_PER_KIND = 2;
    /**
     * 주기배치용 2건을 <b>지금부터 몇 분 전</b>에 둘지 — 트랙별.
     *
     * <p>앞엣것은 10분 창 안, 뒤엣것은 10~20분 사이다. 주기배치는 10분마다 돌면서 <b>20분</b>
     * ({@code voice.batch.periodic-lag-min})을 훑으므로, 뒤엣것은 "10분 창만 봤다면 놓쳤을 건"이다.
     * 접견·전화를 서로 다른 분에 두어 로그에서 어느 트랙의 건인지 바로 읽힌다.</p>
     */
    private static final Map<VoiceKind, int[]> PERIODIC_OFFSET_MIN = Map.of(
            VoiceKind.MEET, new int[] {6, 18},
            VoiceKind.PHONE, new int[] {4, 16});
    /** 하루 = 86400초. 일배치용 건을 어제 00:00:00~23:59:59 에 고르게 펼 때 쓴다. */
    private static final long DAY_SECONDS = 24L * 60 * 60;
    public static final int MEET_COUNT = DAILY_PER_KIND + PERIODIC_PER_KIND;
    public static final int PHONE_COUNT = DAILY_PER_KIND + PERIODIC_PER_KIND;
    /**
     * 이 건수를 넘는 대용량 시딩은 더미 파일을 쓰지 않는다(파이프라인은 브로커·Mock 이 만든 파일을 쓴다).
     *
     * <p>성능 테스트 상한({@link #PERF_MAX_TOTAL})과 같게 둔다 — 브로커가 {@code REST} 면 이 원본을 그대로
     * 가져가므로, 더미가 없으면 201번째 건부터 원본이 없어 실패한다.</p>
     */
    public static final int FILE_LIMIT = 300;
    /**
     * 성능 테스트 한 번에 만드는 최대 건수 — 운영 일일 처리 한도(300건)와 같다.
     *
     * <p>이 건수까지는 <b>개발계 공용 DB 에도</b> 만든다. 원천 조회·Hikari 가 실측이어야 성능 테스트가
     * 의미가 있기 때문이다. SIM 접두라 초기화가 지울 수 있고, 성능 테스트는 끝나면 스스로 지운다.</p>
     */
    public static final int PERF_MAX_TOTAL = 300;
    /** 이 번호의 전화 건은 "보라미가 이미 STT 를 가지고 있는" 시나리오(계획서 Q1). */
    public static final int PHONE_WITH_SOURCE_STT = 3;
    /** 이 번호의 접견 건은 암호화되지 않은 파일 — 복호화가 통과(Noop)하는지 본다. */
    public static final int MEET_UNENCRYPTED = 2;

    private static final String USR = "simadm";
    /** 특이수용자 코드 — 001 만 마약(1) 이라 코드 필터 테스트의 기준이 된다. */
    private static final String[] SPECL_CODES = {"1", "2", "3", "0", "5"};

    private final JdbcTemplate jdbc;
    private final BoramiTableNames tables;
    private final DbKindDetector db;
    private final VoiceDirState dirs;
    private final VoiceProperties props;
    private final MockDatasetState dataset;
    private final VoiceModeState modeState;
    /** DB 구간에만 트랜잭션을 건다 — 파일 쓰기·암호화 동안 커넥션을 잡지 않게(@Transactional 은 메서드 전체를 잡는다). */
    private final PlatformTransactionManager txManager;

    // ── 조회 ──────────────────────────────────────────────────────────────

    /** 현재 상태 — DB 종류, 테이블, SIM 행 수, 더미 파일 수. 실패해도 예외 대신 사유를 싣는다. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("dbUrl", db.url());
        out.put("xvarmMode", tables.isXvarmMock() ? "MOCK_DEV" : "REAL");
        out.put("tables", tables.describe());
        Map<String, Object> rows = new LinkedHashMap<>();
        try {
            rows.put("inmates", count(tables.imscPtprDt(), "CORR_NO LIKE 'SIM%'"));
            rows.put("meet", count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%'"));
            rows.put("phone", count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-%'"));
            rows.put("cmfi", count(tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'SIMCMFI%'"));
            rows.put("xvarm", count(tables.asysContentElement(), "ELEMENTID LIKE 'SIMDOC%'"));
        } catch (Exception e) {
            rows.put("error", rootMessage(e));
        }
        out.put("rows", rows);
        out.put("files", fileStatus());
        return out;
    }

    // ── 생성 ──────────────────────────────────────────────────────────────

    /**
     * Clean &amp; Seed — 기존 SIM 행·더미 파일을 지운 뒤 시연 기본 10건(트랙별 일배치 3 + 주기 2)을 새로 만든다.
     *
     * @return 결과 요약(테이블별 행 수 · 파일 목록 · 폴더)
     */
    public Map<String, Object> seed() {
        return seed(DAILY_PER_KIND, DAILY_PER_KIND);
    }

    /**
     * Clean &amp; Seed — 일배치용 건수를 지정한다(대용량 시험). 주기배치용 2·2 는 늘 같다.
     *
     * <p><b>대용량(기본 10건 초과)은 로컬 H2 에서만</b> 허용한다 — 개발계 DB 에 수천 행을 넣으면 남의 시험을 방해한다.
     * {@link #FILE_LIMIT} 을 넘으면 더미 파일은 쓰지 않는다.</p>
     */
    public Map<String, Object> seed(int dailyMeet, int dailyPhone) {
        boolean bulk = dailyMeet > DAILY_PER_KIND || dailyPhone > DAILY_PER_KIND;
        if (bulk && !db.isH2()) {
            throw new IllegalStateException("대용량 시딩(일배치 " + dailyMeet + "·" + dailyPhone + ")은 로컬 H2 에서만 허용한다 — 지금 대상: " + db.label());
        }
        // 시연 기본 — 전화 3번 한 건만 '보라미가 이미 STT 를 가진' 건(계획서 Q1)
        return seed(dailyMeet, dailyPhone, PERIODIC_PER_KIND,
                i -> i == PHONE_WITH_SOURCE_STT && dailyPhone >= PHONE_WITH_SOURCE_STT);
    }

    /**
     * 성능 테스트용 Clean &amp; Seed — <b>정확히 접견 {@code meet} · 전화 {@code phone} 건</b>을 어제 하루
     * ({@code [어제 00:00, 오늘 00:00)})에 고르게 만든다. 주기배치용 건은 만들지 않는다.
     *
     * <p>어제에 두는 이유: 주기배치(당일 00:00~지금)가 켜져 있어도 성능 테스트 데이터를 집어 가지 않는다.</p>
     *
     * <p>합계 {@link #PERF_MAX_TOTAL} 건까지는 개발계 공용 DB 에도 만든다(일반 대용량 시딩은 H2 전용).</p>
     */
    public Map<String, Object> seedPerf(int meet, int phone) {
        return seedPerf(meet, phone, 0);
    }

    /**
     * 위와 같되, 전화 중 {@code sttPercent}% 에 <b>보라미 기존 STT</b>({@code TELP_STT_FLPTH_NM})를 채운다.
     *
     * <p>수집기는 그 건의 복호화·STT 를 건너뛰고 기존 텍스트를 쓴다(Bypass). 그 건들은 순식간에 끝나
     * 다른 워커의 긴 STT 와 나란히 저장·T4 적재에 닿는다 — 동시 처리의 경합을 보는 표본이다.
     * 경로만 채우면 수집기가 파일을 못 찾아 일반 경로로 돌아가므로 더미 텍스트 파일도 같이 쓴다.</p>
     *
     * <p>고르는 방법: 반올림한 건수를 전화 번호 전체에 고르게 편다(예: 150건 3% → 5건, 16·46·76·106·136번).</p>
     */
    public Map<String, Object> seedPerf(int meet, int phone, int sttPercent) {
        if (meet + phone > PERF_MAX_TOTAL) {
            throw new IllegalArgumentException("성능 테스트 데이터는 최대 " + PERF_MAX_TOTAL + "건이다: " + (meet + phone));
        }
        if (sttPercent < 0 || sttPercent > 100) {
            throw new IllegalArgumentException("기 STT 비율은 0~100% 이어야 한다: " + sttPercent);
        }
        java.util.Set<Integer> withStt = sourceSttPicks(phone, sttPercent);
        Map<String, Object> out = seed(meet, phone, 0, withStt::contains);
        out.put("sourceSttPhones", withStt.size());
        return out;
    }

    /** 전화 {@code phone} 건 중 {@code percent}% 를 번호 전체에 고르게 고른다(1부터). */
    static java.util.Set<Integer> sourceSttPicks(int phone, int percent) {
        int n = (int) Math.round(phone * percent / 100d);
        java.util.Set<Integer> picks = new java.util.TreeSet<>();
        for (int k = 0; k < n; k++) {
            picks.add(Math.min(phone, 1 + (int) Math.floor((k + 0.5) * phone / n)));
        }
        return picks;
    }

    private Map<String, Object> seed(int dailyMeet, int dailyPhone, int periodicPerKind,
                                     java.util.function.IntPredicate withSourceStt) {
        if (dailyMeet < 0 || dailyPhone < 0) {
            throw new IllegalArgumentException("건수는 음수일 수 없다");
        }
        dataset.set(dailyMeet, dailyPhone);
        int meetCount = dailyMeet + periodicPerKind;
        int phoneCount = dailyPhone + periodicPerKind;
        boolean writeFiles = meetCount + phoneCount <= FILE_LIMIT;

        SqlTrace trace = new SqlTrace();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("target", db.target().name());
        out.put("dirsEnsured", dirs.ensureDirs());
        // ── DB — 한 트랜잭션. 커넥션은 여기서만 잡는다 ─────────────────────────────
        //   더미 오디오 생성·암호화·디스크 쓰기는 트랜잭션 안에서 하지 않는다 — 300건이면 수 초 동안 커넥션을
        //   붙잡는다. 쓸 파일은 목록으로 모았다가 커밋(커넥션 반납) 뒤에 쓴다. 파일 쓰기는 실패해도 예외 없이
        //   사유만 남기므로(writeDummy) 행과 파일의 원자성은 예전과 같다.
        int inmates = Math.max(meetCount, phoneCount);
        Path meetDir = dirs.xvarmOriginalDir(VoiceKind.MEET);
        Path phoneDir = dirs.xvarmOriginalDir(VoiceKind.PHONE);
        List<java.util.function.Supplier<Map<String, Object>>> fileJobs = new ArrayList<>();
        inTransaction(() -> seedRows(trace, out, fileJobs, dailyMeet, dailyPhone, meetCount, phoneCount, inmates,
                writeFiles, withSourceStt, meetDir, phoneDir));

        // ── 파일 — 커밋 뒤 ─────────────────────────────────────────────────────
        List<Map<String, Object>> files = new ArrayList<>(fileJobs.size());
        fileJobs.forEach(job -> files.add(job.get()));

        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("inmates", inmates);
        rows.put("meet", meetCount);
        rows.put("phone", phoneCount);
        rows.put("cmfi", meetCount);
        rows.put("xvarm", meetCount);
        out.put("rows", rows);
        out.put("files", files);
        out.put("filesSkipped", !writeFiles);
        out.put("dirs", Map.of("meet", slash(meetDir), "phone", slash(phoneDir)));
        out.put("tables", tables.describe());
        out.put("windows", Map.of(
                "daily", "접견 %d · 전화 %d (어제 00:00:00~23:59:59 에 고르게 · 자정 직후면 주기 창이 닿지 않는 앞까지)"
                        .formatted(dailyMeet, dailyPhone),
                "periodic", "접견 %s · 전화 %s (분 단위, 지금 기준)".formatted(
                        offsetText(VoiceKind.MEET), offsetText(VoiceKind.PHONE))));
        // 화면 팝업이 쓰는 건수 — 일배치용/주기용을 나눠 센다. rows 는 테이블별이라 이 구분이 나오지 않는다.
        out.put("counts", Map.of(
                "daily", Map.of("meet", dailyMeet, "phone", dailyPhone, "total", dailyMeet + dailyPhone),
                "periodic", Map.of("meet", periodicPerKind, "phone", periodicPerKind,
                        "total", periodicPerKind * 2,
                        "offsetMin", Map.of("meet", PERIODIC_OFFSET_MIN.get(VoiceKind.MEET),
                                "phone", PERIODIC_OFFSET_MIN.get(VoiceKind.PHONE))),
                "total", meetCount + phoneCount,
                "files", files.size()));
        trace.into(out);
        log.info("[Sim] 시뮬레이션 데이터 생성 — {} · 파일 {}개{} · SQL {}문장 · {}", rows, files.size(),
                writeFiles ? "" : " (대용량 — 더미 파일 생략)", trace.total, tables.describe());
        return out;
    }

    /** 시딩의 DB 구간 — 스키마 확인 · SIM 행 정리 · 삽입. 쓸 더미 파일은 {@code fileJobs} 에 모으기만 한다. */
    private void seedRows(SqlTrace trace, Map<String, Object> out,
                          List<java.util.function.Supplier<Map<String, Object>>> fileJobs,
                          int dailyMeet, int dailyPhone, int meetCount, int phoneCount, int inmates, boolean writeFiles,
                          java.util.function.IntPredicate withSourceStt, Path meetDir, Path phoneDir) {
        out.put("ensured", ensureXvarmMockTables(trace));
        Map<String, Object> cleaned = cleanRows(trace);
        out.put("cleaned", cleaned);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        Timestamp ts = Timestamp.valueOf(now);
        int guardMin = midnightGuardMin();
        // 특이수용자 — 접견·전화가 같은 사람(001~)을 쓴다. 코드는 1/2/3/0/5 를 돌려 가며 준다
        List<Object[]> inmateRows = new ArrayList<>();
        for (int i = 1; i <= inmates; i++) {
            // 001 만 마약(1) — 나머지는 2/3/0/5 를 돌린다(코드 필터 테스트가 001 하나만 기대한다)
            String code = i == 1 ? SPECL_CODES[0] : SPECL_CODES[1 + ((i - 2) % (SPECL_CODES.length - 1))];
            inmateRows.add(new Object[] {corrNo(i), 1, code, "A01", "20260101", null, ts, USR, ts, USR});
        }
        batch(trace, "INSERT INTO " + tables.imscPtprDt()
                + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)", inmateRows);

        // 접견 — re → im → sm → xvarm
        for (int i = 1; i <= meetCount; i++) {
            LocalDateTime at = occurredAt(now, i, dailyMeet, VoiceKind.MEET, guardMin);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = meetFileName(i);
            Path file = meetDir.resolve(fileNm);
            String cmfi = "SIMCMFI" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i));
            String doc = "SIMDOC" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i));
            exec(trace, "INSERT INTO " + tables.smsmCmfiBs()
                    + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    cmfi, doc, fileNm, "01", "A", crt, "Y", i == MEET_UNENCRYPTED ? "N" : "Y", "N", crt, USR, crt, USR);
            exec(trace, "INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)",
                    doc, slash(file));
            exec(trace, "INSERT INTO " + tables.rerdTfinDs()
                    + " (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,"
                    + "  TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS, TARE_FILE_MG_VL, TARE_FLPTH_NM,"
                    + "  DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    meetKey(i), "CI00001", "01", ymd(at), i, corrNo(i), ymd(at),
                    "SIMTRCD" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i)), cmfi, fileNm, hms(at), hms(at.plusMinutes(10)), "1024", slash(meetDir),
                    "N", "N", "N", crt, USR, crt, USR);
            if (writeFiles) {
                String key = meetKey(i);
                boolean encrypted = i != MEET_UNENCRYPTED;
                fileJobs.add(() -> writeDummy(file, "m4a", key, encrypted));
            }
        }

        // 전화 — im 통화내역 → im 특이수용자
        for (int i = 1; i <= phoneCount; i++) {
            LocalDateTime at = occurredAt(now, i, dailyPhone, VoiceKind.PHONE, guardMin);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = phoneFileName(i);
            Path file = phoneDir.resolve(fileNm);
            String sttPath = null;
            if (withSourceStt.test(i)) {
                Path stt = phoneDir.resolve("mock_phone_" + seq(i) + ".stt.txt");
                fileJobs.add(() -> writeText(stt, "(보라미 기존 STT / 시뮬레이션) 여보세요 저 김수용입니다. 어머니 잘 계시죠.\n"
                        + "연락처 010-9876-5432 로 전화 주세요. 주민번호는 900101-1234567 입니다.\n"));
                sttPath = slash(stt);
            }
            exec(trace, "INSERT INTO " + tables.imphUcdrDs()
                    + " (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD, RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO,"
                    + "  TELP_PCALL_BGNG_DT, TELP_PCALL_RSPNS_DT, TELP_PCALL_END_DT, TELP_PCALL_TIME, TELP_RSPNS_TIME, TELP_PCALL_RSPNS_YN,"
                    + "  TELP_PCALL_OCRN_AMT, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT, CORR_INSTT_CD, TELP_USE_PLACE_NM,"
                    + "  TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM, TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    phoneKey(i), "P001", "01", corrNo(i), "L001", "(시뮬레이션)수신자" + i, "(시뮬레이션)관계", "000-0000-000" + i,
                    dt14(at), dt14(at.plusSeconds(5)), dt14(at.plusMinutes(3)), 180, 5, "Y",
                    0, "Y", props.source().flag().ptcrYes(), 1, "CI00001", "(시뮬레이션)전화실",
                    slash(phoneDir), fileNm, "SIMPHONEKEY" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i)), sttPath, null,
                    crt, USR, crt, USR);
            if (writeFiles) {
                String key = phoneKey(i);
                fileJobs.add(() -> writeDummy(file, "wav", key, true));
            }
        }

    }

    /** DB 작업을 한 트랜잭션으로 — 끝나면 커밋하고 커넥션을 바로 반납한다. 예외면 롤백. */
    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    /** Complete Clean — SIM 행 일괄 삭제 + 원본 스토리지의 더미 파일 삭제. */
    public Map<String, Object> clean() {
        SqlTrace trace = new SqlTrace();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("dirsEnsured", dirs.ensureDirs());
        Map<String, Object> cleaned = new LinkedHashMap<>();
        inTransaction(() -> cleaned.putAll(cleanRows(trace)));
        Map<String, Integer> filesDeleted = deleteDummyFiles();   // 커밋 뒤 — 파일 삭제 동안 커넥션을 잡지 않는다
        out.put("cleaned", cleaned);
        out.put("filesDeleted", filesDeleted);
        // 화면 팝업이 쓰는 합계. 테이블이 없어 건너뛴 항목("skip: …")은 숫자가 아니므로 빼고 센다.
        out.put("counts", Map.of(
                "dbRows", cleaned.values().stream().filter(Integer.class::isInstance).mapToInt(v -> (Integer) v).sum(),
                "dummyFiles", filesDeleted.values().stream().mapToInt(Integer::intValue).sum()));
        trace.into(out);
        log.info("[Sim] 시뮬레이션 데이터 초기화 — {}", out);
        return out;
    }

    /** SIM 접두 행만 지운다 — 자식(xvarm·sm·re·im 통화) → 부모(im 수용자). 테이블이 없으면(REAL 인데 미구축) 사유만 남긴다. */
    private Map<String, Object> cleanRows(SqlTrace trace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xvarm", safeDelete(trace, tables.asysContentElement(), "ELEMENTID LIKE 'SIMDOC%'"));
        m.put("cmfi", safeDelete(trace, tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'SIMCMFI%'"));
        m.put("meet", safeDelete(trace, tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%'"));
        m.put("phone", safeDelete(trace, tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-%'"));
        m.put("inmates", safeDelete(trace, tables.imscPtprDt(), "CORR_NO LIKE 'SIM%'"));
        return m;
    }

    private Object safeDelete(SqlTrace trace, String table, String where) {
        String sql = "DELETE FROM " + table + " WHERE " + where;
        try {
            trace.add(sql);
            return jdbc.update(sql);
        } catch (Exception e) {
            String msg = rootMessage(e);
            trace.mark("-- ↑ 실패(건너뜀): " + msg);
            log.warn("[Sim] 삭제 건너뜀 — {} ({})", table, msg);
            return "skip: " + msg;
        }
    }

    /** 원본 스토리지의 더미 파일({@code mock_meet_*} · {@code mock_phone_*})을 지운다. 우리가 만든 이름만 — 남의 파일은 손 대지 않는다. */
    private Map<String, Integer> deleteDummyFiles() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VoiceKind k : VoiceKind.values()) {
            Path dir = dirs.xvarmOriginalDir(k);
            String prefix = k == VoiceKind.MEET ? "mock_meet_" : "mock_phone_";
            int n = 0;
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path f : s.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                        Files.deleteIfExists(f);
                        n++;
                    }
                } catch (IOException e) {
                    log.warn("[Sim] 더미 파일 삭제 실패 — {} ({})", dir, e.getMessage());
                }
            }
            m.put(k.name(), n);
        }
        return m;
    }

    // ── XVARM MOCK 테이블 ──────────────────────────────────────────────────

    /**
     * 개발계 PostgreSQL 에 누락된 공통파일기본·XVARM 테이블을 만든다(CREATE … IF NOT EXISTS).
     * XVARM 모드가 REAL 이거나 H2(스키마 스크립트가 이미 만든다)면 건너뛴다.
     *
     * @return 무엇을 했는지 한 줄
     */
    public String ensureXvarmMockTables() {
        return ensureXvarmMockTables(null);
    }

    /** 위와 같되, 실행한 DDL 을 화면용 SQL 목록에 같이 남긴다. */
    private String ensureXvarmMockTables(SqlTrace trace) {
        if (!tables.isXvarmMock()) {
            return "REAL 모드 — 실 테이블(" + tables.smsmCmfiBs() + " · " + tables.asysContentElement() + ")을 그대로 쓴다";
        }
        if (db.isH2()) {
            return "H2 — schema-borami-mock.sql 이 이미 만든 평평한 테이블을 쓴다";
        }
        if (!db.isPostgres()) {
            throw new IllegalStateException("XVARM MOCK 테이블 자동 생성은 PostgreSQL(개발계)만 지원한다 — 현재 " + db.label());
        }
        String sm = tables.smsmSchema();
        String xv = tables.xvarmSchema();
        String ddl;
        try {
            ddl = new String(new ClassPathResource("sql/xvarm_mock_tables_postgres.sql").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("DDL 리소스를 읽지 못했다: sql/xvarm_mock_tables_postgres.sql", e);
        }
        // 주석을 먼저 걷어낸 뒤 세미콜론으로 문장을 나눈다 — 주석 안의 세미콜론이 문장을 쪼개던 문제
        StringBuilder body = new StringBuilder();
        ddl.replace("__SM__", sm).replace("__XVARM__", xv).lines().forEach(l -> {
            int c = l.indexOf("--");
            body.append(c >= 0 ? l.substring(0, c) : l).append('\n');
        });
        int n = 0;
        for (String stmt : body.toString().split(";")) {
            String sql = stmt.trim();
            if (sql.isEmpty()) {
                continue;
            }
            if (trace != null) {
                trace.add(sql);
            }
            jdbc.execute(sql);
            n++;
        }
        String msg = "PostgreSQL — %s.tb_smsm_cmfi_bs · %s.asyscontentelement 확인/생성 (%d문장)".formatted(sm, xv, n);
        log.info("[Sim] {}", msg);
        return msg;
    }

    // ── SQL 트레이스 ──────────────────────────────────────────────────────

    /** 바인딩 실행 + 화면용 SQL 기록. */
    private void exec(SqlTrace trace, String sql, Object... args) {
        trace.add(sql, args);
        jdbc.update(sql, args);
    }

    /** 배치 실행 + 화면용 SQL 기록(행마다 한 문장으로 풀어 적는다). */
    private void batch(SqlTrace trace, String sql, List<Object[]> rows) {
        for (Object[] r : rows) {
            trace.add(sql, r);
        }
        jdbc.batchUpdate(sql, rows);
    }

    /**
     * 시딩에 쓴 SQL 을 <b>화면에 보여 주기 위해</b> 모아 둔다.
     *
     * <p><b>여기서 만드는 문자열은 표시 전용이다 — 실행되는 SQL 이 아니다.</b> 실제 실행은 끝까지
     * 바인딩({@code ?})이고, 이 클래스는 읽는 사람이 DBeaver 에 그대로 붙여 넣어 대조할 수 있도록
     * 값만 끼워 넣은 사본을 만든다. 그래서 문자열 값은 따옴표를 겹쳐 이스케이프한다 — SQL 주입 경로가
     * 아니라 <b>보이는 문장이 실제로 실행된 것과 달라지지 않게</b> 하려는 것이다.</p>
     *
     * <p>같은 형태의 문장은 <b>테이블 종류별로 한 번만</b> 싣는다 — 14건이면 INSERT 만 40문장이 되는데
     * 전부 실으면 응답이 붓고 콘솔이 잠긴다. 생략한 건수는 요약 주석으로 알린다.</p>
     */
    private static final class SqlTrace {

        private static final java.time.format.DateTimeFormatter TS_FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        /** 같은 (동사, 테이블) 로 묶은 한 덩어리 — 대표 문장 1건과 전체 건수. */
        private static final class Group {
            final String sample;
            int count;
            String note;

            Group(String sample) {
                this.sample = sample;
            }
        }

        /** 삽입 순서를 지키는 묶음 — 키는 "INSERT INTO im.TB_IMSC_PTPR_DT" 처럼 동사+테이블. */
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private int total;

        void add(String sql, Object... args) {
            total++;
            String one = sql.replaceAll("\s+", " ").trim();
            Group g = groups.computeIfAbsent(key(one), k -> new Group(render(one, args)));
            g.count++;
        }

        /** 마지막 묶음에 붙는 메모(실패 사유 등). 총 건수에는 넣지 않는다. */
        void mark(String note) {
            groups.values().stream().reduce((a, b) -> b).ifPresent(g -> g.note = note);
        }

        /**
         * 화면에 뿌릴 줄을 만든다 — <b>테이블 종류별 대표 1건</b> 과 요약 주석.
         *
         * <p>14건이면 INSERT 만 네 테이블에 흩어져 40문장이 나온다. 전부 찍으면 콘솔이 잠기고
         * 정작 "어느 테이블에 무엇이 들어갔나" 가 안 읽힌다. 형태가 같은 문장은 한 번만 보이면 된다.</p>
         */
        void into(Map<String, Object> out) {
            List<String> lines = new ArrayList<>();
            for (Group g : groups.values()) {
                lines.add(g.sample);
                if (g.count > 1) {
                    lines.add("-- (해당 테이블 %d건 중 1건 표시 / %d건 생략)".formatted(g.count, g.count - 1));
                }
                if (g.note != null) {
                    lines.add(g.note);
                }
            }
            out.put("sqls", List.copyOf(lines));
            out.put("sqlTotal", total);
            out.put("sqlGroups", groups.size());
            out.put("sqlTruncated", total > groups.size());
        }

        /**
         * 묶음 키 — 동사와 테이블명까지만 자른다.
         * {@code INSERT INTO x (…) VALUES (…)} → {@code INSERT INTO x},
         * {@code DELETE FROM x WHERE …} → {@code DELETE FROM x}. 나머지(DDL 등)는 문장 전체가 키다.
         */
        private static String key(String one) {
            for (String verb : new String[] {"INSERT INTO ", "DELETE FROM ", "UPDATE "}) {
                if (one.regionMatches(true, 0, verb, 0, verb.length())) {
                    int from = verb.length();
                    int end = one.length();
                    for (int i = from; i < end; i++) {
                        char c = one.charAt(i);
                        if (c == ' ' || c == '(') {
                            end = i;
                            break;
                        }
                    }
                    return one.substring(0, verb.length()) + one.substring(from, end);
                }
            }
            return one;
        }

        private static String render(String sql, Object... args) {
            String one = sql.replaceAll("\\s+", " ").trim();
            StringBuilder sb = new StringBuilder(one.length() + 64);
            int ai = 0;
            for (int i = 0; i < one.length(); i++) {
                char c = one.charAt(i);
                if (c == '?' && ai < args.length) {
                    sb.append(literal(args[ai++]));
                } else {
                    sb.append(c);
                }
            }
            return sb.append(';').toString();
        }

        private static String literal(Object v) {
            if (v == null) {
                return "NULL";
            }
            if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
            if (v instanceof Timestamp t) {
                // toString() 은 'T' 를 끼우고 초가 0이면 떨어뜨린다 — 붙여 넣어 쓸 수 있게 고정 형식으로 적는다
                return "TIMESTAMP '" + TS_FMT.format(t.toLocalDateTime()) + "'";
            }
            return "'" + String.valueOf(v).replace("'", "''") + "'";
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * i 번째 건의 발생 시각.
     *
     * <p><b>일배치용(1~daily)</b>은 <b>어제 00:00:00 ~ 23:59:59</b> 에 고르게 편다. 첫 건과 마지막 건이
     * 창의 양 끝에 정확히 놓이므로 일배치 창 {@code [어제 00:00, 오늘 00:00)} 의 경계 포함/제외까지 같이 본다.
     * 예전에는 09:10 부터 10분 간격이라 하루의 앞뒤가 비어 있었다.</p>
     *
     * <p><b>주기배치용(그 뒤 2건)</b>은 {@link #PERIODIC_OFFSET_MIN} 대로 <b>10분 창 안 1건 · 10~20분 사이 1건</b>
     * 이다. 주기배치는 10분마다 돌지만 창은 20분({@code periodic-lag-min})이라, 뒤엣것은 <b>10분 창만 봤다면
     * 놓쳤을 지연 건</b>이 된다 — 20분 창이 실제로 주워 오는지 확인하는 표본이다. 트랙마다 분을 다르게 둬서
     * 로그에서 접견·전화가 섞이지 않는다.</p>
     *
     * <p><b>자정 직후에도 두 창이 섞이지 않게</b> — 주기 창은 {@code [min(지금-lag, 당일 00:00), 지금)} 이라 자정 직후에는
     * 어제 끝자락까지 거슬러 간다. 그대로 두면 00:15 에 ① 주기용 '지금-18분'(어제 23:57)이 어제 일배치 창으로 넘어가고
     * ② 일배치용 마지막 건(23:59:59)이 주기 창에 걸린다. 2026-09-30 Jenkins 빌드(00:15 UTC)가 이것으로 8건 실패했다 —
     * 빌드 에이전트는 UTC 라 <b>한국 시각 09:00~09:20 푸시가 매일</b> 걸렸다. 그래서</p>
     * <ul>
     *   <li>일배치용은 {@code 지금 - guardMin} 보다 앞에서 끝낸다 — 끝이 당겨지는 것은 자정 직후뿐이다</li>
     *   <li>주기용은 당일 00:00 아래로 내려가지 않는다 — 오래된 건이 먼저인 순서는 초 단위로 지킨다</li>
     * </ul>
     *
     * @param guardMin 주기 창이 자정 너머로 거슬러 갈 수 있는 최대 분(lag) + 여유
     */
    static LocalDateTime occurredAt(LocalDateTime now, int i, int daily, VoiceKind kind, int guardMin) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        if (i <= daily) {
            LocalDateTime yesterday = today.minusDays(1);
            // 끝 — 보통 23:59:59. 자정 직후면 주기 창이 닿지 않는 '지금-guard' 앞까지
            LocalDateTime end = today.minusSeconds(1);
            LocalDateTime guard = now.minusMinutes(guardMin).minusSeconds(1);
            if (guard.isBefore(end)) {
                end = guard;
            }
            // 마지막 건이 끝에 닿도록 (daily-1) 등분한다. 1건이면 00:00:00.
            long span = Math.max(0L, Duration.between(yesterday, end).getSeconds());
            long sec = daily <= 1 ? 0 : ((long) (i - 1) * span) / (daily - 1);
            return yesterday.plusSeconds(sec);
        }
        int[] offsets = PERIODIC_OFFSET_MIN.get(kind);
        int k = Math.min(i - daily, offsets.length);            // 1, 2
        LocalDateTime at = now.minusMinutes(offsets[k - 1]);
        // 자정을 넘기면 당일 00:00 으로 올린다 — 오래된 건(k 가 큰 쪽)이 앞서도록 초 단위로 벌린다
        LocalDateTime floor = today.plusSeconds(offsets.length - k);
        return at.isBefore(floor) ? floor : at;
    }

    /** 자정 직후 일배치용 건을 당기는 여유 — 주기 창이 거슬러 갈 수 있는 최대 분(lag, 시험은 최대 20) + 10분. */
    private int midnightGuardMin() {
        return Math.max(20, props.batch().periodicLagMin()) + 10;
    }

    /** "지금-6분 / 지금-18분" 처럼 트랙의 주기배치 오프셋을 읽을 수 있게 적는다. */
    private static String offsetText(VoiceKind kind) {
        int[] o = PERIODIC_OFFSET_MIN.get(kind);
        StringBuilder sb = new StringBuilder();
        for (int m : o) {
            sb.append(sb.length() == 0 ? "" : " / ").append("지금-").append(m).append("분");
        }
        return sb.toString();
    }

    /** 세 자리를 넘는 대용량은 자릿수를 늘린다 — 키 형식은 시연 10건(SIM-MEET-001…)과 같다. */
    private static String seq(int i) { return i < 1000 ? "%03d".formatted(i) : "%05d".formatted(i); }

    public static String corrNo(int i) { return "SIM%014d".formatted(i); }
    public static String meetKey(int i) { return "SIM-MEET-" + seq(i); }
    public static String phoneKey(int i) { return "SIM-PHONE-" + seq(i); }
    public static String meetFileName(int i) { return "mock_meet_" + seq(i) + ".m4a"; }
    public static String phoneFileName(int i) { return "mock_phone_" + seq(i) + ".wav"; }

    private static String ymd(LocalDateTime t) { return "%04d%02d%02d".formatted(t.getYear(), t.getMonthValue(), t.getDayOfMonth()); }
    private static String hms(LocalDateTime t) { return "%02d%02d%02d".formatted(t.getHour(), t.getMinute(), t.getSecond()); }
    private static String dt14(LocalDateTime t) { return ymd(t) + hms(t); }
    private static String slash(Path p) { return p.toAbsolutePath().normalize().toString().replace('\\', '/'); }

    private int count(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * XVARM 원본 스토리지의 더미 파일 — <b>메타가 말하는 그대로 만든다.</b>
     *
     * <p><b>왜 실제 오디오이고, 왜 실제로 암호화하나</b>: 예전에는 한 줄짜리 텍스트를 썼다.
     * 브로커가 {@code MOCK} 일 때는 원본을 보지 않고 자기가 오디오를 만들어 떨구므로 그래도
     * 파이프라인이 돌았다. 그런데 브로커가 {@code REST} 면 <b>이 파일을 그대로 가져간다</b> —
     * 메타는 {@code CMMN_FILE_ENC_YN='Y'} 인데 내용은 평문 텍스트라, 복호화를 REAL 로 올리는
     * 순간 평문에 AES 를 걸다가 {@code IllegalBlockSizeException} 으로 죽었다.
     * 시뮬레이션 데이터는 메타와 앞뒤가 맞아야 한다.</p>
     *
     * <p>키를 못 읽으면 <b>평문으로 둔다</b>. 시딩이 키 문제로 실패하면 화면에서 원인을 찾기
     * 어렵다 — 복호화 단계가 "암호문이 아니다" 라고 말하게 두는 편이 훨씬 읽기 쉽다.</p>
     *
     * @param encrypted 이 건의 메타가 '암호화됨' 인가. 참이고 복호화 모드가 REAL 이면 실제로 암호화한다
     */
    private Map<String, Object> writeDummy(Path file, String kind, String key, boolean encrypted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", slash(file));
        byte[] plain = SilentWav.of(dataset.wavSeconds());
        boolean enc = false;
        byte[] body = plain;
        if (encrypted && modeState.decrypt() == VoiceProperties.DecryptMode.REAL) {
            byte[] c = tryEncrypt(plain, file);
            enc = c != plain;
            body = c;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, body);
            m.put("bytes", body.length);
            m.put("encrypted", enc);
            m.put("written", true);
        } catch (IOException e) {
            m.put("written", false);
            m.put("error", e.getMessage());
            log.warn("[Sim] 더미 파일 생성 실패 — {} ({})", file, e.getMessage());
        }
        return m;
    }

    /** 설정된 키로 암호화한다. 못 하면 원본을 그대로 돌려준다(사유는 경고로 남긴다). */
    private byte[] tryEncrypt(byte[] plain, Path file) {
        String keyPath = props.decrypt().rvsKeyPath();
        if (!org.springframework.util.StringUtils.hasText(keyPath)) {
            log.warn("[Sim] 복호화 REAL 인데 키 경로가 없어 평문으로 둡니다 — voice.decrypt.rvs-key-path ({})",
                    file.getFileName());
            return plain;
        }
        try {
            return egovframework.unstructured.collector.common.decrypt.MediaDecryptor
                    .fromKeyFile(Path.of(keyPath.trim())).encrypt(plain);
        } catch (Exception e) {
            log.warn("[Sim] 더미 암호화 실패 — 평문으로 둡니다 — {} ({})", file.getFileName(), e.getMessage());
            return plain;
        }
    }

    private Map<String, Object> writeText(Path file, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", slash(file));
        try {
            Files.createDirectories(file.getParent());
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            Files.write(file, body);
            m.put("bytes", body.length);
            m.put("written", true);
        } catch (IOException e) {
            m.put("written", false);
            m.put("error", e.getMessage());
            log.warn("[Sim] 더미 파일 생성 실패 — {} ({})", file, e.getMessage());
        }
        return m;
    }

    private Map<String, Object> fileStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (VoiceKind k : VoiceKind.values()) {
            Path dir = dirs.xvarmOriginalDir(k);
            List<String> names = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).sorted().forEach(names::add);
                } catch (IOException ignored) {
                    // 목록을 못 읽으면 빈 목록 — 상태 조회가 실패를 만들면 안 된다
                }
            }
            String prefix = k == VoiceKind.MEET ? "mock_meet_" : "mock_phone_";
            List<String> mine = names.stream().filter(n -> n.startsWith(prefix)).toList();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dir", slash(dir));
            d.put("exists", Files.isDirectory(dir));
            d.put("count", mine.size());
            d.put("names", mine);
            m.put(k.name(), d);
        }
        return m;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String s = cur.getMessage();
        return s == null ? cur.getClass().getSimpleName() : s.replaceAll("\\s+", " ").trim();
    }
}
