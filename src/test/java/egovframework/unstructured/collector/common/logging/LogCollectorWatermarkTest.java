package egovframework.unstructured.collector.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import egovframework.unstructured.collector.common.config.DeployEnvPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * [바로 실행] 워터마크 — <b>T1 이 원본이다.</b>
 *
 * <p>기준점을 파일({@code state/last_success.txt})에 적던 방식을 걷어 내고, 로그 컬렉터 T1 의
 * SUCCESS 배치 {@code MAX(target_to_dtm)} 을 읽는다. 그러려면 배치를 열 때 구간을 T1 에 남겨야 하는데,
 * 예전에는 {@code targetFromDtm}·{@code targetToDtm} 을 null 로 보내 그 칸이 전부 비어 있었다.</p>
 */
class LogCollectorWatermarkTest {

    private LogCollectorClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        client = new LogCollectorClient(om, mock(DeployEnvPreset.class));
        ReflectionTestUtils.setField(client, "baseUrl", "http://lc/logc");
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "connectTimeoutSec", 1);
        ReflectionTestUtils.setField(client, "readTimeoutSec", 1);
        client.init();
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }

    @Test
    @DisplayName("배치를 열 때 훑을 구간을 T1 에 남긴다 — 워터마크의 재료다")
    void createBatchSendsTheWindow() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.targetFromDtm").value("2026-09-26T00:00:00"))
                .andExpect(jsonPath("$.targetToDtm").value("2026-09-27T00:00:00"))
                .andExpect(jsonPath("$.execTypeCd").value("SCHEDULED"))
                .andRespond(withSuccess("{\"success\":true,\"code\":0,\"result\":{\"execId\":\"20260927VOC001\"}}",
                        MediaType.APPLICATION_JSON));

        String id = client.createBatch("VOICE_ANALYSIS", "UNSTRUCTURED", "SCHEDULED", "SCHEDULER",
                LocalDateTime.of(2026, 9, 26, 0, 0), LocalDateTime.of(2026, 9, 27, 0, 0));

        assertThat(id).isEqualTo("20260927VOC001");
        server.verify();
    }

    @Test
    @DisplayName("워터마크를 읽는다 — 작업(jobId)별로 가른다")
    void readsWatermark() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/watermark?dataTypeCd=UNSTRUCTURED&jobId=TEST_BATCH"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"success\":true,\"code\":0,\"result\":"
                        + "{\"watermark\":\"2026-09-27T00:00:00\",\"execId\":\"20260927TST001\"}}", MediaType.APPLICATION_JSON));

        LogCollectorClient.Watermark w = client.watermark("UNSTRUCTURED", "TEST_BATCH");

        assertThat(w).isNotNull();
        assertThat(w.at()).isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));
        assertThat(w.execId()).isEqualTo("20260927TST001");
    }

    @Test
    @DisplayName("성공한 배치가 없으면 null — 호출 측이 최근 30일로 되짚는다")
    void nullWhenNoSuccess() {
        server.expect(requestTo("http://lc/logc/api/v1/logs/batches/watermark?dataTypeCd=UNSTRUCTURED&jobId=VOICE_ANALYSIS"))
                .andRespond(withSuccess("{\"success\":true,\"code\":0,\"result\":{\"watermark\":null,\"execId\":null}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.watermark("UNSTRUCTURED", "VOICE_ANALYSIS")).isNull();
    }

    @Test
    @DisplayName("컬렉터가 꺼져 있으면 부르지도 않고 null")
    void nullWhenDisabled() {
        ReflectionTestUtils.setField(client, "enabled", false);

        assertThat(client.watermark("UNSTRUCTURED", "VOICE_ANALYSIS")).isNull();
        server.verify();   // 아무 요청도 없어야 한다
    }

    @Test
    @DisplayName("시각 해석 — 시간대가 붙은 값은 이 서버 시간대로 옮긴다. 앞부분만 자르면 9시간이 어긋난다")
    void parsesEveryShapeIntoLocalWallClock() {
        assertThat(LogCollectorClient.parseDtm("2026-09-27T00:00:00")).isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));
        assertThat(LogCollectorClient.parseDtm("2026-09-27 00:00:00")).isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));
        assertThat(LogCollectorClient.parseDtm("2026-09-27T00:00")).isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));

        String utc = "2026-09-26T15:00:00.000+00:00";
        LocalDateTime expected = OffsetDateTime.parse(utc).atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime().withNano(0);
        assertThat(LogCollectorClient.parseDtm(utc)).isEqualTo(expected);
        assertThat(LogCollectorClient.parseDtm("2026-09-26T15:00:00Z")).isEqualTo(expected);
        assertThat(LogCollectorClient.parseDtm("2026-09-26T15:00:00+0000")).isEqualTo(expected);

        long millis = OffsetDateTime.parse(utc).toInstant().toEpochMilli();
        assertThat(LogCollectorClient.parseDtm(String.valueOf(millis))).isEqualTo(expected);
    }
}
