package egovframework.unstructured.collector.batch.schedule;

import egovframework.unstructured.collector.batch.UnstructuredBatchProperties;
import egovframework.unstructured.collector.batch.UnstructuredBatchService;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 비정형 배치 자동 실행 — admin-api 배치 스케줄 설정(UNSTRUCTURED)을 읽어 {@link TaskScheduler} 에 트리거를 동적으로 건다.
 *
 * <p>data-collector {@code BatchScheduler} 와 같은 구조다(2026-10-02 회의 — 회의록 3장·5-2).</p>
 * <ol>
 *   <li><b>기동 시 조회</b> — {@code GET {admin}/api/batch-schedules/UNSTRUCTURED}</li>
 *   <li><b>5분 재조회</b>({@code unstructured.schedule.refresh-ms}) — 관리 화면 시각이 10분 단위라 그 절반. admin 이 잠시 죽어도 다음 회차에 회복</li>
 *   <li><b>즉시 반영</b> — admin 이 설정을 저장한 뒤 {@code POST /internal/schedule/refresh} 로 값을 실어 보낸다(본문 없으면 재조회)</li>
 * </ol>
 * <p>값이 같으면 아무것도 안 한다(변경 감지 {@code activeYn|유형|값}). 조회가 실패하면 지금 스케줄을 유지한다. 사용 N 이면 트리거를 푼다.</p>
 *
 * <p><b>고정 cron 두 개를 없앴다</b>: 예전에는 yml 의 일배치 02:00 + 10분 주기가 따로 돌았다. 관리 화면은 유형당 한 값이라
 * 그 값 하나로 일원화한다 — {@code FIXED_TIME} 이면 일배치 창, {@code INTERVAL_BASED} 면 주기 창.</p>
 *
 * <p><b>실행 시점</b>(2026-10-08 PL 요청 — 정형과 같게):</p>
 * <ul>
 *   <li>{@code FIXED_TIME} — 매일 그 시각(크론). 바꿔도 곧바로 돌지 않는다</li>
 *   <li>{@code INTERVAL_BASED} — <b>반영 즉시 1회</b>, 그 뒤로는 <b>배치가 끝난 시각 + 주기</b>. 이전 배치가 끝나기 전에 다음 회차가
 *       오지 않는다. 기동 때도 즉시 1회(정형과 같다 — 배포 중 옛 파드와 겹치는 것은 파드 간 잠금이 막는다).
 *       다른 배치(바로 실행 · 재처리 · 다른 파드)가 돌고 있어 건너뛰면 지금 + 주기</li>
 * </ul>
 * <p>Spring {@code PeriodicTrigger} 는 '발화 메서드가 끝난 시각 + 주기' 인데, 발화는 실행기에 넘기고 곧바로 돌아오므로 그대로 쓰면
 * '시작 시각 + 주기' 가 된다. 그래서 주기 실행은 <b>한 번짜리 예약을 이어 건다</b> — 배치가 끝나면 다음 회차를 예약한다.
 * 스케줄러 스레드(1개)를 배치 동안 붙잡지 않으므로 5분 재조회가 멈추지 않는다. 설정이 바뀌면 세대 번호가 올라가
 * 이전 사슬은 다음 회차를 걸지 않는다.</p>
 *
 * <p><b>최종 안전 스위치</b> {@code voice.batch.schedule-enabled}({@code VOICE_SCHEDULE_ENABLED}): 꺼져 있으면 설정은 받아
 * 상태에 보여 주되 <b>트리거를 걸지 않는다</b>. 개발계는 꺼 둔다 — 관리 화면 값이 "사용 Y · 02:00" 이라 켜는 순간
 * 개발계에서 매일 실제 배치가 돈다.</p>
 *
 * <p>발화하면 {@link UnstructuredJobRunner} 에 넘긴다 — 바로 실행·재처리와 같은 단일 스레드·같은 잠금. 무엇이든 돌고 있으면 이번 회차는 건너뛴다.
 * 기동 따라잡기(catch-up)는 하지 않는다(data-collector 기본값과 같음) — 놓친 구간은 바로 실행(워터마크 ~)으로 메운다.</p>
 */
