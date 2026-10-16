package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 클라우드 전송 — 관리자 화면 [클라우드 전송](파이프라인 운영 &gt; 클라우드 전송 제어)이 비정형 음성 배치를 보낸다.
 *
 * <p><b>누른 배치와 그 이전의 전송 실패 배치</b>를 오래된 순으로 보낸다(긴급 재처리 체인과 같은 방식 · 2026-10-16).
 * 배치마다 데이터를 가진 쪽이 보낸다.</p>
 * <ul>
 *   <li><b>커넥터 보류 장부에 있으면</b> — 커넥터 전송 트리거({@code /api/v1/transmit/holds/{execId}/release}). 전송 가드가 보류한 건
 *       (수동 확인 대기 WAIT)과 커넥터가 받은 뒤 실패한 건(장부에 남아 재시도)이다. 커넥터가 T2 SEND 를 마감한다</li>
 *   <li><b>장부에 없고 배치가 실패/부분성공/취소면</b> — 수집기가 같은 EXEC_ID 로 다시 열어 <b>전송부터</b> 다시 돈다(보존된 전사 · 처리된 건은
 *       멱등 표식이 건너뜀). 커넥터에 닿기 전에 실패한 건(커넥터 5xx · 연결 실패)이다</li>
 * </ul>
 *
 * <p><b>전송 대기(WAIT)는 법적 수동 검수 대기다</b> — 관리자가 <b>그 배치</b>를 골라 누를 때만 보낸다. 이전 배치를 자동으로 함께 보낼 때는
 * 절대 넣지 않는다(로그 컬렉터 전송 체인이 빼고, 보내기 직전에 여기서 다시 본다). 실행 중 · 전송 중인 배치도 건너뛴다(중복 방지).</p>
 *
 * <p>접수는 바로 돌려주고(202) 백그라운드에서 보낸다 — 스케줄 · 바로 실행 · 재처리와 같은 실행기 · 같은 잠금이라 동시에 한 건만 돈다.
 * 수용자 이미지는 클라우드로 보내지 않는다(마지막 단계 STORE · 내부 저장만).</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class CloudSendService {

    // ── 화면 안내(400 message) — 공백 포함 60자 이내 · 내부 변수 · 실행 ID 없이 ──
    static final String MSG_NO_TARGET = "전송할 배치를 선택해 주세요.";
    static final String MSG_NOT_UNSTRUCTURED_BATCH = "비정형 배치만 전송할 수 있습니다.";
    static final String MSG_IMAGE_NOT_SENT = "수용자 이미지는 클라우드로 전송하지 않습니다.";
    static final String MSG_NOTHING_TO_SEND = "전송할 데이터가 없는 배치입니다. 긴급 재처리를 이용해 주세요.";
    static final String MSG_SEND_DONE = "이미 전송이 끝난 배치입니다. 목록을 새로고침해 주세요.";
    static final String MSG_HOLD_NOT_FOUND = "전송 대기 정보를 찾지 못했습니다. 목록을 새로고침해 주세요.";
    static final String MSG_CANNOT_RESEND = "다시 보낼 수 없는 배치입니다. 긴급 재처리를 이용해 주세요.";
    static final String MSG_SEND_UNAVAILABLE = "전송을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /** 다시 보낼 수 있는 전송 단계 상태(누른 배치). WAIT 는 누른 배치일 때만 — 관리자 승인이다. */
    static final Set<String> SENDABLE_STS = Set.of("WAIT", "FAIL", "PARTIAL", "CANCELED");

    private final LogCollectorClient logCollector;
    private final TransmitHoldClient holds;
    private final UnstructuredBatchService batch;
    private final VoiceProperties voiceProps;

    /**
     * 실행 계획 — 접수 <b>전에</b> 만들고 검증한다. 잘못된 요청은 실행기에 넣지 않고 400 · 409 로 돌려보낸다.
     *
     * @param execId       누른 배치
     * @param sendStsCd    누른 배치의 전송 단계 상태(계획 시점)
     * @param predecessors 함께 보낼 이전 전송 실패 배치(오래된 순 · 누른 배치 제외 · WAIT/RUNNING 제외)
     * @param requestedBy  관리자 ID — 커넥터 감사 로그
     * @param triggerBy    다시 여는 배치의 T1 TRIGGER_BY — {@code <관리자>/cloud-send:<누른 배치>}
     */
    public record Plan(String execId, String sendStsCd, List<String> predecessors, String requestedBy, String triggerBy) {

        /** 상태 · 응답에 보일 요약. */
        public Map<String, Object> detail() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataTypeCd", UnstructuredBatchService.DATA_TYPE);
            m.put("originExecId", execId);
            m.put("sendStsCd", sendStsCd);
            m.put("chain", chain());
            return m;
        }

        /** 보낼 순서 — 이전 배치(오래된 순) 다음 누른 배치. */
        public List<String> chain() {
            List<String> all = new ArrayList<>(predecessors);
            all.add(execId);
            return all;
        }
    }

    /**
     * @throws UserFacingRejection 유형 · execId 오류, 배치 없음 · 이미지 · 보낼 것 없음 · 이미 보냄 · 장부/체인을 읽지 못함
     * @throws UnstructuredJobRunner.AlreadyRunningException 그 배치가 지금 실행 · 전송 중(409)
     */
    public Plan plan(String execId, String dataTypeCd, String requestedBy) {
        UnstructuredBatchService.requireUnstructured(dataTypeCd);
        if (execId == null || execId.isBlank()) {
            throw new UserFacingRejection(MSG_NO_TARGET, "클라우드 전송 거절 — execId 필수");
        }
        String id = execId.trim();
        String who = requestedBy == null || requestedBy.isBlank() ? "admin" : requestedBy.trim();
        JsonNode d = logCollector.batchDetail(id);
        JsonNode b = d == null ? null : d.path("batch");
        if (b == null || b.isMissingNode() || !b.hasNonNull("exec_id")) {
            throw new UserFacingRejection(UnstructuredBatchService.MSG_ORIGIN_NOT_FOUND, "클라우드 전송 거절 — 배치를 찾을 수 없다: execId=" + id
                    + (logCollector.isEnabled() ? "" : " (로그 컬렉터 미연동)"));
        }
        String type = b.path("data_type_cd").asText("");
        if (!UnstructuredBatchService.DATA_TYPE.equals(type)) {
            throw new UserFacingRejection(MSG_NOT_UNSTRUCTURED_BATCH, "클라우드 전송 거절 — 비정형 배치가 아니다: execId=" + id + " data_type_cd=" + type);
        }
        if (UnstructuredBatchService.isImageBatch(b)) {
            throw new UserFacingRejection(MSG_IMAGE_NOT_SENT, "클라우드 전송 거절 — 수용자 이미지 배치(마지막 단계 STORE · 내부 저장): execId=" + id);
        }
        String t1 = b.path("exec_sts_cd").asText("");
        String send = UnstructuredBatchService.sendStepStatus(d);
        if ("RUNNING".equals(t1) || "WAIT".equals(t1)) {
            throw new UnstructuredJobRunner.AlreadyRunningException("BATCH", id, UnstructuredBatchService.MSG_BUSY);
        }
        if ("RUNNING".equals(send)) {
            throw new UnstructuredJobRunner.AlreadyRunningException("SENDING", id, UnstructuredBatchService.MSG_BUSY);
        }
        if (send == null) {
            throw new UserFacingRejection(MSG_NOTHING_TO_SEND, "클라우드 전송 거절 — 전송 단계가 없다(전송 전에 실패): execId=" + id + " exec_sts_cd=" + t1);
        }
        if (!SENDABLE_STS.contains(send)) {
            throw new UserFacingRejection(MSG_SEND_DONE, "클라우드 전송 거절 — 전송 단계가 " + send + ": execId=" + id);
        }
        Set<String> held = holds.holds();
        if (holds.isEnabled() && held == null) {
            throw new UserFacingRejection(MSG_SEND_UNAVAILABLE, "클라우드 전송 거절 — 커넥터 보류 장부를 읽지 못했다: execId=" + id);
        }
        boolean inLedger = held != null && held.contains(id);
        if ("WAIT".equals(send) && !inLedger) {
            throw new UserFacingRejection(MSG_HOLD_NOT_FOUND, "클라우드 전송 거절 — 전송 대기인데 커넥터 보류 장부에 없다: execId=" + id
                    + (holds.isEnabled() ? "" : " (커넥터 미연동 — MOCK)"));
        }
        if (!inLedger && !UnstructuredBatchService.REOPENABLE_STS.contains(t1)) {
            throw new UserFacingRejection(MSG_CANNOT_RESEND, "클라우드 전송 거절 — 장부에 없고 다시 열 수도 없다: execId=" + id
                    + " exec_sts_cd=" + t1 + " send=" + send);
        }
        List<LogCollectorClient.ChainBatch> chain = logCollector.sendChain(id);
        if (chain == null) {
            throw new UserFacingRejection(MSG_SEND_UNAVAILABLE, "클라우드 전송 거절 — 전송 체인(failed-chain?basis=SEND)을 읽지 못했다: execId=" + id);
        }
        List<String> before = new ArrayList<>();
        for (LogCollectorClient.ChainBatch c : chain) {
            // 정책 — 이전 배치를 자동으로 함께 보낼 때 전송 대기(WAIT · 법적 수동 검수)와 전송 중은 넣지 않는다.
            //   basis 를 모르는 옛 로그 컬렉터는 배치 체인(sendStsCd 없음)을 주므로, 보내기 직전에 다시 본다(execute)
            if (c.execId() == null || c.execId().equals(id)
                    || (c.sendStsCd() != null && UnstructuredBatchService.NOT_AUTO_SEND_STS.contains(c.sendStsCd()))) {
                continue;
            }
            before.add(c.execId());
        }
        return new Plan(id, send, List.copyOf(before), who, who + "/cloud-send:" + id);
    }

    /** 계획대로 보낸다(실행기 스레드) — 이전 배치부터 오래된 순, 마지막에 누른 배치. 결과 요약을 돌려준다. */
    public String execute(Plan p, BiConsumer<String, Boolean> onExecId) {
        if (onExecId != null) {
            try {
                onExecId.accept(p.execId(), true);   // 접수 응답 — 누른 배치(새 실행 ID 를 만들지 않는다)
            } catch (RuntimeException e) {
                log.warn("[CloudSend] execId 알림 실패(전송은 계속) — {}", e.getMessage());
            }
        }
        Set<String> held = holds.holds();
        log.info("[CloudSend] 클라우드 전송 — 누른 배치 {} · 함께 보낼 이전 배치 {} · 커넥터 장부 {}", p.execId(), p.predecessors(),
                held == null ? (holds.isEnabled() ? "읽지 못함" : "없음(MOCK)") : held.size() + "건");
        List<String> lines = new ArrayList<>();
        for (String id : p.chain()) {
            String line = sendOne(id, id.equals(p.execId()), held, p);
            lines.add(line);
            log.info("[CloudSend] {}", line);
        }
        return "클라우드 전송 %d건 — %s".formatted(lines.size(), String.join(" / ", lines));
    }

    /** 한 배치 보내기 — 보내기 직전 상태를 다시 보고, 데이터를 가진 쪽(커넥터 장부 · 수집기 보존 전사)으로 보낸다. */
    String sendOne(String id, boolean target, Set<String> held, Plan p) {
        JsonNode d = logCollector.batchDetail(id);
        if (d == null || !d.path("batch").hasNonNull("exec_id")) {
            return id + " 건너뜀(상태 확인 실패)";
        }
        JsonNode b = d.path("batch");
        String t1 = b.path("exec_sts_cd").asText("");
        String send = UnstructuredBatchService.sendStepStatus(d);
        if ("RUNNING".equals(t1) || "WAIT".equals(t1) || "RUNNING".equals(send)) {
            return id + " 건너뜀(실행 · 전송 중)";
        }
        if ("WAIT".equals(send) && !target) {
            return id + " 건너뜀(전송 대기 · 수동 확인 — 자동으로 보내지 않는다)";
        }
        if (send == null || !SENDABLE_STS.contains(send)) {
            return id + " 건너뜀(보낼 것 없음 · 전송 " + send + ")";
        }
        if (held != null && held.contains(id)) {
            TransmitHoldClient.Release r = holds.release(id, p.requestedBy());
            return "%s 커넥터 전송 %s(HTTP %d · %s · %s)".formatted(id, r.sent() ? "성공" : "실패", r.httpStatus(), r.stepStsCd(), r.message());
        }
        if (holds.isEnabled() && held == null) {
            // 장부를 모르면 수집기가 다시 보내지 않는다 — 커넥터가 들고 있는 건을 한 번 더 보내게 될 수 있다
            return id + " 건너뜀(커넥터 보류 장부 확인 실패)";
        }
        if ("WAIT".equals(send)) {
            return id + " 건너뜀(보류 장부에 없음 — 보내지 않는다)";
        }
        if (!UnstructuredBatchService.REOPENABLE_STS.contains(t1)) {
            return id + " 건너뜀(다시 보낼 경로 없음 — 배치 " + t1 + " · 전송 " + send + ")";
        }
        boolean testRun = voiceProps.batch().testJobId().equals(b.path("job_id").asText(""));
        return batch.reopenAndRunVoice(id, dtm(b, "target_from_dtm"), dtm(b, "target_to_dtm"), p.triggerBy(), testRun,
                ResumeMode.FROM_SEND, null);
    }

    private static LocalDateTime dtm(JsonNode b, String f) {
        JsonNode v = b.path(f);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.asText().trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
