package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.config.VoiceDirState;
import egovframework.unstructured.collector.image.store.InmatePhotoRepository;
import egovframework.unstructured.collector.mock.DummyDataService;
import egovframework.unstructured.collector.mock.DummyDataType;
import egovframework.unstructured.collector.mock.DummyTarget;
import egovframework.unstructured.collector.voice.batch.IdempotencyGuard;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * admin 연동 — 실제 컨텍스트에서 {@code /internal/**} 를 부른다(배선 · 비동기 실행 · 안전 스위치).
 * admin-api 주소는 비워 조회하지 않는다(dev 프로파일로 돌아도 외부로 나가지 않게 테스트 속성으로 못 박는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminLinkE2ETest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("unstructured.admin.base-url", () -> "");
        registry.add("voice.batch.schedule-enabled", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
        // 바로 실행이 음성 뒤에 이미지도 돈다(개발계 기본과 같게). Admin DB 는 H2 로 못 박는다 — Jenkins 는 dev 프로파일로 테스트한다
        registry.add("unstructured.batch.include-image", () -> "true");
        registry.add("image.admin-db.url", () -> "jdbc:h2:mem:admin-adminlink;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("image.admin-db.username", () -> "sa");
        registry.add("image.admin-db.password", () -> "");
        registry.add("unstructured.mock.dashboard.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private SimulationDataService sim;
    @Autowired
    private IdempotencyGuard idempotency;
    @Autowired
    private UnstructuredJobRunner runner;
    @Autowired
    private DummyDataService dummy;
    @Autowired
    private VoiceDirState dirs;
    @Autowired
    private InmatePhotoRepository photos;

    @BeforeEach
    void seed() {
        await().atMost(60, TimeUnit.SECONDS).until(() -> !runner.isRunning());
        sim.seed();
        idempotency.clearAll();
    }

    @Test
    @DisplayName("바로 실행 — 202 즉시 응답(로컬 execId) → 백그라운드에서 음성 배치 완료 · 상태로 확인")
    void runNowAsync() throws Exception {
        String to = LocalDateTime.now().withNano(0).toString();
        String res = mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"targetToDtm\":\"" + to + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = om.readTree(res);
        assertThat(body.path("execIdSource").asText()).as("로그 컬렉터 미연동 — 로컬 임시 execId").isEqualTo("LOCAL");
        String execId = body.path("execId").asText();

        await().atMost(60, TimeUnit.SECONDS).until(() -> !runner.isRunning());
        JsonNode st = om.readTree(mvc.perform(get("/internal/batch/status").param("execId", execId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(st.path("job").path("status").asText()).as("결과: %s", st).isEqualTo("DONE");
        assertThat(st.path("job").path("result").asText()).contains("음성 execId=" + execId).contains("성공");
        assertThat(st.path("schedule").path("adminEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("바로 실행 — 시뮬레이터 데이터(SIM)는 집지 않고 대시보드 더미(DMY)만 · 음성 → 이미지(include-image)")
    void runNowTakesDashboardDummiesOnly() throws Exception {
        dummy.cleanDashboard();
        Map<String, Object> g = dummy.generate(new DummyDataService.GenerateRequest(DummyTarget.DASHBOARD, null,
                LocalDate.now().minusDays(1), 2, Map.of(), true, null));
        try {
            String res = mvc.perform(post("/internal/batch/run").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataTypeCd\":\"UNSTRUCTURED\"}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            assertThat(om.readTree(res).path("includeImage").asBoolean()).isTrue();
            await().atMost(60, TimeUnit.SECONDS).until(() -> !runner.isRunning());
            JsonNode st = om.readTree(mvc.perform(get("/internal/batch/status")).andReturn().getResponse().getContentAsString());
            assertThat(st.path("job").path("status").asText()).as("결과: %s", st).isEqualTo("DONE");
            assertThat(st.path("job").path("result").asText()).contains("이미지 execId=");

            List<String> markers;
            try (Stream<Path> s = Files.list(Path.of(dirs.work(), ".processed"))) {
                markers = s.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(markers).as("DMY 음성 4건(접견 2 · 전화 2)을 처리했다").filteredOn(DummyTarget::isDashboardLocalName).hasSize(4);
            assertThat(markers).as("SIM(시연 기본 14건)은 실제 실행이 집지 않는다").noneMatch(n -> n.contains("-SIM-"));
            assertThat(photos.stats("DMYIMG").get("sim")).as("DMY 이미지 2명 매핑").isEqualTo(2L);
            @SuppressWarnings("unchecked")
            Map<String, Object> image = (Map<String, Object>) ((Map<String, Object>) g.get("types")).get(DummyDataType.IMAGE.name());
            assertThat(image.get("count")).isEqualTo(2);
        } finally {
            dummy.cleanDashboard();
        }
    }

    @Test
    @DisplayName("refresh 본문 — VOICE_SCHEDULE_ENABLED=false 면 값만 받고 트리거는 걸지 않는다 · 음성 상태 API 에도 보인다")
    void refreshWithSafetyOff() throws Exception {
        mvc.perform(post("/internal/schedule/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataTypeCd\":\"UNSTRUCTURED\",\"activeYn\":\"Y\",\"execSchedTypeCd\":\"INTERVAL_BASED\",\"schedVal\":\"00:30:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("applied:UNSTRUCTURED"));
        mvc.perform(get("/api/v1/voice/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.schedule.schedVal").value("00:30:00"))
                .andExpect(jsonPath("$.batch.schedule.armed").value(false))
                .andExpect(jsonPath("$.batch.schedule.appliedFrom").value("REFRESH"));
    }

    @Test
    @DisplayName("긴급 재처리 — 로그 컬렉터 미연동이면 원배치를 찾을 수 없어 400")
    void reprocessWithoutCollector() throws Exception {
        mvc.perform(post("/internal/batch/reprocess").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"execId\":\"20260915UNS003\",\"dataTypeCd\":\"UNSTRUCTURED\",\"stepTypeCd\":\"ANALYZE\",\"stepSeq\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("로그 컬렉터 미연동")));
    }
}