@Log4j2
@Component
public class UnstructuredBatchScheduler {

    /** 반영 출처 — 상태 화면에 보인다. */
    public enum Source { STARTUP, POLL, REFRESH, REFRESH_FETCH }

    /** 반영 결과. */
    public enum Apply { APPLIED, UNCHANGED, IGNORED, INVALID }

    private final AdminBatchScheduleClient client;
    private final UnstructuredBatchService service;
    private final UnstructuredJobRunner runner;
    private final TaskScheduler taskScheduler;
    private final VoiceProperties voiceProps;
    private final ZoneId zone;

    // 상태 — synchronized 메서드 안에서만 바꾼다
    private BatchSchedule active;            // 마지막으로 받은(유효한) 설정. 사용 N 이어도 둔다
    private String currentSpec;
    private ScheduledFuture<?> current;
    private Source appliedFrom;
    private LocalDateTime appliedAt;
    private LocalDateTime lastFetchAt;
    private String lastFetchResult;
    private LocalDateTime lastFireAt;
    private String lastFireResult;
    private String lastError;
    private long generation;            // 반영할 때마다 +1 — 주기 실행 사슬의 세대
    private Duration period;            // INTERVAL_BASED 주기(아니면 null)
    private LocalDateTime nextFireAt;   // INTERVAL_BASED 다음 발화 예정 — 실행 중이면 null(끝난 뒤 정해진다)

    public UnstructuredBatchScheduler(AdminBatchScheduleClient client, UnstructuredBatchService service,
                                      UnstructuredJobRunner runner, TaskScheduler taskScheduler,
                                      VoiceProperties voiceProps, UnstructuredBatchProperties props) {
        this.client = client;
        this.service = service;
        this.runner = runner;
        this.taskScheduler = taskScheduler;
        this.voiceProps = voiceProps;
        this.zone = ZoneId.of(props.schedule().zone());
    }

    /**
     * 기동 시 조회 — 기동을 붙잡지 않게 스케줄러 스레드로 넘긴다. admin 이 안 닿으면(개발 PC · 빌드 에이전트)
     * 연결 타임아웃만큼 기다리는데, 그동안 컨텍스트 기동·테스트가 멈추지 않게 한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        taskScheduler.schedule(() -> reschedule(Source.STARTUP), java.time.Instant.now());
    }

    /** 주기 재조회 — 기동 직후는 {@link #onReady()} 가 했으므로 한 주기 뒤부터. */
    @Scheduled(fixedDelayString = "${unstructured.schedule.refresh-ms:300000}",
            initialDelayString = "${unstructured.schedule.refresh-ms:300000}")
    public void poll() {
        reschedule(Source.POLL);
    }

    /**
     * admin 을 다시 읽어 적용한다 — 기동 · 주기 · 본문 없는 refresh.
     *
     * @return 조회에 성공했는가. 실패(미설정 포함)면 지금 스케줄을 그대로 둔다
     */
    public synchronized boolean reschedule(Source source) {
        if (!client.isEnabled()) {
            lastFetchResult = "DISABLED";
            return false;
        }
        lastFetchAt = now();
        var s = client.fetch(UnstructuredBatchService.DATA_TYPE);
        if (s.isEmpty()) {
            lastFetchResult = "FAILED — 지금 스케줄 유지";
            return false;
        }
        Apply r = applySchedule(s.get(), source);
        lastFetchResult = "OK(" + r + ")";
        return true;
    }

