package egovframework.unstructured.collector.voice.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.unstructured.collector.common.model.FileProcOutcome;
import egovframework.unstructured.collector.common.model.VoiceKind;
import egovframework.unstructured.collector.common.model.VoiceTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 결과 JSON 직렬화 — <b>파생 메서드가 응답에서 빠지지 않는지</b> 못 박는다.
 *
 * <p><b>왜 따로 테스트하는가</b>: 이 함정에 두 번 걸렸다. record 는 <b>컴포넌트만</b> 자동
 * 직렬화된다. {@code execStsCd()} 처럼 값을 계산해 주는 메서드는
 * {@code get} 접두어도 없어 Jackson 이 조용히 건너뛴다 — 예외도 경고도 없이 화면에만
 * {@code undefined} 가 찍힌다. Java 객체를 직접 보는 테스트로는 절대 잡히지 않는다.</p>
 */
class BatchResultSerializationTest {

    // 스프링 부트의 ObjectMapper 처럼 JSR-310(LocalDateTime) 모듈을 얹는다 — VoiceTarget.occurredAt 이 들어 있다
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static VoiceTarget meet(int i) {
        return new VoiceTarget(VoiceKind.MEET, "CORR" + i, "TARE-000" + i, "DOC", "FK",
                true, null, "/data001", "mock_meet_00" + i + ".m4a", null, LocalDateTime.now());
    }

    private VoiceBatchResult sample(int fail) {
        List<FileProcOutcome> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            outcomes.add(i < fail
                    ? FileProcOutcome.fail(meet(i), FileProcOutcome.STEP_COLLECT,
                            "ResourceAccessException: I/O error on POST — Connection refused", 10L)
                    : FileProcOutcome.success(meet(i), 1234L, 300, "zenon:mock/meet-TARE-000" + i + ".json", 20L));
        }
        List<VoiceBatchResult.StepLog> steps = List.of(
                new VoiceBatchResult.StepLog("COLLECT", "20260916TST00101", fail == 0 ? "SUCCESS" : "PARTIAL", 10, 10 - fail, fail, 1, true),
                new VoiceBatchResult.StepLog("ANALYZE", "20260916TST00102", "SUCCESS", 10 - fail, 10 - fail, 0, 2, true));
        return new VoiceBatchResult("20260916TST001", true, "DAILY[...]", 10, 10 - fail, fail,
                0, 1234L, Map.of("mode", "MOCK", "endpoint", "MOCK", "sent", "2"), steps, outcomes);
    }

    @Test
    @DisplayName("execStsCd 가 JSON 에 실린다 — record 컴포넌트가 아니라 파생 메서드다")
    void serializesExecStsCd() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample(0)));

        assertThat(json.has("execStsCd")).as("빠지면 화면 '상태' 칸이 비어 보인다").isTrue();
        assertThat(json.path("execStsCd").asText()).isEqualTo("SUCCESS");
        assertThat(json.path("errMsg").isNull()).as("성공이면 대표 오류가 없다").isTrue();
    }

    @Test
    @DisplayName("제논 전송 요약·T2 단계·파일별 전송 위치(sttPath)가 응답에 실린다 — 시뮬레이터가 그대로 그린다")
    void serializesOutputsAndSteps() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample(0)));

        assertThat(json.path("zenon").path("mode").asText()).isEqualTo("MOCK");
        assertThat(json.path("steps")).hasSize(2);
        assertThat(json.path("steps").get(0).path("stepTypeCd").asText()).isEqualTo("COLLECT");
        assertThat(json.path("steps").get(1).path("stepTypeCd").asText()).isEqualTo("ANALYZE");
        assertThat(json.path("steps").get(1).path("stepLogId").asText()).isEqualTo("20260916TST00102");
        assertThat(json.path("outcomes").get(0).path("sttPath").asText()).startsWith("zenon:").endsWith(".json");
        assertThat(json.path("outcomes").get(0).has("failedStep")).isTrue();
        assertThat(json.path("execIdFromCollector").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("일부 실패면 PARTIAL, 전부 실패면 FAIL — 대표 오류(errMsg)는 실패 사유 중 최다")
    void statusReflectsOutcome() throws Exception {
        JsonNode partial = mapper.readTree(mapper.writeValueAsString(sample(3)));
        assertThat(partial.path("execStsCd").asText()).isEqualTo("PARTIAL");
        assertThat(partial.path("errMsg").asText()).contains("Connection refused");

        JsonNode fail = mapper.readTree(mapper.writeValueAsString(sample(10)));
        assertThat(fail.path("execStsCd").asText()).isEqualTo("FAIL");
        assertThat(fail.path("outcomes").get(0).path("failedStep").asText()).isEqualTo("COLLECT");
    }

    @Test
    @DisplayName("중단한 배치는 CANCELED — 43건만 하고 멈췄는데 이력에 '성공'으로 남으면 안 된다")
    void canceledBatchIsNotSuccess() throws Exception {
        VoiceBatchResult r = sample(0);
        VoiceBatchResult canceled = new VoiceBatchResult(r.execId(), r.execIdFromCollector(), r.window(),
                160, 43, 0, 117, r.elapsedMs(), r.zenon(), r.steps(), r.outcomes(), true);

        assertThat(canceled.execStsCd()).isEqualTo("CANCELED");
        assertThat(mapper.readTree(mapper.writeValueAsString(canceled)).path("execStsCd").asText())
                .isEqualTo("CANCELED");
        // 실패가 0이라는 이유로 SUCCESS 가 되던 자리
        assertThat(r.execStsCd()).as("중단이 아니면 그대로 SUCCESS").isEqualTo("SUCCESS");
    }
}
