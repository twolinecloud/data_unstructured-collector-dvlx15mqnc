package egovframework.unstructured.collector.image;

import egovframework.unstructured.collector.batch.UnstructuredBatchService;
import egovframework.unstructured.collector.common.logging.FakeLogCollector;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.util.InmatePidGenerator;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.image.sim.ImageSimulationService;
import egovframework.unstructured.collector.mock.DummyDataService;
import egovframework.unstructured.collector.mock.DummyDataType;
import egovframework.unstructured.collector.mock.DummyTarget;
import egovframework.unstructured.collector.mock.FailureScenario;
import egovframework.unstructured.collector.mock.ScenarioFaults;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import egovframework.unstructured.collector.voice.controller.VoiceMockController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수용자 이미지 처리 이력 — 로그 컬렉터 <b>T1 · T2 · T4</b> 적재(비정형 3단계 C05: COLLECT · ANALYZE · SEND).
 *
 * <p>진짜 로그 컬렉터는 PostgreSQL 전용이라 {@link FakeLogCollector}(실제 HTTP · H2 표)를 띄워 수집기가 보낸 그대로 받는다.
 * 로컬 H2(보라미 · Admin) · 브로커/전화/STT/제논 MOCK · 복호화 REAL(테스트 키). 외부 주소는 테스트 속성으로 못 박는다
 * (Jenkins 는 dev 프로파일로 테스트한다).</p>
 */
@SpringBootTest(properties = {
        "image.admin-db.url=jdbc:h2:mem:admin-imglog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "image.admin-db.username=sa",
        "image.admin-db.password=",
        "voice.source.mode=MOCK",
        "voice.broker.mode=MOCK",
        "voice.phone.mode=MOCK",
        "voice.stt.mode=MOCK",
        "voice.decrypt.mode=REAL",
        "zenon.mode=MOCK",
        "log-collector.enabled=true",
        "unstructured.admin.base-url=",
        "voice.batch.schedule-enabled=false",
        "voice.sim.seed-on-startup=false",
        "voice.sync.wait-timeout-sec=10",
        "voice.sync.stable-check-ms=20",
        "unstructured.mock.dashboard.enabled=true"
})
class ImageLogHistoryTest {