    /**
     * 주어진 설정으로 트리거를 다시 건다 — admin push(refresh 본문) 또는 조회 결과.
     *
     * <p>새 트리거를 <b>먼저 만들어 보고</b> 바꾼다. 값이 잘못됐으면(형식 오류·0 주기) 지금 트리거를 그대로 두고
     * {@link Apply#INVALID} — data-collector 는 기존 트리거를 먼저 해제해 잘못된 값 하나로 스케줄이 멈춘다.</p>
     */
    public synchronized Apply applySchedule(BatchSchedule s, Source source) {
        if (s == null || !UnstructuredBatchService.DATA_TYPE.equalsIgnoreCase(s.dataTypeCd())) {
            return Apply.IGNORED;   // 담당 아님
        }
        if (s.spec().equals(currentSpec)) {
            return Apply.UNCHANGED;
        }
        Trigger cron = null;
        Duration every = null;
        if (s.isActive()) {
            try {
                if (BatchSchedule.INTERVAL_BASED.equals(s.execSchedTypeCd())) {
                    every = ScheduleTriggers.period(s.schedVal());
                } else {
                    cron = ScheduleTriggers.of(s.execSchedTypeCd(), s.schedVal(), zone);
                }
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                log.error("[Schedule] 설정 값 오류 — 지금 스케줄 유지(유형={} 값={}): {}", s.execSchedTypeCd(), s.schedVal(), e.getMessage());
                return Apply.INVALID;
            }
        }
        cancelCurrent();
        active = s;
        currentSpec = s.spec();
        appliedFrom = source;
        appliedAt = now();
        lastError = null;
        if (cron == null && every == null) {
            log.info("[Schedule] 비정형 스케줄 사용 N — 자동 실행 없음 (출처 {})", source);
            return Apply.APPLIED;
        }
        if (!voiceProps.batch().scheduleEnabled()) {
            log.info("[Schedule] 비정형 스케줄 받음 — 유형={} 값={} (출처 {}) · VOICE_SCHEDULE_ENABLED=false 라 트리거는 걸지 않음",
                    s.execSchedTypeCd(), s.schedVal(), source);
            return Apply.APPLIED;
        }
        if (every != null) {
            period = every;
            armInterval(generation, Instant.now());
            log.info("[Schedule] 비정형 스케줄 등록 — 주기 {} · 지금 1회 실행하고 그 뒤로는 끝난 시각 + 주기 (출처 {})",
                    s.schedVal(), source);
            return Apply.APPLIED;
        }
        current = taskScheduler.schedule(this::fire, cron);
        log.info("[Schedule] 비정형 스케줄 등록 — 유형={} 값={} 다음 실행={} (출처 {})", s.execSchedTypeCd(), s.schedVal(),
                ScheduleTriggers.nextRun(s, LocalDateTime.now(zone), appliedAt), source);
        return Apply.APPLIED;
    }

    /** 정기(크론) 발화 — 실행기에 넘기고 곧바로 돌아온다. 무엇이든 돌고 있으면 이번 회차는 건너뛴다. */
    void fire() {
        submitScheduled();
    }

    /**
     * 주기 발화 — 이 세대가 아직 유효할 때만 돈다. 배치가 끝나면(성공 · 실패 모두) 그 시각 + 주기로 다음 회차를 건다.
     * 건너뛰었으면(다른 배치가 실행 중 · 다른 파드 · 꺼짐) 지금 + 주기.
     */
    void fireInterval(long gen) {
        synchronized (this) {
            if (gen != generation) {
                return;   // 그 사이 설정이 바뀌었다 — 새 사슬이 맡는다
            }
            nextFireAt = null;
        }
        UnstructuredJobRunner.Job job = submitScheduled();
        if (job == null) {
            scheduleNext(gen, "건너뜀");
            return;
        }
        job.done().whenComplete((j, e) -> scheduleNext(gen, "끝남 " + (j == null ? "" : j.currentExecId())));
    }

    private synchronized void scheduleNext(long gen, String why) {
        if (gen != generation || period == null) {
            return;   // 설정이 바뀌었거나 풀렸다
        }
        armInterval(gen, Instant.now().plus(period));
        log.info("[Schedule] 주기 실행 {} — 다음 실행 {}", why, nextFireAt);
    }

    /** 한 번짜리 예약 — synchronized 안에서 부른다. */
    private void armInterval(long gen, Instant at) {
        current = taskScheduler.schedule(() -> fireInterval(gen), at);
        nextFireAt = LocalDateTime.ofInstant(at, zone).withNano(0);
    }

