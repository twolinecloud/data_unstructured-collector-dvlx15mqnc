package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.DbKindDetector;
import egovframework.unstructured.collector.common.config.MockDatasetState;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.config.VoiceModeState;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.decrypt.MediaDecryptor;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.util.SampleImage;
import egovframework.unstructured.collector.common.util.SilentWav;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.image.store.ImageFileStore;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 더미 데이터 — <b>용도(SIMULATOR / DASHBOARD) · 유형(전화 · 접견 · 이미지) · 대상일 · 건수 · 장애 시나리오</b>를 받아
 * 보라미 원천 행 + 더미 원본 파일을 만들고, 용도별로 지운다.
 *
 * <p><b>기존 시뮬레이션과의 관계</b> — {@code SimulationDataService.seed()} · {@code seedPerf()} · 6번 탭 이미지 시딩은 그대로 둔다.
 * 이것은 그 옆에 <b>추가(append)</b>하는 생성기다. SIMULATOR 용도는 기존과 같은 {@code SIM} 접두 · {@code simadm} 이라 기존
 * [시뮬레이션 데이터 초기화] 가 함께 지운다. DASHBOARD 용도는 {@code DMY} 접두 · {@code dmyadm} 로 따로 두고 따로 지운다.</p>
 *
 * <p><b>정형 오염 방지</b>(작업 지시 1-2) — DASHBOARD 는 실제 수용자를 쓰지 않고 실존하지 않는 {@code DMY…} 교정번호만 쓴다.
 * 수용자기본({@code TB_IRIM_PRBS_BS}) · 신상({@code TB_IRIM_PEIN_BS})에는 <b>어떤 경우에도 쓰지 않는다</b> — 그래야 정형 수집기의
 * JSON 조립 · 특이수용자 원장 · 학습셋 · 대시보드에서 고아로 빠진다. 쓰는 테이블은 응답의 {@code sqlTables} 에 그대로 나온다.</p>
 *
 * <p><b>장애 시나리오</b> — 표식(CF·AF·SF)을 키에 박고, 실제 경로가 그 단계에서 <b>한 번만</b> 실패한다({@link ScenarioFaults}).
 * 유형마다 건수가 같고 순서가 [CF… → AF… → SF… → 정상…] 이라 시나리오마다 {@code CRT_DT} 구간이 겹치지 않는다 — 응답의
 * {@code windows} 로 시나리오 하나만 골라 그 기대 모드로 재처리할 수 있다.</p>
 *
 * <p>dev/local 프로필에서만 빈이 생긴다(운영 미생성). DB 는 한 트랜잭션, 파일은 커밋 뒤에 쓴다(커넥션을 잡고 암호화하지 않는다).</p>
 */
