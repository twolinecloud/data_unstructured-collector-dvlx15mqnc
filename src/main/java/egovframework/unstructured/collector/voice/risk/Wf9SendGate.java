package egovframework.unstructured.collector.voice.risk;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import org.springframework.stereotype.Component;

/**
 * WF9 전송 확인 문 — 그 배치(T1)의 클라우드 전송 단계(T2 {@code SEND} 마지막 행) 상태를 로그 컬렉터에서 읽는다(2026-11-27).
 *
 * <p>수동 전송(T7 MANUAL)이면 커넥터 전송 가드가 배치를 보류하고 SEND 를 {@code WAIT} 로 남긴다. 관리 화면에서 [클라우드 전송]을
 * 누르면 커넥터가 그 배치를 보내고 SEND 를 {@code SUCCESS} · {@code PARTIAL} · {@code FAIL} 로 마감한다. 자동 전송이면 배치 안에서
 * 바로 마감한다. 작업자는 {@link #SUCCESS} 일 때만 WF9 를 부른다 — WF9 정상 응답 뒤 T1 을 폐기 완료로 적기 때문에, 그보다 먼저
 * 부르면 아직 보내지 않은 배치가 전송 대기 · 클라우드 전송 목록에서 빠진다.</p>
 *
 * <p>재처리(같은 EXEC_ID reopen)는 SEND 행을 새 순번으로 더 쌓으므로 <b>가장 뒤 순번</b>을 본다.</p>
 */
@Component
public class Wf9SendGate {

    public static final String SUCCESS = "SUCCESS";
    static final String SEND = "SEND";

    private final LogCollectorClient logCollector;

    public Wf9SendGate(LogCollectorClient logCollector) {
        this.logCollector = logCollector;
    }

    /** @return 그 배치 T2 SEND 마지막 행의 상태 — SEND 행이 없거나 읽지 못하면 null(기다린다) */
    public String sendStatus(String execId) {
        if (execId == null || execId.isBlank()) {
            return null;
        }
        try {
            return lastSendStatus(logCollector.batchDetail(execId.strip()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 배치 상세({@code steps[]})에서 SEND 가장 뒤 순번의 {@code step_sts_cd}. */
    static String lastSendStatus(JsonNode detail) {
        if (detail == null) {
            return null;
        }
        JsonNode steps = detail.path("steps");
        if (!steps.isArray()) {
            steps = detail.path("result").path("steps");   // 응답을 result 로 감싼 경우
        }
        if (!steps.isArray()) {
            return null;
        }
        String found = null;
        int seq = Integer.MIN_VALUE;
        for (JsonNode st : steps) {
            if (!SEND.equals(st.path("step_type_cd").asText(""))) {
                continue;
            }
            int q = st.path("step_seq").asInt(0);
            if (found == null || q >= seq) {
                String s = st.path("step_sts_cd").asText("");
                found = s.isBlank() ? "" : s.strip();
                seq = q;
            }
        }
        return found == null || found.isEmpty() ? null : found;
    }
}
