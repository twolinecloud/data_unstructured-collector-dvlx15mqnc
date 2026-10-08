package egovframework.unstructured.collector.batch;

import com.fasterxml.jackson.databind.JsonNode;
import egovframework.unstructured.collector.batch.schedule.BatchSchedule;
import egovframework.unstructured.collector.batch.schedule.ScheduleTriggers;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.logging.LogCollectorClient;
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

    /** 관리 화면 데이터 구분(C01). */
    public static final String DATA_TYPE = "UNSTRUCTURED";

    private final VoiceCollectService voice;
    private final ImageCollectService image;
    private final VoiceProperties voiceProps;
    private final LogCollectorClient logCollector;
    private final UnstructuredBatchProperties props;

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
     * @throws IllegalArgumentException 끝이 미래 · 끝이 워터마크보다 이르지 않음(이미 그 시각까지 수집함)
     */
    public Plan planRun(LocalDateTime targetToDtm, LocalDateTime now) {
        LocalDateTime to = targetToDtm != null ? targetToDtm : now.minusMinutes(voiceProps.batch().periodicLagMin());
        if (to.isAfter(now)) {
            throw new IllegalArgumentException("targetToDtm 이 미래다: " + to);
        }
        LogCollectorClient.Watermark wm = logCollector.watermark(DATA_TYPE, voiceProps.batch().jobId());
        LocalDateTime from = wm != null ? wm.at() : to.minusDays(Math.max(1, catchupLookbackDays));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("수집할 구간이 없다 — 워터마크 " + from + "(" + wm.execId() + ") 가 targetToDtm "
                    + to + " 이후다. 이미 그 시각까지 수집했다");
        }
        return new Plan(BatchWindow.manual(from, to), ResumeMode.FULL, null, false, "ADMIN", props.batch().includeImage());
    }

    /**
     * 긴급 재처리 — <b>원배치(execId)가 훑었던 구간</b>을 그 단계부터 다시 돈다.
     *
     * <p>예전 재처리 API({@code /api/v1/voice/batches/resume})는 "지금-2일 ~ 지금" 을 봤다. admin 은 특정 배치를 고르므로,
     * 오래된 실패 배치도 빠짐없이 잡으려면 그 배치의 구간(T1 {@code target_from_dtm ~ target_to_dtm})을 써야 한다.
     * 실행 ID 는 <b>새로 받는다</b>(원배치 연결은 TRIGGER_BY {@code ADMIN/reprocess:<원 execId>} 로) — data-collector 는
     * 2026-10-02 부터 원 execId 를 다시 여는(reopen) 체인 방식이다. 비정형은 건마다 독립(멱등)이라 새 ID 로도 결과가 같다.</p>
     *
     * @throws IllegalArgumentException 유형 · 단계 · execId 오류, 원배치를 찾을 수 없음
     */
    public Plan planReprocess(String execId, String dataTypeCd, String stepTypeCd) {
        requireUnstructured(dataTypeCd);
        if (execId == null || execId.isBlank()) {
            throw new IllegalArgumentException("execId 필수 — 재처리할 원배치 실행 ID");
        }
        ResumeMode resume = resumeOf(stepTypeCd);
        JsonNode d = logCollector.batchDetail(execId.trim());
        JsonNode b = d == null ? null : d.path("batch");
        if (b == null || b.isMissingNode() || b.isNull() || !b.hasNonNull("exec_id")) {
            throw new IllegalArgumentException("원배치를 찾을 수 없다 — execId=" + execId
                    + (logCollector.isEnabled() ? "" : " (로그 컬렉터 미연동)"));
        }
        String type = b.path("data_type_cd").asText("");
        if (!DATA_TYPE.equals(type)) {
            throw new IllegalArgumentException("비정형 배치가 아니다 — execId=" + execId + " data_type_cd=" + type);
        }
        boolean testRun = voiceProps.batch().testJobId().equals(b.path("job_id").asText(""));
        if (isImageBatch(b)) {
            // 수용자 이미지 배치(수집 구간이 없다) — 이미지를 다시 돈다. 실패 · 미처리 건만 처리되고 T4 에 새 실행 ID 로 남는다
            LocalDateTime now = LocalDateTime.now().withNano(0);
            return new Plan(BatchWindow.manual(now, now.plusSeconds(1)), resume, execId.trim(), testRun,
                    "ADMIN/reprocess:" + execId.trim(), false, true);
        }
        LocalDateTime from = dtm(b, "target_from_dtm");
        LocalDateTime to = dtm(b, "target_to_dtm");
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("원배치 수집 구간이 없다 — execId=" + execId + " target=" + from + " ~ " + to);
        }
        return new Plan(BatchWindow.manual(from, to), resume, execId.trim(), testRun,
                "ADMIN/reprocess:" + execId.trim(), false);
    }

    /** 원배치가 수용자 이미지 배치인가 — 작업명('수용자 이미지 수집…') 또는 작업 ID({@code IMAGE_COLLECT}). */
    static boolean isImageBatch(JsonNode b) {
        return b.path("job_nm").asText("").startsWith(ImageCollectService.JOB_NM)
                || "IMAGE_COLLECT".equalsIgnoreCase(b.path("job_id").asText(""));
    }

    /**
     * 재처리 단계(C05) → 이어서 할 단계. 비정형은 3단계({@link StepType})라 1:1 이다.
     * 비어 있으면 처음부터 — admin 이 실패 단계를 못 찾으면(단계 기록 없이 죽은 배치) {@code stepTypeCd} 없이 보낸다.
     *
     * @throws IllegalArgumentException 비정형 3단계(COLLECT · ANALYZE · SEND)가 아닌 코드
     */
    public static ResumeMode resumeOf(String stepTypeCd) {
        if (stepTypeCd == null || stepTypeCd.isBlank()) {
            return ResumeMode.FULL;
        }
        StepType step = StepType.parse(stepTypeCd).orElseThrow(() -> new IllegalArgumentException(
                "비정형에 없는 단계(C05): " + stepTypeCd + " — COLLECT · ANALYZE · SEND 만 재처리할 수 있다"));
        return switch (step) {
            case COLLECT -> ResumeMode.FULL;
            case ANALYZE -> ResumeMode.FROM_ANALYZE;
            case SEND -> ResumeMode.FROM_SEND;
        };
    }

    /**
     * 데이터 구분 확인. 비정형이 아니면 400 — admin 은 유형별 주소로 보내므로 다른 유형이 왔다면 주소(DB) 설정 오류다.
     * 조용히 무시하면 admin 은 "요청됨" 으로 알고 아무 일도 일어나지 않는다.
     */
    public static void requireUnstructured(String dataTypeCd) {
        if (!DATA_TYPE.equalsIgnoreCase(dataTypeCd == null ? "" : dataTypeCd.trim())) {
            throw new IllegalArgumentException("비정형 수집기는 dataTypeCd=UNSTRUCTURED 만 받는다: " + dataTypeCd);
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
        }
        if (p.imageOnly()) {
            // 이미지 배치의 긴급 재처리 — 이미지를 다시 돈다(실패 · 미처리 건만 처리 · 새 실행 ID 로 T1 · T2 · T4)
            //   시험(TST) 이미지 배치는 시뮬레이터 SIM 사진이다 — 6번 탭과 같게 SIMIMG 접두 · 수집기 내장 Mock 브로커로
            ImageCollectService.ImageRunResult r = image.run(new ImageCollectService.ImageRunRequest(
                    null, p.testRun() ? egovframework.unstructured.collector.image.sim.ImageSimulationService.PREFIX : null,
                    null, null, false, 0L, p.triggerBy(), p.testRun(), p.testRun(), null), onExecId);
            return "이미지 execId=%s 대상%d 성공%d 실패%d 건너뜀%d (재처리 — 원배치 %s)".formatted(
                    r.execId(), r.total(), r.success(), r.fail(), r.skipped(), p.originExecId());
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
     *   <li><b>음성</b> — 누른 배치까지, 그 이전의 실패/부분성공/취소 배치를 <b>오래된 순</b>으로(정형과 같은 방식). 각 배치는 자기 구간을
     *       다시 돌고 처리된 건은 건너뛴다(멱등). 누른 배치는 요청한 단계부터, 나머지는 전송부터(보존물이 없으면 건마다 앞 단계로).
     *       비정형은 건마다 독립이라 중간 배치가 또 실패해도 <b>멈추지 않고</b> 끝까지 간다</li>
     *   <li><b>수용자 이미지</b> — 날짜 구간이 없어 한 번이 지금 시점 전체 수용자의 최신 사진을 본다(대표형). 그래서 지금까지의 실패 이미지
     *       배치 <b>전부</b>를 오래된 순으로 다시 연다 — 첫 배치가 실제로 처리하고, 나머지는 '이미 최신 사진' 이라 금방 끝나며 원래 실패
     *       행이 해결된 만큼 성공으로 바뀐다</li>
     *   <li>T4 는 같은 (배치 · 수용자 · 파일)을 마지막 상태로 덮어쓰고(로그 컬렉터 V19), T1 은 T4 최종 상태로 마감한다</li>
     * </ul>
     *
     * @return 요약. 체인을 쓸 수 없으면(로그 컬렉터 미연동 · 옛 컬렉터 · 원배치가 실패가 아님) null — 예전처럼 새 실행 ID 로 원배치 구간을 돈다
     */
    private String executeChain(Plan p, BiConsumer<String, Boolean> onExecId) {
        if (!logCollector.isEnabled()) {
            return null;
        }
        List<LogCollectorClient.ChainBatch> chain = logCollector.failedChain(p.originExecId(), p.imageOnly());
        if (chain == null || chain.isEmpty()) {
            log.info("[Unstructured] 재처리 체인 없음(원배치 {} 가 실패가 아니거나 옛 로그 컬렉터) — 새 실행 ID 로 원배치 구간을 돈다",
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
        for (LogCollectorClient.ChainBatch b : chain) {
            String id = b.execId();
            if (!p.imageOnly() && (b.targetFrom() == null || b.targetTo() == null || !b.targetFrom().isBefore(b.targetTo()))) {
                lines.add(id + " 건너뜀(수집 구간 없음)");
                log.warn("[Unstructured] 체인 — {} 는 수집 구간이 없어 건너뛴다", id);
                continue;
            }
            List<JsonNode> failedRows = failedRows(id);
            if (!logCollector.reopenBatch(id, p.triggerBy(), b.targetFrom(), b.targetTo())) {
                lines.add(id + " 다시 열기 실패");
                log.warn("[Unstructured] 체인 — {} 를 다시 열지 못했다(이미 처리됐거나 로그 컬렉터 오류) · 다음 배치로", id);
                continue;
            }
            try {
                if (p.imageOnly()) {
                    ImageCollectService.ImageRunResult r = image.runReopened(new ImageCollectService.ImageRunRequest(
                            null, p.testRun() ? egovframework.unstructured.collector.image.sim.ImageSimulationService.PREFIX : null,
                            null, null, false, 0L, p.triggerBy(), p.testRun(), p.testRun(), null), id, failedRows);
                    lines.add("%s 이미지 대상%d 성공%d 실패%d 건너뜀%d · 원래 실패 %d".formatted(
                            id, r.total(), r.success(), r.fail(), r.skipped(), failedRows.size()));
                } else {
                    ResumeMode mode = id.equals(p.originExecId()) ? p.resume() : ResumeMode.FROM_SEND;
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    failedRows.forEach(f -> keys.add(f.path("rec_file_id").asText("")));
                    VoiceBatchResult v = voice.run(BatchWindow.manual(b.targetFrom(), b.targetTo()), null, p.triggerBy(),
                            p.testRun(), mode, id, voice.defaultWorkers(), null, new VoiceCollectService.Reopened(id, keys));
                    lines.add("%s 음성 %s 대상%d 성공%d 실패%d 건너뜀%d · 원래 실패 %d".formatted(
                            id, mode, v.targetCnt(), v.successCnt(), v.failCnt(), v.skippedCnt(), failedRows.size()));
                }
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

    /** 원배치 T4 의 실패 행 — 다시 돈 뒤 '해결됨' 을 가리는 근거. 못 읽으면 빈 목록(이번에 처리한 건만 덮어쓴다). */
    private List<JsonNode> failedRows(String execId) {
        JsonNode r = logCollector.fileProcs(execId, 5000);
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