    private static Path root;
    private static FakeLogCollector logc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("imglog");
        Path key = root.resolve("rvs_key.txt");
        Files.writeString(key, String.join("\n",
                "secretkey=LOCAL_TEST_KEY_0123456789abcdefg", "iv=0000000000000000", "algorithm=AES",
                "ciphermode=CBC", "padding=PKCS5Padding", "charset=UTF-8"));
        logc = FakeLogCollector.start();
        registry.add("log-collector.base-url", logc::baseUrl);
        registry.add("voice.dirs.base-dir", () -> root.toString().replace('\\', '/'));
        registry.add("voice.decrypt.rvs-key-path", key::toString);
    }

    @AfterAll
    static void stop() {
        logc.close();
    }

    @Autowired
    private DummyDataService dummy;
    @Autowired
    private VoiceMockController voiceMock;
    @Autowired
    private ImageCollectService image;
    @Autowired
    private ImageSimulationService imageSim;
    @Autowired
    private UnstructuredBatchService unstructured;
    @Autowired
    private VoiceCollectService voice;
    @Autowired
    private InmatePidGenerator pid;
    @Autowired
    private ScenarioFaults scenarioFaults;

    private static final LocalDate D = LocalDate.now().minusDays(1);

    @BeforeEach
    @AfterEach
    void clean() {
        dummy.cleanDashboard();
        voiceMock.deleteSimData(DummyTarget.SIMULATOR);
        scenarioFaults.forget(k -> true);
        logc.clear();
    }

    private static Map<FailureScenario, Integer> oneEach() {
        Map<FailureScenario, Integer> m = new EnumMap<>(FailureScenario.class);
        m.put(FailureScenario.COLLECT_FAIL, 1);
        m.put(FailureScenario.ANALYZE_FAIL, 1);
        m.put(FailureScenario.SEND_FAIL, 1);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> keys(Map<String, Object> g, DummyDataType t) {
        return (List<String>) ((Map<String, Object>) ((Map<String, Object>) g.get("types")).get(t.name())).get("keys");
    }

    private Map<String, Object> t1(String execId) {
        return logc.rows("SELECT * FROM tb_batch_exec_log WHERE exec_id = ?", execId).get(0);
    }

    private Map<String, Map<String, Object>> t2(String execId) {
        return logc.rows("SELECT * FROM tb_batch_step_log WHERE exec_id = ? ORDER BY step_seq", execId).stream()
                .collect(Collectors.toMap(r -> (String) r.get("step_type_cd"), Function.identity()));
    }

    /** T4 — 가명ID → 행. */
    private Map<String, Map<String, Object>> t4ByPid(String execId) {
        return logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", execId).stream()
                .collect(Collectors.toMap(r -> (String) r.get("inmate_pid"), Function.identity()));
    }

    private static long n(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    @Test
    @DisplayName("이미지 수집 — T1(IMAGE_COLLECT · UNS 채번) · T2 3단계(COLLECT·ANALYZE·SEND 건수) · T4 사진 1장 = 1행(단계 · 상태 · 경로 · 가명ID)")
    void imageRunWritesT1T2T4() {
        Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD,
                List.of(DummyDataType.IMAGE), D, 4, oneEach(), true, null));
        List<String> corrs = keys(g, DummyDataType.IMAGE);

        ImageCollectService.ImageRunResult r = image.run(new ImageCollectService.ImageRunRequest(null, "DMYIMG", null, 2, false,
                0L, "API", false, null, null));
        assertThat(r.execId()).as("로그 컬렉터가 채번한 실행 ID").matches("\\d{8}UNS\\d{3}");

        Map<String, Object> b = t1(r.execId());
        assertThat(b.get("job_id")).isEqualTo("IMAGE_COLLECT");
        assertThat(b.get("job_nm")).isEqualTo(ImageCollectService.JOB_NM);
        assertThat(b.get("data_type_cd")).isEqualTo("UNSTRUCTURED");
        assertThat(b.get("exec_type_cd")).isEqualTo("MANUAL");
        assertThat(b.get("target_to_dtm")).as("이미지는 수집 구간이 없다 — 음성 워터마크에 끼지 않게 비운다").isNull();
        assertThat(b.get("exec_sts_cd")).isEqualTo("PARTIAL");
        assertThat(n(b, "target_cnt")).isEqualTo(4);
        assertThat(n(b, "success_cnt")).isEqualTo(1);
        assertThat(n(b, "fail_cnt")).isEqualTo(3);

        Map<String, Map<String, Object>> steps = t2(r.execId());
        assertThat(steps.keySet()).containsExactlyInAnyOrder("COLLECT", "ANALYZE", "SEND");
        assertThat(List.of(n(steps.get("COLLECT"), "in_cnt"), n(steps.get("COLLECT"), "out_cnt"), n(steps.get("COLLECT"), "err_cnt")))
                .containsExactly(4L, 3L, 1L);
        assertThat(List.of(n(steps.get("ANALYZE"), "in_cnt"), n(steps.get("ANALYZE"), "out_cnt"), n(steps.get("ANALYZE"), "err_cnt")))
                .containsExactly(3L, 2L, 1L);
        assertThat(List.of(n(steps.get("SEND"), "in_cnt"), n(steps.get("SEND"), "out_cnt"), n(steps.get("SEND"), "err_cnt")))
                .containsExactly(2L, 1L, 1L);
        assertThat(steps.values()).allMatch(s -> "PARTIAL".equals(s.get("step_sts_cd")));
        assertThat(n(steps.get("COLLECT"), "step_seq")).isEqualTo(1);
        assertThat(n(steps.get("SEND"), "step_seq")).isEqualTo(3);

        Map<String, Map<String, Object>> t4 = t4ByPid(r.execId());
        assertThat(t4).hasSize(4);
        Map<FailureScenario, String> stepOf = Map.of(FailureScenario.COLLECT_FAIL, "COLLECT",
                FailureScenario.ANALYZE_FAIL, "ANALYZE", FailureScenario.SEND_FAIL, "SEND");
        for (String corr : corrs) {
            Map<String, Object> row = t4.get(pid.of(corr));
            assertThat(row).as("가명ID 로 남는다 — 교정번호 원문이 아니다 %s", corr).isNotNull();
            assertThat((String) row.get("rec_file_id")).as("이미지 공통파일ID(사진 원본 키)").startsWith("DMYIMGF").endsWith("2");
            FailureScenario s = FailureScenario.ofKey(corr).orElse(null);
            if (s == null) {
                assertThat(row.get("proc_sts_cd")).isEqualTo("SUCCESS");
                assertThat(row.get("step_type_cd")).isEqualTo("SEND");
                assertThat((String) row.get("file_path")).as("저장한 사진의 폴더").contains("/image/" + corr);
                assertThat((String) row.get("file_nm")).endsWith(".jpg");
                assertThat(n(row, "file_size")).isPositive();
                assertThat(row.get("err_stack")).isNull();
            } else {
                assertThat(row.get("proc_sts_cd")).isEqualTo("FAIL");
                assertThat(row.get("step_type_cd")).isEqualTo(stepOf.get(s));
                assertThat((String) row.get("err_stack")).as("실패 단계는 V16 컬럼과 함께 ERR_STACK 에도(V16 전 컬렉터 호환)")
                        .startsWith("[DATA] [" + stepOf.get(s) + "] ").contains("더미 시나리오 " + s.name());
                assertThat(row.get("file_nm")).as("저장 못 했으니 원본 파일명").isEqualTo(corr + "_2.jpg");
            }
        }
    }

    @Test
    @DisplayName("이미지 재처리(긴급 재처리 → 이미지 재실행) — 새 실행 ID 의 T4 에 실패했던 3건만 SUCCESS 로 추가(정상 건은 건너뜀 · 남기지 않음)")
    void imageReprocessAddsT4ForFailedOnly() {
        dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, List.of(DummyDataType.IMAGE), D, 4, oneEach(), true, null));
        ImageCollectService.ImageRunResult first = image.run(new ImageCollectService.ImageRunRequest(null, "DMYIMG", null, 2, false,
                0L, "SCHEDULER", false, null, null));
        assertThat(t1(first.execId()).get("exec_type_cd")).isEqualTo("SCHEDULED");

        UnstructuredBatchService.Plan p = unstructured.planReprocess(first.execId(), "UNSTRUCTURED", "ANALYZE");
        assertThat(p.imageOnly()).as("원배치가 이미지 배치 — 이미지를 다시 돈다").isTrue();
        AtomicReference<String> again = new AtomicReference<>();
        String summary = unstructured.execute(p, (id, fromCollector) -> again.set(id));
        assertThat(again.get()).isNotNull().isNotEqualTo(first.execId());
        assertThat(summary).contains("성공3 실패0 건너뜀1");

        Map<String, Object> b = t1(again.get());
        assertThat(b.get("trigger_by")).isEqualTo("ADMIN/reprocess:" + first.execId());
        assertThat(b.get("exec_sts_cd")).isEqualTo("SUCCESS");
        assertThat(n(b, "success_cnt")).isEqualTo(3);
        assertThat(n(b, "fail_cnt")).isZero();
        List<Map<String, Object>> rows = logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", again.get());
        assertThat(rows).hasSize(3).allMatch(r -> "SUCCESS".equals(r.get("proc_sts_cd")) && "SEND".equals(r.get("step_type_cd")));
        assertThat(logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", first.execId()))
                .as("원배치의 실패 이력은 그대로 남는다").hasSize(4);
    }

    @Test
    @DisplayName("음성 T4 도 단계 코드를 싣는다 — 성공 SEND · 실패는 실패한 단계(ERR_STACK 에도 [단계])")
    void voiceT4CarriesStepType() {
        Map<FailureScenario, Integer> f = new EnumMap<>(FailureScenario.class);
        f.put(FailureScenario.ANALYZE_FAIL, 1);
        f.put(FailureScenario.SEND_FAIL, 1);
        Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, List.of(DummyDataType.MEET),
                D, 3, f, true, null));
        var r = voice.run(BatchWindow.manual(D.atStartOfDay(), D.plusDays(1).atStartOfDay()), List.of(VoiceKind.MEET), "SCHEDULER", false);
        Map<String, Map<String, Object>> t4 = logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", r.execId()).stream()
                .collect(Collectors.toMap(x -> (String) x.get("rec_file_id"), Function.identity()));
        for (String key : keys(g, DummyDataType.MEET)) {
            Map<String, Object> row = t4.get(key);
            FailureScenario s = FailureScenario.ofKey(key).orElse(null);
            if (s == null) {
                assertThat(row.get("step_type_cd")).isEqualTo("SEND");
                assertThat(row.get("proc_sts_cd")).isEqualTo("SUCCESS");
            } else {
                assertThat(row.get("step_type_cd")).isEqualTo(s.voiceStep());
                assertThat((String) row.get("err_stack")).startsWith("[DATA] [" + s.voiceStep() + "] ");
            }
        }
        assertThat(t2(r.execId()).keySet()).containsExactlyInAnyOrder("COLLECT", "ANALYZE", "SEND");
    }

    @Test
    @DisplayName("SIM 사진(SIMIMG…)은 시험 전용 — 접두로 돌리면 TEST_BATCH(TST 채번) · 작업명 '(시험)'")
    void simImageRunIsTestBatch() {
        imageSim.seed(2);
        ImageCollectService.ImageRunResult r = image.run(new ImageCollectService.ImageRunRequest(null, ImageSimulationService.PREFIX,
                null, 2, false, 0L, "API", null, true, null));
        assertThat(r.execId()).matches("\\d{8}TST\\d{3}");
        Map<String, Object> b = t1(r.execId());
        assertThat(b.get("job_id")).isEqualTo("TEST_BATCH");
        assertThat(b.get("job_nm")).isEqualTo(ImageCollectService.JOB_NM + "(시험)");
        assertThat(b.get("exec_sts_cd")).isEqualTo("SUCCESS");
        assertThat(logc.rows("SELECT * FROM tb_file_proc_log WHERE exec_id = ?", r.execId())).hasSize(2);
        imageSim.clean();
    }
}
