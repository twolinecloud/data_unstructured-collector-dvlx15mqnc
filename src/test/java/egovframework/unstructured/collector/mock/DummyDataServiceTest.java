package egovframework.unstructured.collector.mock;

import egovframework.unstructured.collector.common.config.BoramiTableNames;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.ProcStatus;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.model.ImageOutcome;
import egovframework.unstructured.collector.image.model.ImageStage;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.controller.VoiceMockController;
import egovframework.unstructured.collector.voice.stt.SttTempStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 더미 데이터 — 용도별 생성 · 날짜 분산 · 추가(append) 키 유일성 · 장애 시나리오별 실패 단계와 기대 재처리 · 용도별 초기화 격리 ·
 * 수용자기본/신상 테이블 미사용. 로컬 H2(보라미 · Admin) · 브로커/전화/STT/제논 MOCK · 복호화 REAL(테스트 키) — 개발계와 같은 단계 구성.
 *
 * <p>외부 주소는 여기서 못 박는다 — Jenkins 는 dev 프로파일로 테스트를 돌려 개발계 주소를 읽는다.</p>
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-dummy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.phone.mode=MOCK",
        "voice.stt.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "zenon.mode=MOCK",
        "log-collector.enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20",
        "voice.batch.max-files-per-run=2000",
        "unstructured.mock.dashboard.enabled=true",
        "unstructured.mock.daily.enabled=false"
})
class DummyDataServiceTest {

    private static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("dummydata");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    @Autowired
    private DummyDataService dummy;
    @Autowired
    private VoiceMockController voiceMock;
    @Autowired
    private VoiceCollectService voice;
    @Autowired
    private ImageCollectService image;
    @Autowired
    private InmatePhotoRepository photos;
    @Autowired
    private ScenarioFaults scenarioFaults;
    @Autowired
    private SttTempStore sttTemp;
    @Autowired
    private VoiceDirState dirs;
    @Autowired
    private ImageSimulationService imageSim;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BoramiTableNames tables;
    @Autowired
    private MockDataProperties mockProps;

    private static final LocalDate D = LocalDate.now().minusDays(1);

    @BeforeEach
    void clean() {
        dummy.cleanDashboard();
        voiceMock.deleteSimData(DummyTarget.SIMULATOR);
        scenarioFaults.forget(k -> true);
    }

    private static DummyDataService.GenerateRequest req(DummyTarget t, List<DummyDataType> types, LocalDate d, int count,
                                                        Map<FailureScenario, Integer> failures) {
        return new DummyDataService.GenerateRequest(t, types, d, count, failures, true);
    }

    private static Map<FailureScenario, Integer> oneEach() {
        Map<FailureScenario, Integer> m = new EnumMap<>(FailureScenario.class);
        m.put(FailureScenario.COLLECT_FAIL, 1);
        m.put(FailureScenario.ANALYZE_FAIL, 1);
        m.put(FailureScenario.SEND_FAIL, 1);
        return m;
    }