@Log4j2
@Service
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class DummyDataService {

    /** 유형별 · 유형 합계 상한 — 성능 테스트 데이터 상한(운영 일일 처리 한도)과 같다. 개발계 공용 DB 에도 이만큼까지만. */
    public static final int MAX_TOTAL = SimulationDataService.PERF_MAX_TOTAL;
    public static final int DEFAULT_COUNT = 5;
    private static final List<DummyDataType> ALL_TYPES = List.of(DummyDataType.PHONE, DummyDataType.MEET, DummyDataType.IMAGE);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final BoramiTableNames tables;
    private final DbKindDetector db;
    private final VoiceDirState dirs;
    private final VoiceProperties props;
    private final VoiceModeState modeState;
    private final MockDatasetState dataset;
    private final SimulationDataService voiceSim;
    private final ImageSimulationService imageSim;
    private final InmatePhotoRepository photos;
    private final ImageFileStore store;
    private final LocalArtifacts local;
    private final ScenarioFaults scenarioFaults;
    private final MockDataProperties mockProps;

    /** 대시보드 더미가 꺼져 있다 — 403. */
    public static class DashboardDisabledException extends RuntimeException {
        public DashboardDisabledException(String message) {
            super(message);
        }
    }

    @Schema(description = "더미 데이터 생성 요청")
    public record GenerateRequest(
            @Schema(description = "용도 — SIMULATOR(시뮬레이터 시험 실행 · SIM) | DASHBOARD(실제 배치 · 대시보드 · DMY). 비우면 SIMULATOR",
                    example = "DASHBOARD") DummyTarget target,
            @Schema(description = "유형 — PHONE · MEET · IMAGE 복수. 비우면 셋 다", example = "[\"PHONE\",\"MEET\",\"IMAGE\"]")
            List<DummyDataType> dataTypes,
            @Schema(description = "대상일 yyyy-MM-dd — CRT_DT 를 이 날 안에 분산한다. 비우면 어제. 미래는 안 된다", example = "2026-10-01")
            LocalDate targetDate,
            @Schema(description = "유형별 건수 1~300 (유형 합계도 300 이하). 비우면 5", example = "10") Integer count,
            @Schema(description = "유형별 장애 건수 — COLLECT_FAIL · ANALYZE_FAIL · SEND_FAIL. 나머지는 정상",
                    example = "{\"COLLECT_FAIL\":1,\"ANALYZE_FAIL\":1,\"SEND_FAIL\":1}") Map<FailureScenario, Integer> failures,
            @Schema(description = "기존 데이터를 지우지 않고 추가(기본 true). false 면 그 용도의 데이터를 먼저 지운다", example = "true")
            Boolean append) {}

    /** 검증을 마친 생성 계획. */
    private record Plan(DummyTarget target, List<DummyDataType> types, LocalDate date, int count,
                        Map<FailureScenario, Integer> failures, boolean append) {

        int failTotal() {
            return failures.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** 건의 순서 — [CF… → AF… → SF… → 정상…]. null 이 정상. */
        List<FailureScenario> order() {
            List<FailureScenario> o = new ArrayList<>(count);
            failures.forEach((s, n) -> {
                for (int i = 0; i < n; i++) {
                    o.add(s);
                }
            });
            while (o.size() < count) {
                o.add(null);
            }
            return o;
        }
    }

    /** 커밋 뒤에 쓸 파일 하나. */
    private record FileJob(DummyDataType type, Supplier<Map<String, Object>> write) {}

    // ══════════════════════════════════════════════════════════════════════
    //  생성
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 만든다 — 기본은 추가(append). 같은 날짜로 여러 번 불러도 순번을 이어 받아 키가 겹치지 않는다.
     *
     * @throws IllegalArgumentException     요청 값이 잘못됐다(400)
     * @throws DashboardDisabledException   DASHBOARD 인데 {@code unstructured.mock.dashboard.enabled=false}(403)
     */
    public synchronized Map<String, Object> generate(GenerateRequest req) {
        Plan p = plan(req);
        SqlLog sql = new SqlLog();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", p.target().name());
        out.put("targetLabel", p.target().label());
        out.put("prefix", p.target().prefix());
        out.put("crtUsrId", p.target().usr());
        out.put("targetDate", p.date().toString());
        out.put("count", p.count());
        out.put("dataTypes", p.types().stream().map(Enum::name).toList());
        out.put("append", p.append());
        out.put("db", db.label());
        out.put("dirsEnsured", dirs.ensureDirs());
        out.put("ensured", voiceSim.ensureXvarmMockTables());
        if (!p.append()) {
            out.put("cleanedBefore", p.target() == DummyTarget.DASHBOARD ? cleanDashboard() : cleanSimulator());
        }

        MediaDecryptor enc = encryptor();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Slots slots = Slots.of(p.date(), p.count(), now);
        List<FailureScenario> order = p.order();
        List<FileJob> jobs = new ArrayList<>();
        Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            for (DummyDataType type : p.types()) {
                types.put(type.name(), switch (type) {
                    case MEET -> meet(p, order, slots, enc, sql, jobs);
                    case PHONE -> phone(p, order, slots, enc, sql, jobs);
                    case IMAGE -> image(p, order, slots, enc, sql, jobs);
                });
            }
        });

        // ── 파일 — 커밋 뒤 ─────────────────────────────────────────────────
        Map<DummyDataType, List<Map<String, Object>>> files = new EnumMap<>(DummyDataType.class);
        for (FileJob j : jobs) {
            files.computeIfAbsent(j.type(), k -> new ArrayList<>()).add(j.write().get());
        }
        int written = 0;
        for (DummyDataType type : p.types()) {
            List<Map<String, Object>> f = files.getOrDefault(type, List.of());
            types.get(type.name()).put("files", f);
            written += (int) f.stream().filter(m -> Boolean.TRUE.equals(m.get("written"))).count();
        }

        // 실제로 넣은 CRT_DT 의 처음 · 끝 — 유형별 범위를 합친다(ISO 문자열이라 사전순이 곧 시간순)
        String crtFrom = null;
        String crtTo = null;
        for (Map<String, Object> m : types.values()) {
            @SuppressWarnings("unchecked")
            Map<String, String> r = (Map<String, String>) m.get("crtDt");
            crtFrom = crtFrom == null || r.get("from").compareTo(crtFrom) < 0 ? r.get("from") : crtFrom;
            crtTo = crtTo == null || r.get("to").compareTo(crtTo) > 0 ? r.get("to") : crtTo;
        }
        out.put("crtDtRange", Map.of("from", crtFrom, "to", crtTo));
        out.put("windows", windows(p, slots));
        Map<String, Object> expected = new LinkedHashMap<>();
        for (FailureScenario s : FailureScenario.values()) {
            expected.put(s.name(), Map.of("voice", s.expectedResume().name(), "image", FailureScenario.IMAGE_RERUN,
                    "voiceStep", s.voiceStep(), "imageStage", s.imageStage().name()));
        }
        out.put("expectedResume", expected);
        out.put("types", types);
        int total = p.count() * p.types().size();
        int fail = p.failTotal() * p.types().size();
        out.put("totals", Map.of("rows", total, "normal", total - fail, "fail", fail, "files", written));
        out.put("encrypted", enc != null);
        out.put("howToRun", howToRun(p));
        sql.into(out);
        log.info("[Dummy] 생성 — {} · {} · 대상일 {} · 유형별 {}건(장애 {}) · 파일 {}개 · {}", p.target(), p.types(), p.date(),
                p.count(), p.failures(), written, db.label());
        return out;
    }

    private Plan plan(GenerateRequest req) {
        if (req == null) {
            req = new GenerateRequest(null, null, null, null, null, null);
        }
        DummyTarget target = req.target() == null ? DummyTarget.SIMULATOR : req.target();
        if (target == DummyTarget.DASHBOARD && !mockProps.dashboard().enabled()) {
            throw new DashboardDisabledException("대시보드 더미 생성이 꺼져 있습니다 — unstructured.mock.dashboard.enabled=false");
        }
        List<DummyDataType> types = (req.dataTypes() == null || req.dataTypes().isEmpty())
                ? ALL_TYPES : req.dataTypes().stream().distinct().toList();
        LocalDate today = LocalDate.now();
        LocalDate date = req.targetDate() == null ? today.minusDays(1) : req.targetDate();
        if (date.isAfter(today)) {
            throw new IllegalArgumentException("대상일은 오늘 이전이어야 합니다: " + date);
        }
        int count = req.count() == null ? DEFAULT_COUNT : req.count();
        if (count < 1 || count > MAX_TOTAL) {
            throw new IllegalArgumentException("건수는 유형별 1~" + MAX_TOTAL + " 입니다: " + count);
        }
        if (count * types.size() > MAX_TOTAL) {
            throw new IllegalArgumentException("한 번에 만드는 건수는 유형 합계 " + MAX_TOTAL + " 이하입니다 — " + count + " × "
                    + types.size() + "유형 = " + count * types.size());
        }
        Map<FailureScenario, Integer> failures = new EnumMap<>(FailureScenario.class);
        if (req.failures() != null) {
            req.failures().forEach((s, n) -> {
                if (s == null) {
                    return;
                }
                int v = n == null ? 0 : n;
                if (v < 0) {
                    throw new IllegalArgumentException("장애 건수는 음수일 수 없습니다: " + s + "=" + v);
                }
                if (v > 0) {
                    failures.put(s, v);
                }
            });
        }
        int failTotal = failures.values().stream().mapToInt(Integer::intValue).sum();
        if (failTotal > count) {
            throw new IllegalArgumentException("장애 건수 합(" + failTotal + ")이 유형별 건수(" + count + ")보다 많습니다");
        }
        return new Plan(target, types, date, count, failures, req.append() == null || req.append());
    }

    // ── 유형별 ───────────────────────────────────────────────────────────

    /** 접견 — 특이수용자 → 녹취파일내역 → 공통파일기본 → XVARM 1:1:1:1 + XVARM 원본 더미(접견 원본 스토리지). */
    private Map<String, Object> meet(Plan p, List<FailureScenario> order, Slots slots, MediaDecryptor enc, SqlLog sql,
                                     List<FileJob> jobs) {
        DummyTarget t = p.target();
        LocalDate d = p.date();
        int seq0 = nextSeq(sql, tables.rerdTfinDs(), "TARE_FILE_NO", DummyKeys.meetKeyHead(t, d),
                t.prefix() + DummyDataType.MEET.letter() + DummyKeys.ymd8(d));
        checkSeq(seq0, p.count());
        Path meetDir = dirs.xvarmOriginalDir(VoiceKind.MEET);
        String encYn = enc != null ? props.source().flag().encrypted() : "N";
        List<Object[]> inmates = new ArrayList<>();
        List<Object[]> cmfi = new ArrayList<>();
        List<Object[]> xvarm = new ArrayList<>();
        List<Object[]> rerd = new ArrayList<>();
        Collector c = new Collector(order);
        for (int i = 0; i < p.count(); i++) {
            FailureScenario s = order.get(i);
            int seq = seq0 + i;
            LocalDateTime at = slots.at(i, seq);
            Timestamp crt = Timestamp.valueOf(at);
            String corr = DummyKeys.voiceCorrNo(t, DummyDataType.MEET, d, seq);
            String key = DummyKeys.meetKey(t, d, seq, s);
            String fileNm = DummyKeys.meetFileName(t, d, seq, s);
            Path file = meetDir.resolve(fileNm);
            String cmfiId = DummyKeys.cmfiId(t, d, seq);
            String doc = DummyKeys.docId(t, d, seq);
            inmates.add(inmateRow(corr, i, crt, t));
            cmfi.add(new Object[] {cmfiId, doc, fileNm, "01", "A", crt, "Y", encYn, flagNotDeleted(), crt, t.usr(), crt, t.usr()});
            xvarm.add(new Object[] {doc, slash(file)});
            rerd.add(new Object[] {key, "CI00001", "01", DummyKeys.ymd8(at.toLocalDate()), seq, corr, DummyKeys.ymd8(at.toLocalDate()),
                    DummyKeys.recordFileId(t, d, seq), cmfiId, fileNm, hms(at), hms(at.plusMinutes(10)), "1024",
                    SimulationDataService.fitPath(slash(meetDir), SimulationDataService.TARE_FLPTH_MAX),
                    flagNotDeleted(), flagNotDeleted(), flagNotDeleted(), crt, t.usr(), crt, t.usr()});
            jobs.add(new FileJob(DummyDataType.MEET, () -> writeFile(file, SilentWav.of(dataset.wavSeconds()), enc)));
            c.add(key, s, at);
        }
        insertInmates(sql, inmates);
        sql.batch(jdbc, "INSERT INTO " + tables.smsmCmfiBs()
                + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", cmfi);
        sql.batch(jdbc, "INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)", xvarm);
        sql.batch(jdbc, "INSERT INTO " + tables.rerdTfinDs()
                + " (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,"
                + "  TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS, TARE_FILE_MG_VL, TARE_FLPTH_NM,"
                + "  DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rerd);
        Map<String, Object> out = c.result(DummyDataType.MEET, seq0, p.count());
        out.put("rows", Map.of("inmates", inmates.size(), "meet", rerd.size(), "cmfi", cmfi.size(), "xvarm", xvarm.size()));
        out.put("originalDir", slash(meetDir));
        return out;
    }

    /** 전화 — 특이수용자 → 사용자통화내역 1:1 + 원본 더미(전화 Mock 제공자는 수신 폴더에 따로 만든다 — 앞뒤 맞춤용). */
    private Map<String, Object> phone(Plan p, List<FailureScenario> order, Slots slots, MediaDecryptor enc, SqlLog sql,
                                      List<FileJob> jobs) {
        DummyTarget t = p.target();
        LocalDate d = p.date();
        int seq0 = nextSeq(sql, tables.imphUcdrDs(), "VRFC_ESTL_ID", DummyKeys.phoneKeyHead(t, d),
                t.prefix() + DummyDataType.PHONE.letter() + DummyKeys.ymd8(d));
        checkSeq(seq0, p.count());
        Path phoneDir = dirs.xvarmOriginalDir(VoiceKind.PHONE);
        VoiceProperties.Flag flag = props.source().flag();
        List<Object[]> inmates = new ArrayList<>();
        List<Object[]> calls = new ArrayList<>();
        Collector c = new Collector(order);
        for (int i = 0; i < p.count(); i++) {
            FailureScenario s = order.get(i);
            int seq = seq0 + i;
            LocalDateTime at = slots.at(i, seq);
            Timestamp crt = Timestamp.valueOf(at);
            String corr = DummyKeys.voiceCorrNo(t, DummyDataType.PHONE, d, seq);
            String key = DummyKeys.phoneKey(t, d, seq, s);
            String fileNm = DummyKeys.phoneFileName(t, d, seq, s);
            Path file = phoneDir.resolve(fileNm);
            inmates.add(inmateRow(corr, i, crt, t));
            // 개인정보 컬럼(수신자명 · 관계 · 전화번호)에는 실제 같은 값을 넣지 않는다
            calls.add(new Object[] {key, "P001", "01", corr, "L001", "(더미)수신자", "(더미)관계", "000-0000-0000",
                    dt14(at), dt14(at.plusSeconds(5)), dt14(at.plusMinutes(3)), 180, 5, "Y",
                    0, flag.recordedYes(), flag.ptcrYes(), 1, "CI00001", "(더미)전화실",
                    slash(phoneDir), fileNm, DummyKeys.phoneFileKey(t, d, seq), null, null,
                    crt, t.usr(), crt, t.usr()});
            jobs.add(new FileJob(DummyDataType.PHONE, () -> writeFile(file, SilentWav.of(dataset.wavSeconds()), enc)));
            c.add(key, s, at);
        }
        insertInmates(sql, inmates);
        sql.batch(jdbc, "INSERT INTO " + tables.imphUcdrDs()
                + " (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD, RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO,"
                + "  TELP_PCALL_BGNG_DT, TELP_PCALL_RSPNS_DT, TELP_PCALL_END_DT, TELP_PCALL_TIME, TELP_RSPNS_TIME, TELP_PCALL_RSPNS_YN,"
                + "  TELP_PCALL_OCRN_AMT, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT, CORR_INSTT_CD, TELP_USE_PLACE_NM,"
                + "  TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM, TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", calls);
        Map<String, Object> out = c.result(DummyDataType.PHONE, seq0, p.count());
        out.put("rows", Map.of("inmates", inmates.size(), "phone", calls.size()));
        out.put("originalDir", slash(phoneDir));
        return out;
    }

    /**
     * 이미지 — 수용자마다 사진 2장(순번 1 · 2)과 사진 아닌 이미지 1장(구분 '2', 순번 3). 파이프라인은 순번 2 를 골라야 한다
     * (6번 탭 시딩과 같은 모양). 최신 사진(순번 2)만 원본 파일을 둔다.
     */
    private Map<String, Object> image(Plan p, List<FailureScenario> order, Slots slots, MediaDecryptor enc, SqlLog sql,
                                      List<FileJob> jobs) {
        DummyTarget t = p.target();
        LocalDate d = p.date();
        int seq0 = nextSeq(sql, tables.irimBsifDs(), "CORR_NO", DummyKeys.imageCorrHead(t, d), null);
        checkSeq(seq0, p.count());
        Path dir = imageSim.originalDir();
        String encYn = enc != null ? props.source().flag().encrypted() : "N";
        List<Object[]> images = new ArrayList<>();
        List<Object[]> cmfi = new ArrayList<>();
        List<Object[]> xvarm = new ArrayList<>();
        Collector c = new Collector(order);
        for (int i = 0; i < p.count(); i++) {
            FailureScenario s = order.get(i);
            int seq = seq0 + i;
            LocalDateTime at = slots.at(i, seq);
            Timestamp crt = Timestamp.valueOf(at);
            String corr = DummyKeys.imageCorrNo(t, d, seq, s);
            for (int sn = 1; sn <= 3; sn++) {
                String fid = DummyKeys.imageFileId(t, d, seq, sn);
                images.add(new Object[] {corr, sn, sn == 3 ? "2" : "1", fid, crt, t.usr(), crt, t.usr()});
                if (sn <= 2) {
                    String doc = DummyKeys.imageDocId(t, d, seq, sn);
                    Path file = dir.resolve(corr + "_" + sn + ".jpg.enc");
                    cmfi.add(new Object[] {fid, doc, corr + "_" + sn + ".jpg", "01", "A", crt, "Y", encYn, flagNotDeleted(),
                            crt, t.usr(), crt, t.usr()});
                    xvarm.add(new Object[] {doc, slash(file)});
                    if (sn == 2) {
                        jobs.add(new FileJob(DummyDataType.IMAGE, () -> writeFile(file, SampleImage.jpeg(corr), enc)));
                    }
                }
            }
            c.add(corr, s, at);
        }
        sql.batch(jdbc, "INSERT INTO " + tables.irimBsifDs()
                + " (CORR_NO, IMAGE_SN, IMAGE_SE_CD, IMAGE_CMMN_FILE_ID, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                + " VALUES (?,?,?,?,?,?,?,?)", images);
        sql.batch(jdbc, "INSERT INTO " + tables.smsmCmfiBs()
                + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", cmfi);
        sql.batch(jdbc, "INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)", xvarm);
        Map<String, Object> out = c.result(DummyDataType.IMAGE, seq0, p.count());
        out.put("rows", Map.of("inmates", p.count(), "image", images.size(), "cmfi", cmfi.size(), "xvarm", xvarm.size()));
        out.put("originalDir", slash(dir));
        out.put("corrNoPrefix", t.imagePrefix());
        out.put("note", t == DummyTarget.SIMULATOR
                ? "SIMIMG… 는 실제 이미지 수집이 대상에서 뺀다 — 6번 탭 또는 corrNoPrefix=SIMIMG 로 돌린다"
                : "DMYIMG… 는 실제 이미지 수집이 집는다 — include-image=true 면 스케줄·바로 실행에, 아니면 POST /api/v1/image/batches");
        return out;
    }

    /** 특이수용자 행 — 지정 상태(해제일 없음) · 대상 코드는 설정된 코드를 돌려 가며. 실제 수용자가 아니라 가상 교정번호다. */
    private Object[] inmateRow(String corr, int i, Timestamp crt, DummyTarget t) {
        List<String> codes = props.batch().speclMngSeCd();
        String code = codes == null || codes.isEmpty() ? "1" : codes.get(i % codes.size()).trim();
        return new Object[] {corr, 1, code, "A01", DummyKeys.ymd8(crt.toLocalDateTime().toLocalDate()), null, crt, t.usr(), crt, t.usr()};
    }

    private void insertInmates(SqlLog sql, List<Object[]> rows) {
        sql.batch(jdbc, "INSERT INTO " + tables.imscPtprDt()
                + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)", rows);
    }

    /**
     * 다음 순번 — 같은 용도 · 유형 · 대상일의 키(와 가상 교정번호) 중 가장 큰 순번 + 1. 추가(append)해도 키가 겹치지 않는다.
     *
     * @param corrHead 음성 가상 교정번호 앞부분 — 키를 지운 뒤 수용자만 남은 경우까지 피한다. 이미지는 키가 곧 교정번호라 null
     */
    private int nextSeq(SqlLog sql, String table, String keyCol, String keyHead, String corrHead) {
        int max = 0;
        String q = "SELECT " + keyCol + " FROM " + table + " WHERE " + keyCol + " LIKE ?";
        sql.add(q, keyHead + "%");
        for (String k : jdbc.queryForList(q, String.class, keyHead + "%")) {
            max = Math.max(max, DummyKeys.seqOf(k));
        }
        if (corrHead != null) {
            String qc = "SELECT CORR_NO FROM " + tables.imscPtprDt() + " WHERE CORR_NO LIKE ?";
            sql.add(qc, corrHead + "%");
            for (String corr : jdbc.queryForList(qc, String.class, corrHead + "%")) {
                String tail = corr.trim().substring(Math.max(0, corr.trim().length() - 4));
                if (tail.chars().allMatch(Character::isDigit)) {
                    max = Math.max(max, Integer.parseInt(tail));
                }
            }
        }
        return max + 1;
    }

    private static void checkSeq(int seq0, int count) {
        if (seq0 + count - 1 > DummyKeys.MAX_SEQ) {
            throw new IllegalArgumentException("이 날짜의 순번이 상한(" + DummyKeys.MAX_SEQ + ")을 넘습니다 — 지금 " + (seq0 - 1)
                    + "번까지 있습니다. 다른 날짜를 쓰거나 먼저 초기화하십시오");
        }
    }

    /** 건별 결과를 모은다 — 키 목록 · 시나리오별 키 · CRT_DT 범위. */
    private static final class Collector {
        final List<FailureScenario> order;
        final List<String> keys = new ArrayList<>();
        final Map<FailureScenario, List<String>> byScenario = new EnumMap<>(FailureScenario.class);
        LocalDateTime min;
        LocalDateTime max;

        Collector(List<FailureScenario> order) {
            this.order = order;
        }

        void add(String key, FailureScenario s, LocalDateTime at) {
            keys.add(key);
            if (s != null) {
                byScenario.computeIfAbsent(s, k -> new ArrayList<>()).add(key);
            }
            min = min == null || at.isBefore(min) ? at : min;
            max = max == null || at.isAfter(max) ? at : max;
        }

        Map<String, Object> result(DummyDataType type, int seq0, int count) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type.name());
            m.put("label", type.label());
            m.put("count", count);
            int fail = byScenario.values().stream().mapToInt(List::size).sum();
            m.put("normal", count - fail);
            m.put("fail", fail);
            m.put("seqFrom", seq0);
            m.put("seqTo", seq0 + count - 1);
            m.put("crtDt", Map.of("from", TS.format(min), "to", TS.format(max)));
            Map<String, Object> f = new LinkedHashMap<>();
            byScenario.forEach((s, ks) -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("count", ks.size());
                one.put("failsAt", type == DummyDataType.IMAGE ? s.imageStage().name() : s.voiceStep());
                one.put("expectedResume", type == DummyDataType.IMAGE ? FailureScenario.IMAGE_RERUN : s.expectedResume().name());
                one.put("keys", ks);
                f.put(s.name(), one);
            });
            m.put("failures", f);
            m.put("keys", keys);
            return m;
        }
    }

    /** 시나리오마다 CRT_DT 구간 — [from, to). 유형마다 건수 · 순서가 같아 유형을 가로질러 같은 구간이다. */
    private static Map<String, Object> windows(Plan p, Slots slots) {
        Map<String, Object> w = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<FailureScenario, Integer> e : p.failures().entrySet()) {
            FailureScenario s = e.getKey();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("from", TS.format(slots.from(idx)));
            one.put("to", TS.format(slots.from(idx + e.getValue())));
            one.put("count", e.getValue());
            one.put("voiceStep", s.voiceStep());
            one.put("expectedResume", s.expectedResume().name());
            one.put("imageStage", s.imageStage().name());
            w.put(s.name(), one);
            idx += e.getValue();
        }
        if (idx < p.count()) {
            w.put("NORMAL", Map.of("from", TS.format(slots.from(idx)), "to", TS.format(slots.from(p.count())),
                    "count", p.count() - idx));
        }
        return w;
    }

    /**
     * 대상일 안의 시각 칸 — 건수만큼 고르게 나눈 칸마다 한 건. 같은 날짜로 추가하면 순번으로 칸 안에서 조금씩 비켜 놓는다.
     * 대상일이 오늘이면 지금-1분까지만 쓴다(미래 시각을 만들지 않는다).
     */
    record Slots(LocalDateTime start, long span, long slot) {

        static Slots of(LocalDate d, int count, LocalDateTime now) {
            LocalDateTime start = d.atStartOfDay();
            LocalDateTime end = d.equals(now.toLocalDate()) ? now.minusMinutes(1) : start.plusDays(1).minusSeconds(1);
            long span = Math.max(1L, Duration.between(start, end).getSeconds() + 1);
            return new Slots(start, span, Math.max(1L, span / Math.max(1, count)));
        }

        /** i 번째 칸의 시각 — 칸 안에서 순번으로 비켜 놓는다. */
        LocalDateTime at(int i, int seq) {
            long jitter = slot <= 1 ? 0 : Math.floorMod(seq * 37L, slot);
            return start.plusSeconds(Math.min(span - 1, i * slot + jitter));
        }

        /** i 번째 칸의 시작(구간 경계). 칸을 다 쓰면 대상일 끝. */
        LocalDateTime from(int i) {
            return start.plusSeconds(Math.min(span, i * slot));
        }
    }

    private List<String> howToRun(Plan p) {
        String d0 = p.date().atStartOfDay().format(TS);
        String d1 = p.date().plusDays(1).atStartOfDay().format(TS);
        boolean sim = p.target() == DummyTarget.SIMULATOR;
        List<String> h = new ArrayList<>();
        if (sim) {
            h.add("음성 1차 실행(시험): POST /api/v1/voice/batches/manual?from=" + d0 + "&to=" + d1 + "&test=true"
                    + " — 대상일이 어제면 시뮬레이터 2번 탭 [전체 실행 (일배치)] 와 같다");
            h.add("음성 재처리(시험): POST /api/v1/voice/batches/resume?resume=<기대 모드>&from=<windows.X.from>&to=<windows.X.to>&test=true");
            h.add("이미지: POST /api/v1/image/batches {\"corrNoPrefix\":\"SIMIMG\"} — 다시 돌리면 실패했던 건만 다시 처리(성공 건은 '변경 없음')");
        } else {
            h.add("음성: 실제 배치가 읽는다 — 일배치 스케줄(대상일=어제) · POST /internal/batch/run · 또는 POST /api/v1/voice/batches/manual?from="
                    + d0 + "&to=" + d1 + "&test=false");
            h.add("음성 재처리: POST /internal/batch/reprocess {\"execId\":…, \"stepTypeCd\":\"COLLECT|ANALYZE|SEND\"} · 또는 "
                    + "POST /api/v1/voice/batches/resume?resume=<기대 모드>&from=<windows.X.from>&to=<windows.X.to>&test=false");
            h.add("이미지: unstructured.batch.include-image=true 면 스케줄·바로 실행에 포함 · 아니면 POST /api/v1/image/batches {\"corrNoPrefix\":\"DMYIMG\"}");
            h.add("시뮬레이터 시험 실행(test=true)은 DMY 를 집지 않는다 — 실제 배치 몫");
        }
        h.add("장애 표식(CF·AF·SF) 건은 그 단계에서 한 번만 실패하고 재처리는 통과한다(소진 표시는 메모리 — 재기동하면 한 번 더 실패)");
        return h;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  현황
    // ══════════════════════════════════════════════════════════════════════

    /** 용도별 행 수 · 파일 수 · 키 표식 장애 소진 현황. 테이블이 없으면(개발계 MOCK_DEV 첫 실행) 사유를 싣는다. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("dashboardEnabled", mockProps.dashboard().enabled());
        out.put("daily", mockProps.daily());
        for (DummyTarget t : DummyTarget.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            String px = t.prefix();
            boolean dmy = t == DummyTarget.DASHBOARD;
            String usr = dmy ? " AND CRT_USR_ID = '" + t.usr() + "'" : "";
            m.put("meet", count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE '" + px + "-MEET-%'" + usr));
            m.put("phone", count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE '" + px + "-PHONE-%'" + usr));
            m.put("inmates", count(tables.imscPtprDt(), "CORR_NO LIKE '" + px + "%'" + usr));
            m.put("imageInmates", count(tables.irimBsifDs(), "IMAGE_SN = 2 AND CORR_NO LIKE '" + t.imagePrefix() + "%'" + usr));
            m.put("cmfi", count(tables.smsmCmfiBs(), "(CMMN_FILE_ID LIKE '" + px + "CMFI%' OR CMMN_FILE_ID LIKE '" + t.imagePrefix()
                    + "F%')" + usr));
            try {
                m.put("photoMapped", photos.stats(t.imagePrefix()).get("sim"));
            } catch (Exception e) {
                m.put("photoMapped", "skip: " + rootMessage(e));
            }
            m.put("note", dmy ? "DMY 접두 + 생성자 dmyadm" : "SIM 접두 전체(시연 기본 Clean & Seed · 성능 시험 데이터 포함)");
            out.put(t.name(), m);
        }
        out.put("scenarioFaults", scenarioFaults.snapshot());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  초기화
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 대시보드 더미를 전부 지운다 — <b>DMY 접두 + 생성자 dmyadm</b> 조건을 같이 건다(실데이터 오삭제 방지).
     *
     * <p>로그 컬렉터 수행 이력(T1~T5)은 지우지 않는다 — 다른 서비스 소유다. 시뮬레이터 데이터(SIM)는 건드리지 않는다.</p>
     */
    public Map<String, Object> cleanDashboard() {
        return deleteDashboard(null);
    }

    /**
     * 보존 기간 정리 — 대상일이 {@code before} 보다 앞선 대시보드 더미만 지운다(행은 {@code CRT_DT < before}, 파일은 이름의 날짜).
     */
    public Map<String, Object> purgeDashboardBefore(LocalDate before) {
        return deleteDashboard(before);
    }

    private synchronized Map<String, Object> deleteDashboard(LocalDate before) {
        DummyTarget t = DummyTarget.DASHBOARD;
        SqlLog sql = new SqlLog();
        // 개발계 MOCK_DEV — 공통파일·XVARM 테이블이 아직 없으면 만든다. PostgreSQL 은 트랜잭션 안의 한 문장이 실패하면
        //   나머지가 전부 거절되므로, 없는 테이블에 DELETE 를 보내지 않게 먼저 맞춘다
        String ensured = voiceSim.ensureXvarmMockTables();
        Map<String, Object> rows = new LinkedHashMap<>();
        LocalDateTime cut = before == null ? null : before.atStartOfDay();
        String cond = "CRT_USR_ID = ?" + (cut == null ? "" : " AND CRT_DT < ?");
        Object[] args = cut == null ? new Object[] {t.usr()} : new Object[] {t.usr(), Timestamp.valueOf(cut)};
        String px = t.prefix();
        String ipx = t.imagePrefix();
        // 사진 매핑(Admin DB)은 다른 DB 라 보라미 행을 지우기 전에 대상 교정번호를 먼저 읽어 둔다
        List<String> imageCorrs = new ArrayList<>();
        try {
            imageCorrs.addAll(jdbc.queryForList("SELECT DISTINCT CORR_NO FROM " + tables.irimBsifDs()
                    + " WHERE CORR_NO LIKE ? AND " + cond, String.class, concat(ipx + "%", args)));
        } catch (Exception e) {
            log.debug("[Dummy] 이미지 교정번호 조회 건너뜀 — {}", rootMessage(e));
        }
        String cmfiCond = "(CMMN_FILE_ID LIKE ? OR CMMN_FILE_ID LIKE ?) AND " + cond;
        Object[] cmfiArgs = concat(new Object[] {px + "CMFI%", ipx + "F%"}, args);
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            // 자식(XVARM · 공통파일 · 녹취 · 통화 · 사진) → 부모(특이수용자). XVARM 은 생성자 컬럼이 없어 우리 공통파일의 문서ID 로 한정한다
            rows.put("xvarm", safeDelete(sql, tables.asysContentElement(),
                    "(ELEMENTID LIKE ? OR ELEMENTID LIKE ?) AND ELEMENTID IN (SELECT DOC_ID FROM " + tables.smsmCmfiBs()
                            + " WHERE " + cmfiCond + ")",
                    concat(new Object[] {px + "DOC%", ipx + "D%"}, cmfiArgs)));
            rows.put("cmfi", safeDelete(sql, tables.smsmCmfiBs(), cmfiCond, cmfiArgs));
            rows.put("meet", safeDelete(sql, tables.rerdTfinDs(), "TARE_FILE_NO LIKE ? AND " + cond, concat(px + "-MEET-%", args)));
            rows.put("phone", safeDelete(sql, tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE ? AND " + cond, concat(px + "-PHONE-%", args)));
            rows.put("image", safeDelete(sql, tables.irimBsifDs(), "CORR_NO LIKE ? AND " + cond, concat(ipx + "%", args)));
            rows.put("inmates", safeDelete(sql, tables.imscPtprDt(), "CORR_NO LIKE ? AND " + cond, concat(px + "%", args)));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", t.name());
        out.put("crtUsrId", t.usr());
        out.put("ensured", ensured);
        if (before != null) {
            out.put("before", before.toString());
        }
        out.put("rows", rows);
        // ── Admin DB 사진 매핑 — 생성자 컬럼이 없어 접두로. 보존 기간 정리면 지운 교정번호만 ──
        Object photoRows;
        try {
            photoRows = before == null ? photos.deleteByPrefix(ipx)
                    : imageCorrs.stream().mapToInt(photos::deleteByPrefix).sum();
        } catch (Exception e) {
            photoRows = "skip: " + rootMessage(e);   // Admin DB 에 매핑 테이블이 아직 없을 수 있다
        }
        out.put("photoRows", photoRows);

        // ── 파일 — 더미 원본 · 저장 사진 · 로컬 산출물(멱등 표식 · 보존물 · 수신 잔재) ──
        Predicate<String> dated = name -> before == null
                || DummyKeys.dashboardDateOf(name).map(dd -> dd.isBefore(before)).orElse(false);
        Map<String, Integer> files = new LinkedHashMap<>();
        files.put("meetOriginal", deleteFiles(dirs.xvarmOriginalDir(VoiceKind.MEET), n -> n.startsWith(t.filePrefix() + "meet_") && dated.test(n)));
        files.put("phoneOriginal", deleteFiles(dirs.xvarmOriginalDir(VoiceKind.PHONE), n -> n.startsWith(t.filePrefix() + "phone_") && dated.test(n)));
        files.put("imageOriginal", deleteFiles(imageSim.originalDir(), n -> n.startsWith(ipx) && dated.test(n)));
        int saved = 0;
        Path root = store.outputRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                for (Path dir : s.filter(Files::isDirectory)
                        .filter(dd -> dd.getFileName().toString().startsWith(ipx) && dated.test(dd.getFileName().toString())).toList()) {
                    saved += store.deleteCorrDir(dir.getFileName().toString());
                }
            } catch (IOException e) {
                log.warn("[Dummy] 저장 사진 정리 실패 — {} ({})", root, e.getMessage());
            }
        }
        files.put("savedPhotos", saved);
        out.put("files", files);
        out.put("local", local.clear(name -> DummyTarget.isDashboardLocalName(name) && dated.test(name)));
        out.put("scenarioFaultsForgotten", scenarioFaults.forget(k -> t.ownsScenarioKey(k) && dated.test(k)));
        out.put("counts", Map.of(
                "dbRows", rows.values().stream().filter(Integer.class::isInstance).mapToInt(v -> (Integer) v).sum(),
                "files", files.values().stream().mapToInt(Integer::intValue).sum()));
        out.put("historyKept", "로그 컬렉터 수행 이력(T1~T5)은 지우지 않았습니다 — 다른 서비스 소유라 남아 있습니다");
        out.put("message", (before == null ? "대시보드 테스트 데이터 초기화 완료" : "대시보드 더미 보존 기간 정리 완료(" + before + " 이전)")
                + " — DMY 행·Admin 사진 매핑·더미 파일·DMY 멱등 표식을 지웠습니다(시뮬레이터 SIM 은 그대로)");
        sql.into(out);
        log.info("[Dummy] 대시보드 더미 정리{} — 행 {} · 파일 {} · 사진 매핑 {}", before == null ? "" : "(" + before + " 이전)",
                rows, files, photoRows);
        return out;
    }

    /** 시뮬레이터 데이터 전체(append=false 재생성용) — 기존 시뮬레이션 정리 + 이미지 SIM 정리 + 로컬(대시보드 더미의 것은 남김). */
    private Map<String, Object> cleanSimulator() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("local", local.clearExceptDashboard());
        out.put("sim", voiceSim.clean().get("counts"));
        out.put("imageSim", imageSim.clean().get("counts"));
        out.put("scenarioFaultsForgotten", scenarioFaults.forget(DummyTarget.SIMULATOR::ownsScenarioKey));
        return out;
    }

    private Object safeDelete(SqlLog sql, String table, String where, Object[] args) {
        String q = "DELETE FROM " + table + " WHERE " + where;
        try {
            sql.add(q, args);
            return jdbc.update(q, args);
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("[Dummy] 삭제 건너뜀 — {} ({})", table, msg);
            return "skip: " + msg;   // 테이블이 아직 없다(개발계 MOCK_DEV 첫 실행 · 이미지 원장 미구축)
        }
    }

    private static int deleteFiles(Path dir, Predicate<String> name) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).filter(f -> name.test(f.getFileName().toString())).toList()) {
                if (Files.deleteIfExists(f)) {
                    n++;
                }
            }
        } catch (IOException e) {
            log.warn("[Dummy] 더미 파일 정리 실패 — {} ({})", dir, e.getMessage());
        }
        return n;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  파일 · 내부
    // ══════════════════════════════════════════════════════════════════════

    /** 복호화가 REAL 이고 키를 읽을 수 있으면 암호화기 — 아니면 null(평문 · 메타도 암호화 'N' 으로 맞춘다). */
    private MediaDecryptor encryptor() {
        if (modeState.decrypt() != VoiceProperties.DecryptMode.REAL) {
            return null;
        }
        String keyPath = props.decrypt().rvsKeyPath();
        if (!StringUtils.hasText(keyPath)) {
            log.warn("[Dummy] 복호화 REAL 인데 키 경로가 없어 평문으로 둡니다 — voice.decrypt.rvs-key-path");
            return null;
        }
        try {
            return MediaDecryptor.fromKeyFile(Path.of(keyPath.trim()));
        } catch (Exception e) {
            log.warn("[Dummy] 키를 읽지 못해 평문으로 둡니다 — {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> writeFile(Path file, byte[] plain, MediaDecryptor enc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", slash(file));
        try {
            byte[] body = enc == null ? plain : enc.encrypt(plain);
            Files.createDirectories(file.getParent());
            Files.write(file, body);
            m.put("bytes", body.length);
            m.put("encrypted", enc != null);
            m.put("written", true);
        } catch (Exception e) {
            m.put("written", false);
            m.put("error", e.getMessage());
            log.warn("[Dummy] 더미 파일 생성 실패 — {} ({})", file, e.getMessage());
        }
        return m;
    }

    private String flagNotDeleted() {
        return props.source().flag().notDeleted();
    }

    private Object count(String table, String where) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return "skip: " + rootMessage(e);
        }
    }

    private static Object[] concat(Object first, Object[] rest) {
        return concat(new Object[] {first}, rest);
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String hms(LocalDateTime t) {
        return "%02d%02d%02d".formatted(t.getHour(), t.getMinute(), t.getSecond());
    }

    private static String dt14(LocalDateTime t) {
        return DummyKeys.ymd8(t.toLocalDate()) + hms(t);
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String s = cur.getMessage();
        return s == null ? cur.getClass().getSimpleName() : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 실행한 SQL — 화면 표시와 <b>어느 테이블을 건드렸는지</b>의 근거. 같은 (동사, 테이블)은 대표 1건만 값까지 채워 싣는다
     * (표시 전용 — 실행은 끝까지 바인딩이다). 건드린 테이블 목록({@code sqlTables})은 빠짐없이 싣는다.
     */
    static final class SqlLog {

        private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:INSERT\\s+INTO|DELETE\\s+FROM|FROM|UPDATE|JOIN)\\s+([A-Za-z0-9_.\"]+)");
        private static final Pattern GROUP = Pattern.compile("(?i)^(INSERT\\s+INTO|DELETE\\s+FROM|SELECT\\s+\\S+\\s+FROM|UPDATE)\\s*([A-Za-z0-9_.\"]*)");
        private final Map<String, String> samples = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Set<String> tables = new LinkedHashSet<>();
        private int total;

        void add(String sql, Object... args) {
            String one = sql.replaceAll("\\s+", " ").trim();
            total++;
            Matcher t = TABLE.matcher(one);
            while (t.find()) {
                tables.add(t.group(1));
            }
            Matcher g = GROUP.matcher(one);
            String key = g.find() ? g.group(1).replaceAll("\\s+", " ").toUpperCase() + " " + g.group(2) : one;
            samples.computeIfAbsent(key, k -> render(one, args));
            counts.merge(key, 1, Integer::sum);
        }

        void batch(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
            if (rows.isEmpty()) {
                return;
            }
            for (Object[] r : rows) {
                add(sql, r);
            }
            jdbc.batchUpdate(sql, rows);
        }

        /** 건드린 테이블 — 테스트가 수용자기본·신상 테이블이 없음을 이것으로 본다. */
        Set<String> tables() {
            return tables;
        }

        void into(Map<String, Object> out) {
            List<String> lines = new ArrayList<>();
            samples.forEach((k, s) -> {
                lines.add(s);
                int n = counts.get(k);
                if (n > 1) {
                    lines.add("-- (같은 형태 %d건 중 1건 표시)".formatted(n));
                }
            });
            out.put("sqls", lines);
            out.put("sqlTotal", total);
            out.put("sqlTables", List.copyOf(tables));
        }

        private static String render(String one, Object... args) {
            StringBuilder sb = new StringBuilder(one.length() + 64);
            int ai = 0;
            for (int i = 0; i < one.length(); i++) {
                char c = one.charAt(i);
                if (c == '?' && args != null && ai < args.length) {
                    Object v = args[ai++];
                    if (v == null) {
                        sb.append("NULL");
                    } else if (v instanceof Number) {
                        sb.append(v);
                    } else if (v instanceof Timestamp ts) {
                        sb.append("TIMESTAMP '").append(ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\'');
                    } else {
                        sb.append('\'').append(String.valueOf(v).replace("'", "''")).append('\'');
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.append(';').toString();
        }
    }
}