    /** 실행기에 넘긴다. @return 접수한 작업. 건너뛰었거나 실패면 null */
    private UnstructuredJobRunner.Job submitScheduled() {
        BatchSchedule s;
        synchronized (this) {
            s = active;
            lastFireAt = now();
        }
        if (s == null || !s.isActive() || !voiceProps.batch().scheduleEnabled()) {
            record("SKIPPED — 스케줄 꺼짐");
            return null;
        }
        try {
            UnstructuredBatchService.Plan plan = service.planScheduled(s, LocalDateTime.now());
            UnstructuredJobRunner.Job job = runner.submit(UnstructuredJobRunner.Kind.SCHEDULE, plan.triggerBy(), plan.detail(),
                    sink -> service.execute(plan, sink::publish));
            record("SUBMITTED — " + job.handle());
            return job;
        } catch (UnstructuredJobRunner.AlreadyRunningException e) {
            log.warn("[Schedule] 이번 회차 건너뜀 — {}", e.getMessage());
            record("SKIPPED — " + e.getMessage());
        } catch (RuntimeException e) {
            // 스케줄러 스레드로 예외가 올라가면 이후 회차가 멈출 수 있다 — 여기서 막는다
            log.error("[Schedule] 발화 처리 실패 — {}", e.getMessage(), e);
            record("FAILED — " + e.getMessage());
        }
        return null;
    }

    /** 상태 표시용 시각 — 트리거와 같은 시간대(한국). 파드 TZ 가 UTC 여도 다음 실행 시각 계산이 어긋나지 않게. */
    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private synchronized void record(String r) {
        lastFireResult = r;
    }

    private void cancelCurrent() {
        generation++;
        period = null;
        nextFireAt = null;
        if (current != null) {
            current.cancel(false);
            current = null;
        }
    }

    /** 지금 트리거가 걸려 있는가 — 사용 Y 이고 안전 스위치가 켜져 있을 때만 true. */
    public synchronized boolean isArmed() {
        return current != null;
    }

    /** 상태 — {@code /internal/batch/status} · {@code /api/v1/voice/status} 가 싣는다. */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataTypeCd", UnstructuredBatchService.DATA_TYPE);
        m.put("adminEnabled", client.isEnabled());
        m.put("adminBaseUrl", client.baseUrl());
        m.put("scheduleEnabled", voiceProps.batch().scheduleEnabled());
        m.put("activeYn", active == null ? null : active.activeYn());
        m.put("execSchedTypeCd", active == null ? null : active.execSchedTypeCd());
        m.put("schedVal", active == null ? null : active.schedVal());
        m.put("armed", current != null);
        LocalDateTime next = current == null ? null
                : period != null ? nextFireAt
                : ScheduleTriggers.nextRun(active, LocalDateTime.now(zone), null);
        m.put("nextRunAt", next == null ? null : next.withNano(0).toString());
        m.put("nextRunNote", period != null && current != null && nextFireAt == null
                ? "실행 중 — 끝난 시각 + 주기(" + active.schedVal() + ")에 다음 실행" : null);
        m.put("appliedFrom", appliedFrom == null ? null : appliedFrom.name());
        m.put("appliedAt", appliedAt == null ? null : appliedAt.withNano(0).toString());
        m.put("lastFetchAt", lastFetchAt == null ? null : lastFetchAt.withNano(0).toString());
        m.put("lastFetchResult", lastFetchResult);
        m.put("lastFireAt", lastFireAt == null ? null : lastFireAt.withNano(0).toString());
        m.put("lastFireResult", lastFireResult);
        m.put("lastError", lastError);
        m.put("dbLock", runner.lockInfo());
        m.put("note", voiceProps.batch().scheduleEnabled() ? null
                : "VOICE_SCHEDULE_ENABLED=false — 설정만 받고 자동 실행은 하지 않는다");
        return m;
    }

    @PreDestroy
    synchronized void shutdown() {
        cancelCurrent();
    }
}
