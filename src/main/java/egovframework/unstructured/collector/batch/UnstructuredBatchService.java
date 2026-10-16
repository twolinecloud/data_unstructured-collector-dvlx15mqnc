package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.batch.schedule.BatchSchedule;
import egovframework.unstructured.collector.batch.schedule.ScheduleTriggers;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
import egovframework.unstructured.collector.common.transfer.TransmitHoldClient;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.common.model.StepType;
import egovframework.unstructured.collector.image.batch.ImageCollectService;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import egovframework.unstructured.collector.voice.batch.VoiceBatchResult;
import egovframework.unstructured.collector.voice.batch.VoiceCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 비정형(UNSTRUCTURED) 오케스트레이터 — admin 연동(스케줄 · 바로 실행 · 긴급 재처리)의 단일 진입점 아래에서 음성·이미지 서비스를 부른다.
 *
 * <p>관리 화면에서 비정형은 한 행이다. 그 한 행의 설정·버튼이 음성과 이미지를 함께 움직인다.
 * 기본은 <b>음성만</b> 돌리고, {@code unstructured.batch.include-image=true} 면 <b>음성 → 이미지 순차</b>로 돈다
 * (이미지는 정형 수집기의 {@code PHOTO_REF} 반영 순서가 미정 · 병렬은 보라미 부하·브로커 동시성 미검증).
 * 긴급 재처리는 로그 컬렉터의 음성 배치(execId)를 다시 돌리는 것이라 음성만 한다.</p>
 *
 * <p>여기는 <b>창을 정하고 검증</b>(plan*)하는 일과 <b>실제로 돌리는</b>(execute) 일만 한다. 비동기 접수·잠금은
 * {@link UnstructuredJobRunner}, 트리거는 {@code UnstructuredBatchScheduler} 가 맡는다. 기존 {@code /api/v1/voice/**} ·
 * {@code /api/v1/image/**} 수동 API 는 그대로 남는다(시뮬레이터·운영 확인용).</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class UnstructuredBatchService {

    /** 화면에 그대로 보이는 시각 표기 — ISO 의 'T' 를 빼고 분까지만. 사용자가 읽을 값이다. */
    private static final DateTimeFormatter USER_DTM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── 화면 안내(400 message) — 공백 포함 60자 이내 · 내부 변수 · 실행 ID 없이. 자세한 사유는 UserFacingRejection.detail 로 로그에 ──
    static final String MSG_TO_FUTURE = "종료 시간은 현재 시각 이전으로 선택해 주세요.";
    static final String MSG_TO_FORMAT = "종료 시간 형식이 올바르지 않습니다. 다시 선택해 주세요.";
    static final String MSG_NO_ORIGIN = "재처리할 배치를 선택해 주세요.";
    static final String MSG_ORIGIN_NOT_FOUND = "선택한 배치를 찾을 수 없습니다. 목록을 새로고침해 주세요.";
    static final String MSG_NOT_UNSTRUCTURED_BATCH = "비정형 배치만 재처리할 수 있습니다.";
    static final String MSG_ORIGIN_NO_WINDOW = "선택한 배치에 수집 구간 정보가 없어 재처리할 수 없습니다.";
    static final String MSG_BAD_STEP = "재처리 단계는 수집 · 분석 · 비식별 · 전송 · 저장 중에서 선택해 주세요.";
    static final String MSG_NOT_UNSTRUCTURED = "비정형 데이터 요청만 처리할 수 있습니다.";
    static final String MSG_BAD_BODY = "요청 형식이 올바르지 않습니다. 다시 시도해 주세요.";
    static final String MSG_ALREADY_DONE = "이미 처리가 끝난 배치입니다. 목록을 새로고침해 주세요.";
    static final String MSG_CHAIN_UNAVAILABLE = "재처리를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    static final String MSG_BUSY = "지금 처리 중인 배치입니다. 잠시 후 다시 시도해 주세요.";

    /** 긴급 재처리로 다시 열 수 있는 원배치 상태 — 로그 컬렉터 reopen 이 받는 상태와 같다. */
    static final java.util.Set<String> REOPENABLE_STS = java.util.Set.of("FAIL", "PARTIAL", "CANCELED");

    /** 다시 보내야 하는 전송 단계 상태 — 실패 · 부분성공 · 취소(커넥터에 닿기 전이든 뒤든). */
    static final java.util.Set<String> RESEND_STS = java.util.Set.of("FAIL", "PARTIAL", "CANCELED");

    /**
     * 미완료로 보는 전송 단계 상태 — {@link #RESEND_STS} + <b>WAIT(전송 대기 · 수동 확인)</b>.
     *
     * <p><b>WAIT 도 순차 실행에 넣는다(PL 협의 2026-10-16)</b> — 화면이 "하위 대기 건을 모두 확인했습니까?" 경고 팝업으로 관리자 승인을
     * 받은 뒤 부른다. 같은 날 오전엔 WAIT 를 빼는 쪽(법적 수동 검수)으로 구현했다가 이 협의로 되돌렸다.</p>
     */
    static final java.util.Set<String> PENDING_SEND_STS = java.util.Set.of("FAIL", "PARTIAL", "CANCELED", "WAIT");

    /**
     * 워터마크 충돌 안내 — 최근 수집 시점 이후를 고르거나, 과거 구간은 긴급 재처리로.
     *
     * <p><b>사용자가 정한 문구 그대로다</b>(2026-10-15). 시각까지 69자라 다른 안내의 60자 상한({@link UserFacingRejection#MAX_MESSAGE_LEN})
     * 밖이다 — 한때 연도를 빼고 '이후로' 로 줄인 60자 축약본을 썼다가, 사용자가 원문을 택해 되돌렸다.</p>
     */
    static String watermarkMessage(LocalDateTime watermark) {
        return "최근 수집 시점(" + USER_DTM.format(watermark) + ") 이후의 시간을 선택해 주세요. 과거 구간은 '긴급 재처리'를 이용 바랍니다.";
    }

    /** 관리 화면 데이터 구분(C01). */
    public static final String DATA_TYPE = "UNSTRUCTURED";

    private final VoiceCollectService voice;
    private final ImageCollectService image;
    private final VoiceProperties voiceProps;
    private final LogCollectorClient logCollector;
    private final UnstructuredBatchProperties props;
    private final TransmitHoldClient holds;

    /** 워터마크가 없을 때(첫 실행) 바로 실행이 되짚는 일수 — {@code /api/v1/voice/batches/on-demand} 와 같은 값. */
    @Value("${voice.batch.catchup-lookback-days:30}")
    private int catchupLookbackDays;

    /** 주기 실행이 자정 직후 거슬러 보는 여유 — 주기 + 이 값(분). 회의(2026-09-11) "앞에서 못 돌린 것까지 다 하고 20분 전" 의 10분. */
    @Value("${voice.batch.periodic-margin-min:10}")
    private int periodicMarginMin = 10;

    /**
     * 실행 계획 — 접수 <b>전에</b> 만들고 검증한다. 잘못된 요청은 실행기에 넣지 않고 400 으로 돌려보낸다.
     *
     * @param window       훑을 구간
     * @param resume       어느 단계부터 — 스케줄·바로 실행은 {@link ResumeMode#FULL}
     * @param originExecId 재처리 원배치(보존물을 찾을 배치). 아니면 null
     * @param testRun      시험 이력(TST)으로 남길지 — 시험 배치를 재처리하면 재처리도 시험으로 남긴다
     * @param triggerBy    T1 TRIGGER_BY
     * @param includeImage 음성 뒤에 이미지도 돌릴지
     * @param imageOnly    이미지만 다시 돈다 — 원배치가 수용자 이미지 배치인 긴급 재처리. 이미지는 단계별 이어서 하기가 없어
     *                     다시 돌리면 실패 · 미처리 건만 처리한다(성공 건은 '변경 없음')
     */
    public record Plan(BatchWindow window, ResumeMode resume, String originExecId, boolean testRun, String triggerBy,
                       boolean includeImage, boolean imageOnly) {

        /** 음성 계획 — 이미지 단독 재처리가 아니다. */
        public Plan(BatchWindow window, ResumeMode resume, String originExecId, boolean testRun, String triggerBy,
                    boolean includeImage) {
            this(window, resume, originExecId, testRun, triggerBy, includeImage, false);
        }

        /** 상태·응답에 보일 요약. */
        public Map<String, Object> detail() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataTypeCd", DATA_TYPE);
            m.put("window", window.toString());
            m.put("from", window.from().toString());
            m.put("to", window.to().toString());
            m.put("resume", resume.name());
            if (originExecId != null) {
                m.put("originExecId", originExecId);
            }
            m.put("testRun", testRun);
            m.put("includeImage", includeImage);
            if (imageOnly) {
                m.put("imageOnly", true);
            }
            return m;
        }
    }

    // ── 계획 ──────────────────────────────────────────────────────────────

    /**
     * 스케줄 발화 — {@code FIXED_TIME} 은 일배치 창(전날 00:00 ~ 오늘 00:00), {@code INTERVAL_BASED} 는 주기 창(당일 00:00 ~ 지금).
     *
     * <p>예전 고정 cron 두 개(일배치 02:00 + 10분 주기)를 관리 화면 값 하나로 합쳤다. 정기면 어제치를 한 번,
     * 주기면 오늘치를 반복해서 줍는다 — 주기 창은 겹치지만 멱등 표식이 이미 처리한 건을 건너뛴다.</p>
     *
     * <p><b>자정 직후 거슬러 보는 폭 = 주기 + {@code periodic-margin-min}(10분)</b>, 최소 {@code periodic-lag-min}(20분)
     * (2026-10-08). 예전에는 주기와 상관없이 20분이라, 주기가 30분 이상이면(관리 화면은 10분 ~ 12시간) 어제 마지막 회차 이후 ~
     * 자정 사이 등록 건이 다음 창에 들어오지 않았다 — 예: 30분 주기 23:30 → 00:00 회차가 23:40 부터 봐 23:30 ~ 23:40 이 빠짐.
     * 다음 회차는 '끝난 시각 + 주기' 에 오므로 배치가 여유(10분)보다 오래 걸리지 않는 한 앞 창과 겹친다.</p>
     */
    public Plan planScheduled(BatchSchedule s, LocalDateTime now) {
        BatchWindow w = BatchSchedule.INTERVAL_BASED.equals(s.execSchedTypeCd())
                ? BatchWindow.periodic(now, periodicLookbackMin(s))
                : BatchWindow.daily(now);
        return new Plan(w, ResumeMode.FULL, null, false, "SCHEDULER", props.batch().includeImage());
    }

    /** 주기 창이 자정 직후 거슬러 보는 분 — max(주기 + 여유, periodic-lag-min). 주기 값이 잘못됐으면 periodic-lag-min. */
    int periodicLookbackMin(BatchSchedule s) {
        int floor = voiceProps.batch().periodicLagMin();
        try {
            long byPeriod = ScheduleTriggers.period(s.schedVal()).toMinutes() + Math.max(0, periodicMarginMin);
            return (int) Math.max(floor, Math.min(byPeriod, Integer.MAX_VALUE));
        } catch (IllegalArgumentException e) {
            return floor;
        }
    }

    /**
     * 바로 실행 — 시작 = 워터마크(로그 컬렉터 T1 의 SUCCESS 배치 {@code MAX(target_to_dtm)}), 끝 = {@code targetToDtm}(없으면 지금-lag).
     *
     * @throws UserFacingRejection 끝이 미래 · 끝이 워터마크보다 이르지 않음(이미 그 시각까지 수집함)
     */
    public Plan planRun(LocalDateTime targetToDtm, LocalDateTime now) {
        LocalDateTime to = targetToDtm != null ? targetToDtm : now.minusMinutes(voiceProps.batch().periodicLagMin());
        if (to.isAfter(now)) {
            throw new UserFacingRejection(MSG_TO_FUTURE, "바로 실행 거절 — targetToDtm 이 미래다: " + to + " (now " + now + ")");
        }
        LogCollectorClient.Watermark wm = logCollector.watermark(DATA_TYPE, voiceProps.batch().jobId());
        LocalDateTime from = wm != null ? wm.at() : to.minusDays(Math.max(1, catchupLookbackDays));
        if (!from.isBefore(to)) {
            // 하루 두 번째 바로 실행이 여기 걸린다. 일배치(새벽)가 이미 어제 구간을 먹어 워터마크가
            //   '오늘 00:00' 이고, 바로 실행 창의 종료 시각을 그대로 두면 창이 빈다.
            //
            //   메시지는 <b>화면에 그대로 뜬다</b>(admin-api 가 400 과 함께 올린다). 사용자가 정한 문구로
            //   바로 할 수 있는 말만 남긴다 — targetToDtm · 배치 ID 같은 내부 값은 뺀다.
            //   대신 추적 수단을 없애지 않도록 <b>자세한 값은 서버 로그에</b> 남긴다(거절 핸들러가 detail 을 WARN 으로).
            throw new UserFacingRejection(watermarkMessage(from),
                    "바로 실행 거절 — 수집할 구간이 없다. 워터마크 " + from + (wm == null ? "" : "(" + wm.execId() + ")")
                            + " · targetToDtm " + to + " (" + (from.equals(to) ? "같음" : "워터마크가 더 나중") + ")");
        }
        return new Plan(BatchWindow.manual(from, to), ResumeMode.FULL, null, false, "ADMIN", props.batch().includeImage());
    }

    /**
     * 긴급 재처리 — <b>원배치(execId)가 훑었던 구간</b>을 그 단계부터 다시 돈다.
     *
     * <p>예전 재처리 API({@code /api/v1/voice/batches/resume})는 "지금-2일 ~ 지금" 을 봤다. admin 은 특정 배치를 고르므로,
     * 오래된 실패 배치도 빠짐없이 잡으려면 그 배치의 구간(T1 {@code target_from_dtm ~ target_to_dtm})을 써야 한다.</p>
     *
     * <p><b>실행 ID 는 새로 받지 않는다 — 원배치를 같은 EXEC_ID 로 다시 연다(reopen 체인, {@link #execute} → executeChain).</b>
     * 해결되면 원배치가 SUCCESS 가 되어 [긴급 재처리] 버튼이 사라지고, 또 실패해도 새 배치가 쌓이지 않는다.
     * 그래서 여기서 미리 확인한다 — 원배치가 실패/부분성공/취소가 아니면, 또는 재처리 체인(로그 컬렉터 failed-chain)을 읽지 못하면
     * 거절한다. 예전에는 그때 새 실행 ID 로 원배치 구간을 돌아 같은 실패가 새 배치로 계속 쌓일 수 있었다(2026-10-16 막음).
     * TRIGGER_BY 에는 {@code <관리자>/reprocess:<누른 배치>} 가 남는다.</p>
     *
     * <p>누른 배치의 전송이 <b>WAIT(수동 확인 대기)여도 받는다</b>(PL 협의 2026-10-16 — 화면 경고 팝업으로 승인). 커넥터 보류분을 먼저 보내고
     * 실패 건을 다시 돈다({@link #processPending}). 지금 실행 · 전송 중이면 409.</p>
     *
     * @throws UserFacingRejection 유형 · 단계 · execId 오류, 원배치를 찾을 수 없음 · 이미 처리됨 · 재처리 체인을 읽지 못함
     */
    public Plan planReprocess(String execId, String dataTypeCd, String stepTypeCd) {
        requireUnstructured(dataTypeCd);
        if (execId == null || execId.isBlank()) {
            throw new UserFacingRejection(MSG_NO_ORIGIN, "재처리 거절 — execId 필수(재처리할 원배치 실행 ID)");
        }
        ResumeMode resume = resumeOf(stepTypeCd);
        JsonNode d = logCollector.batchDetail(execId.trim());
        JsonNode b = d == null ? null : d.path("batch");
        if (b == null || b.isMissingNode() || b.isNull() || !b.hasNonNull("exec_id")) {
            throw new UserFacingRejection(MSG_ORIGIN_NOT_FOUND, "재처리 거절 — 원배치를 찾을 수 없다: execId=" + execId
                    + (logCollector.isEnabled() ? "" : " (로그 컬렉터 미연동)"));
        }
        String type = b.path("data_type_cd").asText("");
        if (!DATA_TYPE.equals(type)) {
            throw new UserFacingRejection(MSG_NOT_UNSTRUCTURED_BATCH,
                    "재처리 거절 — 비정형 배치가 아니다: execId=" + execId + " data_type_cd=" + type);
        }
        String sts = b.path("exec_sts_cd").asText("");
        if ("RUNNING".equals(sts) || "WAIT".equals(sts)) {
            throw new UnstructuredJobRunner.AlreadyRunningException("BATCH", execId.trim(), MSG_BUSY);
        }
        if (!REOPENABLE_STS.contains(sts)) {
            throw new UserFacingRejection(MSG_ALREADY_DONE,
                    "재처리 거절 — 실패/부분성공/취소 배치가 아니다(다시 열 수 없음): execId=" + execId + " exec_sts_cd=" + sts);
        }
        String send = sendStepStatus(d);
        if ("RUNNING".equals(send)) {
            throw new UnstructuredJobRunner.AlreadyRunningException("SENDING", execId.trim(), MSG_BUSY);
        }
        boolean testRun = voiceProps.batch().testJobId().equals(b.path("job_id").asText(""));
        boolean image = isImageBatch(b);
        Plan plan;
        if (image) {
            // 수용자 이미지 배치(수집 구간이 없다) — 지금까지의 실패 이미지 배치를 같은 ID 로 다시 연다(executeChain)
            LocalDateTime now = LocalDateTime.now().withNano(0);
            plan = new Plan(BatchWindow.manual(now, now.plusSeconds(1)), resume, execId.trim(), testRun,
                    "ADMIN/reprocess:" + execId.trim(), false, true);
        } else {
            LocalDateTime from = dtm(b, "target_from_dtm");
            LocalDateTime to = dtm(b, "target_to_dtm");
            if (from == null || to == null || !from.isBefore(to)) {
                throw new UserFacingRejection(MSG_ORIGIN_NO_WINDOW,
                        "재처리 거절 — 원배치 수집 구간이 없다: execId=" + execId + " target=" + from + " ~ " + to);
            }
            plan = new Plan(BatchWindow.manual(from, to), resume, execId.trim(), testRun,
                    "ADMIN/reprocess:" + execId.trim(), false);
        }
        List<LogCollectorClient.ChainBatch> chain = chainOf(execId.trim(), image);
        if (chain == null) {
            throw new UserFacingRejection(MSG_CHAIN_UNAVAILABLE, "재처리 거절 — 재처리 체인(failed-chain)을 읽지 못했다: execId=" + execId
                    + " · 새 실행 ID 로는 돌지 않는다(원배치 reopen 만)");
        }
        if (chain.isEmpty()) {
            throw new UserFacingRejection(MSG_ALREADY_DONE, "재처리 거절 — 재처리 체인이 비었다(그새 처리됨): execId=" + execId);
        }
        return plan;
    }

    /**
     * 전송(SEND) 단계 상태 — 배치 상세(T1 + T2)의 단계 중 SEND, 여럿이면 마지막 순번. 없으면 null.
     * 음성은 SEND 가 한 번이고(청크는 로그 컬렉터가 한 행으로 병합), 이미지는 SEND 대신 STORE 라 null 이다.
     */
    static String sendStepStatus(JsonNode detail) {
        if (detail == null || !detail.path("steps").isArray()) {
            return null;
        }
        String sts = null;
        int seq = Integer.MIN_VALUE;
        for (JsonNode st : detail.path("steps")) {
            if (!"SEND".equals(st.path("step_type_cd").asText(""))) {
                continue;
            }
            int q = st.path("step_seq").asInt(0);
            if (sts == null || q >= seq) {
                sts = st.path("step_sts_cd").asText(null);
                seq = q;
            }
        }
        return sts;
    }

    /**
     * 재처리 체인 — 음성은 <b>미완료 배치 전부</b>(배치 실패/부분성공/취소 <b>또는</b> 전송 실패/부분성공/취소/<b>대기</b>, 로그 컬렉터
     * {@code failed-chain?basis=PENDING}), 누른 배치 이전(포함) · 오래된 순. 이미지는 지금까지의 실패 이미지 배치 전부(전송 단계가 없다).
     *
     * @return 목록. 미연동 · 호출 실패면 null
     */
    List<LogCollectorClient.ChainBatch> chainOf(String execId, boolean image) {
        return image ? logCollector.failedChain(execId, true) : logCollector.pendingChain(execId);
    }

    /** 처리 방식 — 긴급 재처리(보류분 전송 + 실패 건 다시 돌기) · 클라우드 전송(보내기만). */
    enum PendingMode { REPROCESS, CLOUD_SEND }

    /**
     * 순차 체인의 음성 배치 한 건 처리 — 긴급 재처리 · 클라우드 전송이 같이 쓴다. 보내기 직전에 로그 컬렉터로 상태를 다시 본다.
     *
     * <ol>
     *   <li>지금 실행 · 전송 중(T1 RUNNING/WAIT · SEND RUNNING)이거나 상태를 못 읽으면 건너뛴다(중복 방지 · Fail-Safe)</li>
     *   <li><b>커넥터 보류 장부에 있으면 먼저 보낸다</b>(커넥터 전송 트리거 — 전송 대기 WAIT · 커넥터 쪽 실패 재시도). 화면이 하위 대기 건까지
     *       승인받고 부른다(PL 협의). 보내지 못하면 이 배치는 더 손대지 않는다(다시 열면 T2 가 지워져 보류 흔적이 사라진다)</li>
     *   <li>WAIT 인데 장부에 없거나 장부를 못 읽으면 보내지 않는다 — 보류분이 어디 있는지 모른다</li>
     *   <li>그다음 — 재처리: 배치가 실패/부분성공/취소면 같은 EXEC_ID 로 다시 열어 {@code reopenMode} 부터(실패 건만 다시 · 처리된 건 건너뜀).
     *       클라우드 전송: 장부에 없던 전송 실패(커넥터에 닿기 전)만 같은 EXEC_ID 로 전송부터 다시</li>
     * </ol>
     *
     * <p>순서가 <b>보류분 전송 → 다시 돌기</b>인 이유: 다시 돌아 새로 나온 전사는 커넥터 가드를 다시 거쳐야 한다(승인받은 건 지금 보류된 것뿐).
     * 반대로 하면 커넥터가 같은 배치의 보류를 덮어써(장부는 배치당 하나) 승인 안 된 새 전사까지 한 번에 나간다.</p>
     *
     * @param from · to   체인이 준 수집 구간. 없으면 배치 상세의 구간
     * @param held        커넥터 보류 장부(체인 시작 때 한 번 읽음). null 이면 모름(커넥터 미연동이면 장부 없음)
     * @param requestedBy 커넥터 감사 로그에 남길 관리자
     * @return 요약 한 줄
     */
    String processPending(String id, PendingMode mode, ResumeMode reopenMode, LocalDateTime from, LocalDateTime to,
                          java.util.Set<String> held, String triggerBy, String requestedBy) {
        JsonNode d = logCollector.batchDetail(id);
        if (d == null || !d.path("batch").hasNonNull("exec_id")) {
            return id + " 건너뜀(상태 확인 실패)";
        }
        JsonNode b = d.path("batch");
        String t1 = b.path("exec_sts_cd").asText("");
        String send = sendStepStatus(d);
        if ("RUNNING".equals(t1) || "WAIT".equals(t1) || "RUNNING".equals(send)) {
            return id + " 건너뜀(실행 · 전송 중)";
        }
        boolean ledgerKnown = held != null || !holds.isEnabled();
        boolean inLedger = held != null && held.contains(id);
        List<String> parts = new ArrayList<>();
        if (inLedger) {
            TransmitHoldClient.Release r = holds.release(id, requestedBy);
            parts.add("커넥터 전송 %s(HTTP %d · %s · %s)".formatted(r.sent() ? "성공" : "실패", r.httpStatus(), r.stepStsCd(), r.message()));
            if (!r.sent()) {
                return id + " " + parts.get(0) + " — 다시 돌지 않음";
            }
        } else if ("WAIT".equals(send)) {
            return id + " 건너뜀(전송 대기인데 " + (ledgerKnown ? "커넥터 보류 장부에 없음" : "커넥터 보류 장부 확인 실패") + " — 보내지 않음)";
        }
        boolean reopenable = REOPENABLE_STS.contains(t1);
        LocalDateTime f = from != null ? from : dtm(b, "target_from_dtm");
        LocalDateTime t = to != null ? to : dtm(b, "target_to_dtm");
        boolean testRun = voiceProps.batch().testJobId().equals(b.path("job_id").asText(""));
        if (mode == PendingMode.REPROCESS) {
            if (reopenable) {
                parts.add(runReopened(id, f, t, triggerBy, testRun, reopenMode, null));
            }
        } else if (!inLedger && send != null && RESEND_STS.contains(send)) {
            if (!ledgerKnown) {
                parts.add("커넥터 보류 장부 확인 실패 — 다시 보내지 않음");   // 커넥터가 든 건을 한 번 더 보낼 수 있다
            } else if (reopenable) {
                parts.add(runReopened(id, f, t, triggerBy, testRun, ResumeMode.FROM_SEND, null));
            } else {
                parts.add("다시 보낼 경로 없음(배치 " + t1 + " · 전송 " + send + ")");
            }
        }
        if (parts.isEmpty()) {
            return id + " 건너뜀(처리할 것 없음 · 배치 " + t1 + " · 전송 " + send + ")";
        }
        return id + " " + String.join(" → ", parts);
    }

    /** {@code <관리자>/reprocess:<배치>} 같은 TRIGGER_BY 에서 관리자만. */
    static String requesterOf(String triggerBy) {
        if (triggerBy == null || triggerBy.isBlank()) {
            return "admin";
        }
        int i = triggerBy.indexOf('/');
        return i > 0 ? triggerBy.substring(0, i) : triggerBy.trim();
    }

    /** 원배치가 수용자 이미지 배치인가 — 작업명('수용자 이미지 수집…') 또는 작업 ID({@code IMAGE_COLLECT}). */
    static boolean isImageBatch(JsonNode b) {
        return b.path("job_nm").asText("").startsWith(ImageCollectService.JOB_NM)
                || "IMAGE_COLLECT".equalsIgnoreCase(b.path("job_id").asText(""));
    }

    /**
     * 재처리 단계(C05) → 이어서 할 단계. 비정형 4단계({@link StepType}) — DEIDENT 실패는 SEND 와 같이 FROM_SEND 다
     * (보존된 전사를 다시 비식별하고 보낸다 — STT 를 다시 돌리지 않는다).
     * 비어 있으면 처음부터 — admin 이 실패 단계를 못 찾으면(단계 기록 없이 죽은 배치) {@code stepTypeCd} 없이 보낸다.
     *
     * @throws UserFacingRejection 비정형 단계(COLLECT · ANALYZE · DEIDENT · SEND · STORE)가 아닌 코드
     */
    public static ResumeMode resumeOf(String stepTypeCd) {
        if (stepTypeCd == null || stepTypeCd.isBlank()) {
            return ResumeMode.FULL;
        }
        StepType step = StepType.parse(stepTypeCd).orElseThrow(() -> new UserFacingRejection(MSG_BAD_STEP,
                "재처리 거절 — 비정형에 없는 단계(C05): " + stepTypeCd + " — COLLECT · ANALYZE · DEIDENT · SEND · STORE 만 재처리할 수 있다"));
        return switch (step) {
            case COLLECT -> ResumeMode.FULL;
            case ANALYZE -> ResumeMode.FROM_ANALYZE;
            // STORE 는 이미지의 마지막 단계(저장 · 매핑) — 이미지 재처리는 단계와 상관없이 실패 · 미처리 건만 다시 돈다
            case DEIDENT, SEND, STORE -> ResumeMode.FROM_SEND;
        };
    }

    /**
     * 데이터 구분 확인. 비정형이 아니면 400 — admin 은 유형별 주소로 보내므로 다른 유형이 왔다면 주소(DB) 설정 오류다.
     * 조용히 무시하면 admin 은 "요청됨" 으로 알고 아무 일도 일어나지 않는다.
     */
    public static void requireUnstructured(String dataTypeCd) {
        if (!DATA_TYPE.equalsIgnoreCase(dataTypeCd == null ? "" : dataTypeCd.trim())) {
            throw new UserFacingRejection(MSG_NOT_UNSTRUCTURED,
                    "요청 거절 — 비정형 수집기는 dataTypeCd=UNSTRUCTURED 만 받는다: " + dataTypeCd + " (admin 주소 설정 확인)");
        }
    }

    // ── 실행(실행기 스레드) ─────────────────────────────────────────────────

    /** 계획대로 돌린다 — 음성, 그리고 켜져 있으면 이미지. 결과 요약을 돌려준다. */
    public String execute(Plan p, BiConsumer<String, Boolean> onExecId) {
        if (p.originExecId() != null) {
            String chained = executeChain(p, onExecId);
            if (chained != null) {
                return chained;
            }
            // 긴급 재처리는 원배치를 같은 ID 로 다시 여는 것만 한다(2026-10-16). 체인을 쓸 수 없으면 새 실행 ID 로 돌지 않는다 —
            //   예전엔 여기서 새 배치로 원배치 구간을 돌아, 같은 실패가 새 배치로 쌓이고 원배치 버튼은 그대로 남았다.
            //   (planReprocess 가 미리 거르므로 여기 오는 건 접수 뒤 그새 처리됐거나 로그 컬렉터가 끊긴 경우뿐)
            log.warn("[Unstructured] 긴급 재처리 안 함 — 원배치 {} 의 재처리 체인을 쓸 수 없다(새 실행 ID 로 돌지 않는다)", p.originExecId());
            return "재처리 안 함 — 원배치 %s 의 재처리 체인을 쓸 수 없다(이미 처리됐거나 로그 컬렉터 연결 실패) · 새 실행 ID 로 돌지 않는다"
                    .formatted(p.originExecId());
        }
        VoiceBatchResult v = voice.run(p.window(), null, p.triggerBy(), p.testRun(), p.resume(), p.originExecId(),
                voice.defaultWorkers(), onExecId);
        String sum = "음성 " + v.summary();
        if (p.includeImage()) {
            try {
                ImageCollectService.ImageRunResult r = image.run(new ImageCollectService.ImageRunRequest(
                        null, null, null, null, false, 0L, p.triggerBy(), false, false, null));
                sum += " · 이미지 execId=%s 대상%d 성공%d 실패%d 건너뜀%d".formatted(
                        r.execId(), r.total(), r.success(), r.fail(), r.skipped());
            } catch (RuntimeException e) {
                // 음성 결과는 이미 남았다 — 이미지 실패가 음성 성공을 덮지 않게 요약에만 적는다
                log.warn("[Unstructured] 이미지 수집 실패(음성은 끝남) — {}", e.getMessage(), e);
                sum += " · 이미지 실패: " + e.getMessage();
            }
        }
        return sum;
    }

    /**
     * 긴급 재처리 체인(2026-10-06 결정) — 실패 배치마다 버튼이 있고(페이지 · 필터로 버튼이 가려지지 않게), 누르면 <b>같은 EXEC_ID 로
     * 다시 열어</b> 돈다. 해결되면 그 배치는 SUCCESS 가 되어 버튼이 사라진다.
     *
     * <ul>
     *   <li><b>음성</b> — 누른 배치까지, 그 이전의 <b>미완료 배치</b>(배치 실패/부분성공/취소 · 전송 실패/부분성공/취소/<b>대기</b>)를
     *       <b>오래된 순</b>으로. 배치마다 커넥터 보류분을 먼저 보내고 실패 건을 다시 돈다({@link #processPending}). 처리된 건은 건너뛴다(멱등).
     *       누른 배치는 요청한 단계부터, 나머지는 전송부터(보존물이 없으면 건마다 앞 단계로).
     *       비정형은 건마다 독립이라 중간 배치가 또 실패해도 <b>멈추지 않고</b> 끝까지 간다</li>
     *   <li><b>수용자 이미지</b> — 날짜 구간이 없어 한 번이 지금 시점 전체 수용자의 최신 사진을 본다(대표형). 그래서 지금까지의 실패 이미지
     *       배치 <b>전부</b>를 오래된 순으로 다시 연다 — 첫 배치가 실제로 처리하고, 나머지는 '이미 최신 사진' 이라 금방 끝나며 원래 실패
     *       행이 해결된 만큼 성공으로 바뀐다</li>
     *   <li>T4 는 같은 (배치 · 수용자 · 파일)을 마지막 상태로 덮어쓰고(로그 컬렉터 V19), T1 은 T4 최종 상태로 마감한다</li>
     * </ul>
     *
     * @return 요약. 체인을 쓸 수 없으면(로그 컬렉터 미연동 · 호출 실패 · 원배치가 실패가 아님) null — 호출 측은 재처리하지 않는다(새 실행 ID 로 돌지 않음)
     */
    private String executeChain(Plan p, BiConsumer<String, Boolean> onExecId) {
        if (!logCollector.isEnabled()) {
            return null;
        }
        List<LogCollectorClient.ChainBatch> chain = chainOf(p.originExecId(), p.imageOnly());
        if (chain == null || chain.isEmpty()) {
            log.info("[Unstructured] 재처리 체인 없음(원배치 {} 가 실패가 아니거나 로그 컬렉터 호출 실패) — 재처리하지 않는다",
                    p.originExecId());
            return null;
        }
        log.info("[Unstructured] 긴급 재처리 체인 — {} {}건(오래된 순): {}", p.imageOnly() ? "이미지" : "음성", chain.size(),
                chain.stream().map(LogCollectorClient.ChainBatch::execId).toList());
        if (onExecId != null) {
            try {
                onExecId.accept(p.originExecId(), true);   // 접수 응답 — 누른 배치(같은 ID 로 다시 돈다)
            } catch (RuntimeException e) {
                log.warn("[Unstructured] execId 알림 실패(체인은 계속) — {}", e.getMessage());
            }
        }
        List<String> lines = new ArrayList<>();
        java.util.Set<String> held = p.imageOnly() ? null : holds.holds();
        for (LogCollectorClient.ChainBatch b : chain) {
            String id = b.execId();
            if (!p.imageOnly()) {
                // 음성 — 미완료 배치(대기 WAIT 포함)를 오래된 순으로: 보류분 전송 → 실패 건 다시 돌기(processPending)
                ResumeMode mode = id.equals(p.originExecId()) ? p.resume() : ResumeMode.FROM_SEND;
                String line = processPending(id, PendingMode.REPROCESS, mode, b.targetFrom(), b.targetTo(), held, p.triggerBy(),
                        requesterOf(p.triggerBy()));
                lines.add(line);
                log.info("[Unstructured] 체인 — {}", line);
                continue;
            }
            List<JsonNode> failedRows = failedRows(id);
            if (!logCollector.reopenBatch(id, p.triggerBy(), b.targetFrom(), b.targetTo())) {
                lines.add(id + " 다시 열기 실패");
                log.warn("[Unstructured] 체인 — {} 를 다시 열지 못했다(이미 처리됐거나 로그 컬렉터 오류) · 다음 배치로", id);
                continue;
            }
            try {
                ImageCollectService.ImageRunResult r = image.runReopened(new ImageCollectService.ImageRunRequest(
                        null, p.testRun() ? egovframework.unstructured.collector.image.sim.ImageSimulationService.PREFIX : null,
                        null, null, false, 0L, p.triggerBy(), p.testRun(), p.testRun(), null), id, failedRows);
                lines.add("%s 이미지 대상%d 성공%d 실패%d 건너뜀%d · 원래 실패 %d".formatted(
                        id, r.total(), r.success(), r.fail(), r.skipped(), failedRows.size()));
            } catch (RuntimeException e) {
                // 이 배치만 실패로 닫고 다음 배치로 — 비정형은 건마다 독립이라 앞 배치 실패가 뒤를 막을 이유가 없다
                log.error("[Unstructured] 체인 — {} 재처리 실패(다음 배치로 계속) · {}", id, e.getMessage(), e);
                logCollector.finishBatch(id, "FAIL", 0, null, null, null,
                        LogCollectorClient.FileProcReq.errStackOf("재처리 실패 — " + e.getClass().getSimpleName()));
                lines.add(id + " 실패: " + e.getClass().getSimpleName());
            }
        }
        return "긴급 재처리 체인(%s) %d건 — %s".formatted(p.imageOnly() ? "이미지" : "음성", chain.size(), String.join(" / ", lines));
    }

    /**
     * 음성 배치 한 건을 <b>같은 EXEC_ID 로 다시 열어</b> 그 구간을 {@code mode} 부터 다시 돈다 — 긴급 재처리 체인 · 클라우드 전송(재전송)이 같이 쓴다.
     * 처리된 건은 멱등 표식이 건너뛰고, 원래 실패 행은 다시 돈 결과로 덮어쓴다. 실패해도 예외를 밖으로 던지지 않는다
     * (그 배치만 실패로 닫고 요약 한 줄을 돌려준다 — 비정형은 건마다 독립이라 체인의 다음 배치를 막지 않는다).
     *
     * @param failedRows 원배치 T4 실패 행. null 이면 여기서 읽는다
     * @return 요약 한 줄
     */
    String reopenAndRunVoice(String id, LocalDateTime from, LocalDateTime to, String triggerBy, boolean testRun, ResumeMode mode,
                             List<JsonNode> failedRows) {
        return id + " " + runReopened(id, from, to, triggerBy, testRun, mode, failedRows);
    }

    /** {@link #reopenAndRunVoice} 의 본체 — 요약에 배치 ID 를 붙이지 않는다. */
    private String runReopened(String id, LocalDateTime from, LocalDateTime to, String triggerBy, boolean testRun, ResumeMode mode,
                               List<JsonNode> failedRows) {
        if (from == null || to == null || !from.isBefore(to)) {
            log.warn("[Unstructured] {} 는 수집 구간이 없어 건너뛴다", id);
            return "건너뜀(수집 구간 없음)";
        }
        JsonNode t4 = logCollector.fileProcs(id, T4_LIMIT);
        if (t4 == null || !t4.path("rows").isArray()) {
            // 자기 건을 모르면 구간 전체를 훑게 된다 — 다른 배치 건을 가져갈 수 있어 다시 돌지 않는다(Fail-Safe)
            log.warn("[Unstructured] {} 의 T4 를 읽지 못해 다시 돌지 않는다", id);
            return "T4 확인 실패 — 다시 돌지 않음";
        }
        List<JsonNode> rows = failedRows != null ? failedRows : failedOf(t4);
        java.util.Set<String> own = new java.util.HashSet<>();
        t4.path("rows").forEach(n -> own.add(n.path("rec_file_id").asText("")));
        own.remove("");
        if (t4.path("rows").size() >= T4_LIMIT) {
            log.warn("[Unstructured] {} 의 T4 가 {}건 이상 — 자기 건으로 좁히지 않고 구간 전체를 돈다", id, T4_LIMIT);
            own.clear();
        }
        // 다시 열기 전에 — 할 수 있는 일이 있는지 먼저 본다.
        //   원본이 지워진 배치는 다시 열어도 자기 건이 하나도 안 잡혀 아무 일도 못 한다. 그런데 다시 열기가
        //   T1 의 실행일시 · 완료일시 · TRIGGER_BY 를 이미 덮은 뒤라, 화면에는 '방금 0초 실행' 으로 보이면서
        //   실패 건수는 그대로다 — 눌러도 안 되는 것처럼 보이고, 원래 언제 돌았는지도 잃는다.
        if (voice.noReopenTargets(BatchWindow.manual(from, to), own, testRun)) {
            log.warn("[Unstructured] {} 는 자기 건의 원본이 남아 있지 않다 — 다시 열지 않는다(실패 {}건은 재처리로 해소 불가)",
                    id, rows.size());
            return "건너뜀(원본 없음 — 재처리로 해소 불가 · 실패 %d건)".formatted(rows.size());
        }
        if (!logCollector.reopenBatch(id, triggerBy, from, to)) {
            log.warn("[Unstructured] {} 를 다시 열지 못했다(이미 처리됐거나 로그 컬렉터 오류)", id);
            return "다시 열기 실패";
        }
        try {
            java.util.Set<String> keys = new java.util.HashSet<>();
            rows.forEach(f -> keys.add(f.path("rec_file_id").asText("")));
            VoiceBatchResult v = voice.run(BatchWindow.manual(from, to), null, triggerBy, testRun, mode, id,
                    voice.defaultWorkers(), null, new VoiceCollectService.Reopened(id, keys, own));
            return "음성 %s 대상%d 성공%d 실패%d 건너뜀%d · 원래 실패 %d".formatted(
                    mode, v.targetCnt(), v.successCnt(), v.failCnt(), v.skippedCnt(), rows.size());
        } catch (RuntimeException e) {
            // 이 배치만 실패로 닫고 다음 배치로 — 비정형은 건마다 독립이라 앞 배치 실패가 뒤를 막을 이유가 없다
            log.error("[Unstructured] {} 다시 돌기 실패(다음 배치로 계속) · {}", id, e.getMessage(), e);
            logCollector.finishBatch(id, "FAIL", 0, null, null, null,
                    LogCollectorClient.FileProcReq.errStackOf("재처리 실패 — " + e.getClass().getSimpleName()));
            return "실패: " + e.getClass().getSimpleName();
        }
    }

    /** 다시 연 배치의 T4 를 읽는 상한 — 이 이상이면 자기 건으로 좁히지 않는다(다 못 읽었을 수 있다). */
    static final int T4_LIMIT = 5000;

    /** 원배치 T4 의 실패 행 — 다시 돈 뒤 '해결됨' 을 가리는 근거. 못 읽으면 빈 목록(이번에 처리한 건만 덮어쓴다). */
    private List<JsonNode> failedRows(String execId) {
        return failedOf(logCollector.fileProcs(execId, T4_LIMIT));
    }

    private static List<JsonNode> failedOf(JsonNode r) {
        List<JsonNode> out = new ArrayList<>();
        if (r != null && r.path("rows").isArray()) {
            r.path("rows").forEach(n -> {
                if ("FAIL".equals(n.path("proc_sts_cd").asText(""))) {
                    out.add(n);
                }
            });
        }
        return out;
    }

    /** 로그 컬렉터 시각 — ISO({@code 2026-10-01T00:00:00}). 공백 구분도 받는다. */
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
