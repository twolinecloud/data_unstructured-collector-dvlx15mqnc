package egovframework.unstructured.collector.common.config;

import egovframework.unstructured.collector.voice.batch.StageFaultState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영 안전장치 — 개발 · 로컬이 아니면 시뮬레이터 · 장애 주입 API 에 닿지 못한다. */
class MockApiBlockFilterTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockApiBlockFilter.class);

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "test"})
    @DisplayName("개발 · 로컬이 아니면 필터가 켜진다 — 새 환경도 닫힌 쪽으로 간다")
    void activeOutsideDevAndLocal(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(ctx -> assertThat(ctx).hasSingleBean(MockApiBlockFilter.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "local"})
    @DisplayName("개발 · 로컬은 필터가 없다 — 시뮬레이터가 그대로 동작한다")
    void inactiveOnDevAndLocal(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MockApiBlockFilter.class));
    }

    @Test
    @DisplayName("막는 범위 — 정확히 그 경로와 그 아래만")
    void blockedPaths() {
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/mock")).isTrue();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/mock/modes/broker")).isTrue();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/voice/faults")).isTrue();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/voice/faults/SEND")).isTrue();

        assertThat(MockApiBlockFilter.isBlocked("/api/v1/mockingbird")).isFalse();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/voice/faultsX")).isFalse();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/voice/status")).isFalse();
        assertThat(MockApiBlockFilter.isBlocked("/api/v1/voice/batches/daily")).isFalse();
        assertThat(MockApiBlockFilter.isBlocked("/internal/unstructured/run")).isFalse();
        assertThat(MockApiBlockFilter.isBlocked(null)).isFalse();
    }

    /** 실제 Tomcat 으로 — 인코딩 · 세미콜론 · 경로 정규화는 MockMvc 로는 재현되지 않는다. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    class OnRealServer {

        @TempDir
        static Path tmp;

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registry.add("voice.source.mode", () -> "MOCK");
            registry.add("voice.broker.mode", () -> "MOCK");
            registry.add("voice.dirs.base-dir", () -> tmp.toString());
            registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
            registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
            registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
            registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
            registry.add("log-collector.enabled", () -> "false");
            registry.add("voice.batch.schedule-enabled", () -> "false");
        }

        /** local 프로필에서는 필터가 꺼지므로 시험에서만 켠다. */
        @TestConfiguration
        static class FilterOn {
            @Bean
            MockApiBlockFilter mockApiBlockFilter() {
                return new MockApiBlockFilter();
            }
        }

        @LocalServerPort
        int port;

        @Autowired
        StageFaultState stageFault;

        private final HttpClient http = HttpClient.newHttpClient();

        private HttpResponse<String> send(String method, String rawPath) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }

        @Test
        @DisplayName("장애 주입 · 모드 변경 호출이 404 와 안내 문구를 받는다")
        void blocksWithMessage() throws Exception {
            for (String[] c : new String[][]{
                    {"PUT", "/api/v1/voice/faults/SEND?mode=ALL"},
                    {"DELETE", "/api/v1/voice/faults"},
                    {"GET", "/api/v1/voice/faults"},
                    {"PUT", "/api/v1/mock/modes/broker?value=REST"},
                    {"PUT", "/api/v1/mock/endpoints/broker?value=http://localhost:9999"},
                    {"GET", "/api/v1/mock/modes"}}) {
                HttpResponse<String> r = send(c[0], c[1]);
                assertThat(r.statusCode()).as(c[0] + " " + c[1]).isEqualTo(404);
                assertThat(r.body()).as(c[1]).contains("운영 환경에서는 제공하지 않는 API 입니다");
            }
            assertThat(stageFault.snapshot().get("anyOn")).isEqualTo(false);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/voice/%66aults/SEND?mode=ALL",
                "/api/v1/voice/faults;x=1/SEND?mode=ALL",
                "/api/v1/voice/faults/SEND;x=1?mode=ALL",
                "//api/v1/voice/faults/SEND?mode=ALL",
                "/api/v1/./voice/faults/SEND?mode=ALL",
                "/api/v1/mock/../voice/faults/SEND?mode=ALL",
                "/api/v1/voice/faults/SEND/?mode=ALL"})
        @DisplayName("경로를 꼬아 불러도 장애 스위치가 켜지지 않는다")
        void trickyPathsCannotTurnFaultOn(String rawPath) throws Exception {
            HttpResponse<String> r = send("PUT", rawPath);
            assertThat(r.statusCode()).as(rawPath).isBetween(400, 499);
            assertThat(stageFault.snapshot().get("anyOn")).as(rawPath).isEqualTo(false);
        }

        @Test
        @DisplayName("막지 않는 API 는 그대로 동작한다 — 필터가 전체를 막는 것이 아니다")
        void otherApisStillWork() throws Exception {
            assertThat(send("GET", "/api/v1/voice/batches/preserved").statusCode()).isEqualTo(200);
        }
    }
}