    private int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    private BatchWindow day() {
        return BatchWindow.manual(D.atStartOfDay(), D.plusDays(1).atStartOfDay());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> type(Map<String, Object> g, DummyDataType t) {
        return (Map<String, Object>) ((Map<String, Object>) g.get("types")).get(t.name());
    }

    @SuppressWarnings("unchecked")
    private static List<String> keys(Map<String, Object> g, DummyDataType t) {
        return (List<String>) type(g, t).get("keys");
    }

    @SuppressWarnings("unchecked")
    private static List<String> failKeys(Map<String, Object> g, DummyDataType t, FailureScenario s) {
        Map<String, Object> f = (Map<String, Object>) ((Map<String, Object>) type(g, t).get("failures")).get(s.name());
        return (List<String>) f.get("keys");
    }

    private static Map<String, FileProcOutcome> byKey(VoiceBatchResult r) {
        return r.outcomes().stream().collect(Collectors.toMap(o -> o.target().idempotencyKey(), Function.identity(), (a, b) -> b));
    }

    // ── 용도별 접두 · 생성자 ───────────────────────────────────────────────

    @Test
    @DisplayName("용도별 — DASHBOARD 는 DMY 접두 · dmyadm, SIMULATOR 는 SIM 접두 · simadm. 더미 원본 파일도 만든다")
    void prefixesAndCreatorPerTarget() {
        Map<String, Object> g = dummy.generate(req(DummyTarget.DASHBOARD, null, D, 3, Map.of()));
        assertThat(g.get("target")).isEqualTo("DASHBOARD");
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'DMY-MEET-%' AND CRT_USR_ID = 'dmyadm'")).isEqualTo(3);
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'DMY-PHONE-%' AND CRT_USR_ID = 'dmyadm'")).isEqualTo(3);
        assertThat(count(tables.imscPtprDt(), "CORR_NO LIKE 'DMY%' AND CRT_USR_ID = 'dmyadm'")).as("접견 3 + 전화 3 가상 수용자").isEqualTo(6);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'DMYIMG%' AND CRT_USR_ID = 'dmyadm'")).as("수용자 3 × 이미지 3행").isEqualTo(9);
        assertThat(count(tables.smsmCmfiBs(), "(CMMN_FILE_ID LIKE 'DMYCMFI%' OR CMMN_FILE_ID LIKE 'DMYIMGF%') AND CRT_USR_ID = 'dmyadm'"))
                .isEqualTo(3 + 6);
        assertThat(count(tables.asysContentElement(), "ELEMENTID LIKE 'DMYDOC%' OR ELEMENTID LIKE 'DMYIMGD%'")).isEqualTo(3 + 6);
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'DMY-%' AND CRT_USR_ID <> 'dmyadm'")).isZero();
        assertThat(keys(g, DummyDataType.MEET)).allMatch(k -> k.startsWith("DMY-MEET-" + DummyKeys.ymd8(D) + "-"));
        assertThat(keys(g, DummyDataType.IMAGE)).allMatch(k -> k.startsWith("DMYIMG" + DummyKeys.ymd6(D)));
        assertThat(dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.DASHBOARD, D, 1, null))).isRegularFile();
        assertThat(imageSim.originalDir().resolve(DummyKeys.imageCorrNo(DummyTarget.DASHBOARD, D, 1, null) + "_2.jpg.enc")).isRegularFile();

