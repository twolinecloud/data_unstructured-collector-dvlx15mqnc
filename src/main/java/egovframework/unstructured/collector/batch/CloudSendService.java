package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 클라우드 전송 — 관리자 화면 [클라우드 전송](파이프라인 운영 &gt; 클라우드 전송 제어)이 비정형 음성 배치를 보낸다.
 *
 * <p><b>누른 배치 한 건만 보낸다(단건 독립 전송 · PL 최종 협의 2026-10-19).</b> 배치마다 데이터를 가진 쪽이 보낸다
 * ({@link UnstructuredBatchService#processPending}).</p>
 * <ul>
 *   <li><b>커넥터 보류 장부에 있으면</b> — 커넥터 전송 트리거({@code /api/v1/transmit/holds/{execId}/release}). 전송 가드가 보류한 건
 *       (수동 확인 대기 WAIT)과 커넥터가 받은 뒤 실패한 건(장부에 남아 재시도)이다. 커넥터가 T2 SEND 를 마감한다</li>
 *   <li><b>장부에 없고 배치가 실패/부분성공/취소면</b> — 수집기가 같은 EXEC_ID 로 다시 열어 <b>전송부터</b> 다시 돈다(보존된 전사 · 처리된 건은
 *       멱등 표식이 건너뜀). 커넥터에 닿기 전에 실패한 건(커넥터 5xx · 연결 실패)이다</li>
 * </ul>
 *
 * <p><b>왜 체인을 버렸나</b> — 2026-10-16 부터 누른 배치 <i>이전</i>의 전송 미완료 배치(실패 · 부분성공 · 취소 · 대기)를 오래된 순으로
 * 함께 보냈다. 그중 <b>대기(WAIT)</b> 는 비식별 결과를 사람이 검수하라고 전송 가드가 멈춰 둔 건인데, 체인이 그것까지 끌어다 보내면
 * <b>미검수 유출</b>이 된다. 화면 팝업으로 승인을 받는 방식이었지만 "하위 몇 건이 무엇인지" 를 누르는 사람이 알 수 없어
 * 승인이 형식에 그친다. 그래서 체인을 완전히 폐기하고 <b>고른 한 건만</b> 보내는 쪽으로 되돌렸다.</p>
 *
 * <p>순서가 섞여도 된다 — 비정형(음성)은 파일 단위로 독립해 쌓이는 append-only 구조라 배치 간 선후가 데이터 정합성을 만들지 않는다.
 * 이전 미완료 건은 각자 목록에서 눌러 보내면 된다.</p>
 *
 * <p>접수는 바로 돌려주고(202) 백그라운드에서 보낸다 — 스케줄 · 바로 실행 · 재처리와 같은 실행기 · 같은 잠금이라 동시에 한 건만 돈다.
 * 수용자 이미지는 클라우드로 보내지 않는다(마지막 단계 STORE · 내부 저장만).</p>
 *
 * <p>※ 긴급 재처리의 체인({@code failed-chain?basis=PENDING})은 <b>그대로 둔다</b> — 그쪽은 같은 배치를 처음 단계부터 다시 돌리는
 * 것이고 전송 가드가 여전히 대기를 막아 주므로 이 문제가 없다.</p>
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

    private final LogCollectorClient logCollector;
    private final TransmitHoldClient holds;
    private final UnstructuredBatchService batch;

    /**
     * 실행 계획 — 접수 <b>전에</b> 만들고 검증한다. 잘못된 요청은 실행기에 넣지 않고 400 · 409 로 돌려보낸다.
     *
     * @param execId      보낼 배치(한 건)
     * @param sendStsCd   그 배치의 전송 단계 상태(계획 시점)
     * @param requestedBy 관리자 ID — 커넥터 감사 로그
     * @param triggerBy   다시 여는 배치의 T1 TRIGGER_BY — {@code <관리자>/cloud-send:<배치>}
     */
    public record Plan(String execId, String sendStsCd, String requestedBy, String triggerBy) {

        /** 상태 · 응답에 보일 요약. */
        public Map<String, Object> detail() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataTypeCd", UnstructuredBatchService.DATA_TYPE);
            m.put("originExecId", execId);
            m.put("sendStsCd", sendStsCd);
            return m;
        }
    }

    /**
     * @throws UserFacingRejection 유형 · execId 오류, 배치 없음 · 이미지 · 보낼 것 없음 · 이미 보냄 · 장부를 읽지 못함
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
        if (!UnstructuredBatchService.PENDING_SEND_STS.contains(send)) {
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
        // 이전 배치를 끌어모으지 않는다 — 고른 한 건만 보낸다(단건 독립 전송 · PL 최종 협의 2026-10-19).
        //   전송 체인(failed-chain?basis=SEND) 조회도 하지 않는다. 그 조회가 대기(WAIT) 건까지 끌어와
        //   미검수 유출을 만들던 자리다.
        return new Plan(id, send, who, who + "/cloud-send:" + id);
    }

    /** 계획대로 보낸다(실행기 스레드) — 고른 한 건만. 결과 요약을 돌려준다. */
    public String execute(Plan p, BiConsumer<String, Boolean> onExecId) {
        if (onExecId != null) {
            try {
                onExecId.accept(p.execId(), true);   // 접수 응답 — 누른 배치(새 실행 ID 를 만들지 않는다)
            } catch (RuntimeException e) {
                log.warn("[CloudSend] execId 알림 실패(전송은 계속) — {}", e.getMessage());
            }
        }
        Set<String> held = holds.holds();
        log.info("[CloudSend] 클라우드 전송(단건) — 배치 {} · 커넥터 장부 {}", p.execId(),
                held == null ? (holds.isEnabled() ? "읽지 못함" : "없음(MOCK)") : held.size() + "건");
        String line = batch.processPending(p.execId(), UnstructuredBatchService.PendingMode.CLOUD_SEND, ResumeMode.FROM_SEND, null, null,
                held, p.triggerBy(), p.requestedBy());
        log.info("[CloudSend] {}", line);
        return "클라우드 전송 1건 — " + line;
    }
}