        Map<String, Object> s = dummy.generate(req(DummyTarget.SIMULATOR, null, D, 2, Map.of()));
        assertThat(s.get("crtUsrId")).isEqualTo("simadm");
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-" + DummyKeys.ymd8(D) + "-%' AND CRT_USR_ID = 'simadm'")).isEqualTo(2);
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-" + DummyKeys.ymd8(D) + "-%' AND CRT_USR_ID = 'simadm'")).isEqualTo(2);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'SIMIMG%' AND CRT_USR_ID = 'simadm'")).isEqualTo(6);
        assertThat(dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.SIMULATOR, D, 1, null)))
                .as("SIM 더미 원본은 기존 정리 대상(mock_meet_*)").isRegularFile();
    }

    @Test
    @DisplayName("날짜 분산 — CRT_DT 가 대상일 안에서 하루에 고르게(서로 다른 시각)")
    void crtDtSpreadWithinTargetDate() {
        LocalDate d = D.minusDays(3);
        dummy.generate(req(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET), d, 10, Map.of()));
        List<LocalDateTime> at = jdbc.queryForList("SELECT CRT_DT FROM " + tables.rerdTfinDs() + " WHERE TARE_FILE_NO LIKE ?",
                java.sql.Timestamp.class, "DMY-MEET-" + DummyKeys.ymd8(d) + "-%").stream().map(java.sql.Timestamp::toLocalDateTime).sorted().toList();
        assertThat(at).hasSize(10).allMatch(t -> t.toLocalDate().equals(d));
        assertThat(new HashSet<>(at)).hasSize(10);
        assertThat(at.get(0)).isBefore(d.atTime(3, 0));
        assertThat(at.get(9)).isAfter(d.atTime(21, 0));
    }

    @Test
    @DisplayName("추가 2회 — 같은 날짜로 두 번 만들어도 키가 겹치지 않고 순번을 이어 받는다")
    void appendTwiceKeepsKeysUnique() {
        Map<String, Object> a = dummy.generate(req(DummyTarget.DASHBOARD, null, D, 4, Map.of(FailureScenario.SEND_FAIL, 1)));
        Map<String, Object> b = dummy.generate(req(DummyTarget.DASHBOARD, null, D, 4, Map.of(FailureScenario.SEND_FAIL, 1)));
        for (DummyDataType t : DummyDataType.values()) {
            Set<String> all = new HashSet<>(keys(a, t));
            all.addAll(keys(b, t));
            assertThat(all).as(t.name()).hasSize(8);
            assertThat(type(b, t).get("seqFrom")).as(t.name()).isEqualTo(5);
        }
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'DMY-MEET-%'")).isEqualTo(8);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'DMYIMG%' AND IMAGE_SN = 2")).isEqualTo(8);
    }

    // ── 장애 시나리오 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("음성 시나리오 — CF 는 COLLECT · AF 는 ANALYZE · SF 는 SEND 에서 실패하고, 각 기대 모드(FULL · FROM_ANALYZE · FROM_SEND) 재처리로 성공한다")
    void voiceScenariosFailAtStageAndRecoverWithExpectedMode() {
        Map<String, Object> g = dummy.generate(req(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET, DummyDataType.PHONE), D, 5, oneEach()));

        VoiceBatchResult first = voice.run(day(), null, "TEST", false);
        Map<String, FileProcOutcome> o = byKey(first);
        for (DummyDataType t : List.of(DummyDataType.MEET, DummyDataType.PHONE)) {
            for (FailureScenario s : FailureScenario.values()) {
                String k = failKeys(g, t, s).get(0);
                assertThat(o.get(k).status()).as(k).isEqualTo(ProcStatus.FAIL);
                assertThat(o.get(k).failedStep()).as(k).isEqualTo(s.voiceStep());
                assertThat(o.get(k).errMsg()).as(k).contains("더미 시나리오 " + s.name());
            }
            long ok = keys(g, t).stream().filter(k -> FailureScenario.ofKey(k).isEmpty())
                    .filter(k -> o.get(k).status() == ProcStatus.SUCCESS).count();
            assertThat(ok).as(t + " 정상 건").isEqualTo(2);
        }
        // 보존물 — 분석 장애는 복호화 오디오, 전송 장애는 전사가 남아야 그 단계부터 잇는다
        FileProcOutcome af = o.get(failKeys(g, DummyDataType.MEET, FailureScenario.ANALYZE_FAIL).get(0));
        assertThat(Path.of(dirs.work()).resolve("decrypted_" + af.target().srcFileName())).isRegularFile();
        FileProcOutcome sf = o.get(failKeys(g, DummyDataType.PHONE, FailureScenario.SEND_FAIL).get(0));
        assertThat(sttTemp.find(sf.target(), null)).isPresent();

        // 시나리오마다 자기 구간 · 기대 모드로 재처리
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> windows = (Map<String, Map<String, Object>>) g.get("windows");
        for (FailureScenario s : FailureScenario.values()) {
            Map<String, Object> w = windows.get(s.name());
            assertThat(w.get("expectedResume")).isEqualTo(s.expectedResume().name());
            BatchWindow win = BatchWindow.manual(LocalDateTime.parse((String) w.get("from")), LocalDateTime.parse((String) w.get("to")));
            VoiceBatchResult r = voice.run(win, null, "RESUME", false, ResumeMode.valueOf((String) w.get("expectedResume")), null);
            Map<String, FileProcOutcome> ro = byKey(r);
            assertThat(r.failCnt()).as(s + " 재처리 실패 %s", r.outcomes()).isZero();
            for (DummyDataType t : List.of(DummyDataType.MEET, DummyDataType.PHONE)) {
                String k = failKeys(g, t, s).get(0);
                assertThat(ro.get(k)).as("구간 안 " + k).isNotNull();
                assertThat(ro.get(k).status()).as(k).isEqualTo(ProcStatus.SUCCESS);
            }
            assertThat(ro.keySet()).as("시나리오 구간에는 그 시나리오 건만").allMatch(k -> FailureScenario.ofKey(k).orElse(null) == s);
        }
        assertThat(Path.of(dirs.work()).resolve("decrypted_" + af.target().srcFileName())).as("성공하면 보존물 정리").doesNotExist();
        assertThat(sttTemp.find(sf.target(), null)).isEmpty();

        VoiceBatchResult again = voice.run(day(), null, "TEST", false);
        assertThat(again.successCnt() + again.failCnt()).as("모두 처리됨 — 다시 돌리면 전부 건너뜀").isZero();
    }

    @Test
    @DisplayName("시험 실행(test=true)은 대시보드 더미(DMY)를 집지 않는다 — 실제 배치 몫")
    void testRunSkipsDashboardDummies() {
        dummy.generate(req(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET, DummyDataType.PHONE), D, 2, Map.of()));
        dummy.generate(req(DummyTarget.SIMULATOR, List.of(DummyDataType.MEET), D, 1, Map.of()));

        assertThat(voice.pending(day(), null, true).get("total")).isEqualTo(1);
        assertThat(voice.pending(day(), null, false).get("total")).isEqualTo(5);
        VoiceBatchResult r = voice.run(day(), null, "TEST", true);
        assertThat(r.outcomes()).extracting(x -> x.target().idempotencyKey()).noneMatch(k -> k.startsWith("DMY-"));
        assertThat(r.successCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미지 시나리오 — CF 는 수신 · AF 는 복호화 · SF 는 매핑(롤백)에서 실패하고, 다시 돌리면 실패 건만 처리해 성공한다")
    void imageScenariosFailAtStageAndRerunRecovers() {
        Map<String, Object> g = dummy.generate(req(DummyTarget.DASHBOARD, List.of(DummyDataType.IMAGE), D, 4, oneEach()));
        ImageCollectService.ImageRunResult r1 = image.run(new ImageCollectService.ImageRunRequest(null, "DMYIMG", null, 2, false,
                0L, "TEST", false, null, null));
        Map<String, ImageOutcome> o = r1.outcomes().stream().collect(Collectors.toMap(ImageOutcome::corrNo, Function.identity()));
        assertThat(o).hasSize(4);
        for (FailureScenario s : FailureScenario.values()) {
            String corr = failKeys(g, DummyDataType.IMAGE, s).get(0);
            assertThat(o.get(corr).status()).as(corr).isEqualTo("FAIL");
            assertThat(o.get(corr).failedAt()).as(corr).isEqualTo(s.imageStage());
            assertThat(o.get(corr).injected()).as("주입(시험 실행 전용)이 아니라 실제 경로 실패").isFalse();
        }
        assertThat(r1.success()).isEqualTo(1);
        assertThat(photos.stats("DMYIMG").get("sim")).as("매핑 실패는 롤백 — 성공 1건만").isEqualTo(1L);

        ImageCollectService.ImageRunResult r2 = image.run(new ImageCollectService.ImageRunRequest(null, "DMYIMG", null, 2, false,
                0L, "TEST", false, null, null));
        assertThat(r2.fail()).as("재실행 %s", r2.outcomes()).isZero();
        assertThat(r2.success()).isEqualTo(3);
        assertThat(r2.skipped()).as("이미 매핑된 정상 건은 변경 없음").isEqualTo(1);
        assertThat(photos.stats("DMYIMG").get("sim")).isEqualTo(4L);
        assertThat(r2.failByStage()).isEmpty();
        assertThat(ImageStage.values()).contains(ImageStage.ACQUIRE, ImageStage.DECRYPT, ImageStage.MAP);
    }

    @Test
    @DisplayName("이미지 — 실제 수집(접두 없이)은 DMYIMG 를 집고 SIMIMG 는 뺀다(기존 동작 유지)")
    void realImageRunTakesDashboardButNotSimulator() {
        dummy.generate(req(DummyTarget.DASHBOARD, List.of(DummyDataType.IMAGE), D, 1, Map.of()));
        dummy.generate(req(DummyTarget.SIMULATOR, List.of(DummyDataType.IMAGE), D, 2, Map.of()));
        ImageCollectService.ImageRunResult r = image.run(new ImageCollectService.ImageRunRequest(null, null, null, 2, false,
                0L, "TEST", false, null, null));
        assertThat(r.outcomes()).extracting(ImageOutcome::corrNo).allMatch(c -> !c.startsWith("SIMIMG"));
        assertThat(r.outcomes()).extracting(ImageOutcome::corrNo).anyMatch(c -> c.startsWith("DMYIMG"));
    }

    // ── 용도별 초기화 ──────────────────────────────────────────────────────

    private long dashboardMarkers() throws Exception {
        Path p = Path.of(dirs.work(), ".processed");
        if (!Files.isDirectory(p)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(p)) {
            return s.filter(f -> DummyTarget.isDashboardLocalName(f.getFileName().toString())).count();
        }
    }

    @Test
    @DisplayName("초기화 격리 — 시뮬레이터 초기화는 DMY(행 · 파일 · 멱등 표식 · 사진 매핑)를, 대시보드 초기화는 SIM 을 건드리지 않는다")
    void resetsDoNotTouchTheOtherTarget() throws Exception {
        dummy.generate(req(DummyTarget.SIMULATOR, null, D, 2, Map.of()));
        dummy.generate(req(DummyTarget.DASHBOARD, null, D, 2, Map.of()));
        voice.run(day(), null, "TEST", false);   // DMY · SIM 모두 처리 → 멱등 표식
        image.run(new ImageCollectService.ImageRunRequest(null, "DMYIMG", null, 2, false, 0L, "TEST", false, null, null));
        assertThat(dashboardMarkers()).isEqualTo(4);
        Path dmyOriginal = dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.DASHBOARD, D, 1, null));
        Path simOriginal = dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.SIMULATOR, D, 1, null));

        // ① 시뮬레이터 초기화 — SIM 만
        @SuppressWarnings("unchecked")
        Map<String, Object> simReset = voiceMock.deleteSimData(DummyTarget.SIMULATOR).getBody();
        assertThat(simReset).containsKey("historyKept");
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-%'")).isZero();
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'SIMIMG%'")).isZero();
        assertThat(simOriginal).doesNotExist();
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'DMY-%'")).isEqualTo(2);
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'DMY-%'")).isEqualTo(2);
        assertThat(count(tables.imscPtprDt(), "CORR_NO LIKE 'DMY%'")).isEqualTo(4);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'DMYIMG%'")).isEqualTo(6);
        assertThat(photos.stats("DMYIMG").get("sim")).isEqualTo(2L);
        assertThat(dmyOriginal).isRegularFile();
        assertThat(dashboardMarkers()).as("실제 배치가 처리한 DMY 의 멱등 표식은 남는다").isEqualTo(4);

        // ② 대시보드 초기화 — DMY 만
        dummy.generate(req(DummyTarget.SIMULATOR, null, D, 2, Map.of()));
        Map<String, Object> dmyReset = dummy.cleanDashboard();
        assertThat(dmyReset.get("historyKept")).asString().contains("T1~T5");
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'DMY-%'")).isZero();
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'DMY-%'")).isZero();
        assertThat(count(tables.imscPtprDt(), "CORR_NO LIKE 'DMY%'")).isZero();
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'DMYIMG%'")).isZero();
        assertThat(count(tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'DMY%'")).isZero();
        assertThat(count(tables.asysContentElement(), "ELEMENTID LIKE 'DMY%'")).isZero();
        assertThat(photos.stats("DMYIMG").get("sim")).isEqualTo(0L);
        assertThat(dmyOriginal).doesNotExist();
        assertThat(dashboardMarkers()).isZero();
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%' AND CRT_USR_ID = 'simadm'")).isEqualTo(2);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE 'SIMIMG%'")).isEqualTo(6);
        assertThat(simOriginal).isRegularFile();
    }

    @Test
    @DisplayName("보존 기간 정리 — 기준일보다 앞선 DMY 만 지우고 최근 것은 남긴다(일 단위 자동 생성)")
    void purgeKeepsRecentDashboardDummies() {
        LocalDate old = LocalDate.now().minusDays(10);
        dummy.generate(req(DummyTarget.DASHBOARD, null, old, 2, Map.of()));
        dummy.generate(req(DummyTarget.DASHBOARD, null, D, 2, Map.of()));
        dummy.purgeDashboardBefore(LocalDate.now().minusDays(7));
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE ?", "DMY-MEET-" + DummyKeys.ymd8(old) + "-%")).isZero();
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE ?", "DMYIMG" + DummyKeys.ymd6(old) + "%")).isZero();
        assertThat(count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE ?", "DMY-MEET-" + DummyKeys.ymd8(D) + "-%")).isEqualTo(2);
        assertThat(count(tables.irimBsifDs(), "CORR_NO LIKE ?", "DMYIMG" + DummyKeys.ymd6(D) + "%")).isEqualTo(6);
        assertThat(dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.DASHBOARD, old, 1, null))).doesNotExist();
        assertThat(dirs.xvarmOriginalDir(VoiceKind.MEET).resolve(DummyKeys.meetFileName(DummyTarget.DASHBOARD, D, 1, null))).isRegularFile();

        // 스케줄러 한 번 — 어제 날짜로 추가하고 정리
        MockDataProperties p = new MockDataProperties(new MockDataProperties.Dashboard(true),
                new MockDataProperties.Daily(true, "0 30 1 * * *", List.of(DummyDataType.PHONE), 3, 1, 0, 0, 7));
        Map<String, Object> once = new MockDataScheduler(dummy, p).runOnce(LocalDate.now());
        assertThat(once).containsKeys("generated", "purged");
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE ?", "DMY-PHONE-" + DummyKeys.ymd8(D) + "-%")).isEqualTo(2 + 3);
        assertThat(count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE ?", "DMY-PHONE-" + DummyKeys.ymd8(D) + "-CF-%")).isEqualTo(1);
    }

    // ── 정형 오염 방지 ────────────────────────────────────────────────────

    @Test
    @DisplayName("생성 · 초기화 SQL 에 수용자기본(TB_IRIM_PRBS_BS) · 신상(TB_IRIM_PEIN_BS)이 없다")
    void neverTouchesInmateMasterTables() {
        for (DummyTarget t : DummyTarget.values()) {
            Map<String, Object> g = dummy.generate(req(t, null, D, 3, oneEach()));
            assertTablesClean(g);
        }
        assertTablesClean(dummy.cleanDashboard());
        assertThat(mockProps.dashboard().enabled()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static void assertTablesClean(Map<String, Object> out) {
        List<String> touched = (List<String>) out.get("sqlTables");
        assertThat(touched).isNotEmpty();
        assertThat(touched).noneMatch(t -> t.toUpperCase().contains("PRBS_BS") || t.toUpperCase().contains("PEIN_BS"));
        assertThat((List<String>) out.get("sqls")).noneMatch(s -> s.toUpperCase().contains("PRBS_BS") || s.toUpperCase().contains("PEIN_BS"));
    }

    @Test
    @DisplayName("잘못된 요청 — 미래 날짜 · 건수 상한 · 장애 합 초과는 거절한다")
    void rejectsBadRequests() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dummy.generate(req(DummyTarget.DASHBOARD, null,
                LocalDate.now().plusDays(1), 1, Map.of()))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dummy.generate(req(DummyTarget.DASHBOARD, null, D, 101, Map.of())))
                .as("유형 합계 300 초과").isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dummy.generate(req(DummyTarget.DASHBOARD,
                List.of(DummyDataType.MEET), D, 2, oneEach()))).isInstanceOf(IllegalArgumentException.class);
    }
}
