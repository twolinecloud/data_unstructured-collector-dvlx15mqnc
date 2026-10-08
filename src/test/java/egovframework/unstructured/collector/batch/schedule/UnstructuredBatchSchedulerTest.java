package egovframework.unstructured.collector.batch.schedule;

import egovframework.unstructured.collector.batch.UnstructuredBatchProperties;
import egovframework.unstructured.collector.batch.UnstructuredBatchService;
import egovframework.unstructured.collector.batch.UnstructuredJobRunner;
import egovframework.unstructured.collector.common.config.VoiceProperties;
import egovframework.unstructured.collector.common.model.BatchWindow;
import egovframework.unstructured.collector.voice.batch.ResumeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비정형 스케줄러 — data-collector {@code BatchScheduler} 와 같은 동작(조회 · 재조회 · 변경 감지 · 동적 트리거 · 사용 N 해제)
 * + 최종 안전 스위치({@code VOICE_SCHEDULE_ENABLED}) · 잘못된 값이면 기존 트리거 유지
 * + 주기 실행은 반영 즉시 1회 · 끝난 시각 + 주기 · 건너뛰면 지금 + 주기 · 설정이 바뀌면 이전 사슬은 멈춤(2026-10-08).
 */
class UnstructuredBatchSchedulerTest {

    private TaskScheduler taskScheduler;
    private UnstructuredBatchService service;
    private UnstructuredJobRunner runner;
    private VoiceProperties.Batch batch;
    private FakeAdmin admin;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    /** 한 번짜리 예약(주기 실행) — 발화 시각과 작업. */
    private final List<Instant> onceAt = new ArrayList<>();
    private final List<Runnable> onceTask = new ArrayList<>();

    /** admin-api 대역 — 응답을 바꿔 가며 준다. */
    static final class FakeAdmin extends AdminBatchScheduleClient {
        Optional<BatchSchedule> next = Optional.empty();
        boolean enabled = true;
        int calls;

        FakeAdmin() {
            super(null, "http://admin");
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public Optional<BatchSchedule> fetch(String dataTypeCd) {
            calls++;
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(inv -> {
            ScheduledFuture<?> f = mock(ScheduledFuture.class);
            futures.add(f);
            return f;
        });
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            ScheduledFuture<?> f = mock(ScheduledFuture.class);
            futures.add(f);
            synchronized (onceAt) {
                onceTask.add(inv.getArgument(0));
                onceAt.add(inv.getArgument(1));
            }
            return f;
        });
        service = mock(UnstructuredBatchService.class);
        runner = new UnstructuredJobRunner(() -> null);
        batch = mock(VoiceProperties.Batch.class);
        when(batch.scheduleEnabled()).thenReturn(true);
        admin = new FakeAdmin();
    }

    private UnstructuredBatchScheduler scheduler() {
        VoiceProperties vp = mock(VoiceProperties.class);
        when(vp.batch()).thenReturn(batch);
        UnstructuredBatchProperties props = new UnstructuredBatchProperties(
                new UnstructuredBatchProperties.Admin("http://admin", Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new UnstructuredBatchProperties.Schedule(300000, "Asia/Seoul"),
                new UnstructuredBatchProperties.Batch(false, 200));
        return new UnstructuredBatchScheduler(admin, service, runner, taskScheduler, vp, props);
    }

    private static BatchSchedule s(String active, String type, String val) {
        return new BatchSchedule("UNSTRUCTURED", active, type, val);
    }

    @Test
    @DisplayName("refresh 본문 적용 — 크론 트리거를 건다 · 같은 값이 다시 오면 아무것도 안 한다")
    void applyAndUnchanged() {
        UnstructuredBatchScheduler sch = scheduler();
        assertThat(sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.REFRESH))
                .isEqualTo(UnstructuredBatchScheduler.Apply.APPLIED);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(CronTrigger.class));
        assertThat(sch.isArmed()).isTrue();

        assertThat(sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.POLL))
                .isEqualTo(UnstructuredBatchScheduler.Apply.UNCHANGED);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));

        Map<String, Object> snap = sch.snapshot();
        assertThat(snap.get("schedVal")).isEqualTo("02:00");
        assertThat(snap.get("appliedFrom")).isEqualTo("REFRESH");
        assertThat(snap.get("nextRunAt")).isNotNull();
    }

    @Test
    @DisplayName("값이 바뀌면 옛 트리거를 풀고 새로 건다 · 다른 유형은 무시")
    void changeAndIgnore() {
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.STARTUP);
        sch.applySchedule(s("Y", "INTERVAL_BASED", "00:30:00"), UnstructuredBatchScheduler.Source.REFRESH);
        verify(futures.get(0)).cancel(false);
        assertThat(futures).hasSize(2);

        assertThat(sch.applySchedule(new BatchSchedule("STRUCTURED", "Y", "FIXED_TIME", "03:10"),
                UnstructuredBatchScheduler.Source.REFRESH)).isEqualTo(UnstructuredBatchScheduler.Apply.IGNORED);
        assertThat(futures).hasSize(2);
    }

    @Test
    @DisplayName("사용 N — 트리거를 풀고 다시 걸지 않는다")
    void inactiveReleases() {
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.STARTUP);
        assertThat(sch.applySchedule(s("N", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.REFRESH))
                .isEqualTo(UnstructuredBatchScheduler.Apply.APPLIED);
        verify(futures.get(0)).cancel(false);
        assertThat(futures).hasSize(1);
        assertThat(sch.isArmed()).isFalse();
        assertThat(sch.snapshot().get("activeYn")).isEqualTo("N");
    }

    @Test
    @DisplayName("잘못된 값 — INVALID, 지금 트리거는 그대로(풀지 않는다)")
    void invalidKeepsCurrent() {
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.STARTUP);
        assertThat(sch.applySchedule(s("Y", "INTERVAL_BASED", "00:00"), UnstructuredBatchScheduler.Source.REFRESH))
                .isEqualTo(UnstructuredBatchScheduler.Apply.INVALID);
        verify(futures.get(0), never()).cancel(false);
        assertThat(sch.isArmed()).isTrue();
        assertThat(sch.snapshot().get("schedVal")).isEqualTo("02:00");
        assertThat(sch.snapshot().get("lastError")).asString().contains("0 이하");
    }

    @Test
    @DisplayName("admin 조회 실패 — 지금 스케줄 유지 · 조회 성공이면 POLL 로 반영 · 주소 미설정이면 조회 안 함")
    void fetchFailureKeeps() {
        UnstructuredBatchScheduler sch = scheduler();
        admin.next = Optional.of(s("Y", "FIXED_TIME", "02:00"));
        assertThat(sch.reschedule(UnstructuredBatchScheduler.Source.POLL)).isTrue();
        assertThat(sch.snapshot().get("appliedFrom")).isEqualTo("POLL");

        admin.next = Optional.empty();   // admin 장애
        assertThat(sch.reschedule(UnstructuredBatchScheduler.Source.POLL)).isFalse();
        verify(futures.get(0), never()).cancel(false);
        assertThat(sch.isArmed()).isTrue();
        assertThat(sch.snapshot().get("lastFetchResult")).asString().startsWith("FAILED");

        admin.enabled = false;
        int before = admin.calls;
        assertThat(sch.reschedule(UnstructuredBatchScheduler.Source.POLL)).isFalse();
        assertThat(admin.calls).isEqualTo(before);
    }

    @Test
    @DisplayName("VOICE_SCHEDULE_ENABLED=false — 설정은 받아 상태에 보이되 트리거를 걸지 않고, 발화해도 실행하지 않는다")
    void safetySwitchOff() {
        when(batch.scheduleEnabled()).thenReturn(false);
        UnstructuredBatchScheduler sch = scheduler();
        assertThat(sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.REFRESH))
                .isEqualTo(UnstructuredBatchScheduler.Apply.APPLIED);
        assertThat(sch.applySchedule(s("Y", "INTERVAL_BASED", "00:10"), UnstructuredBatchScheduler.Source.REFRESH))
                .isEqualTo(UnstructuredBatchScheduler.Apply.APPLIED);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.REFRESH);
        Map<String, Object> snap = sch.snapshot();
        assertThat(snap.get("schedVal")).isEqualTo("02:00");
        assertThat(snap.get("armed")).isEqualTo(false);
        assertThat(snap.get("note")).asString().contains("VOICE_SCHEDULE_ENABLED=false");

        sch.fire();
        verify(service, never()).planScheduled(any(), any());
        assertThat(runner.status().get("status")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("발화 — 실행기에 넘겨 배치를 돌린다 · 이미 돌고 있으면 이번 회차는 건너뛴다")
    void fireSubmitsOrSkips() throws Exception {
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.STARTUP);
        UnstructuredBatchService.Plan plan = new UnstructuredBatchService.Plan(
                BatchWindow.daily(LocalDateTime.now()), ResumeMode.FULL, null, false, "SCHEDULER", false);
        when(service.planScheduled(any(), any())).thenReturn(plan);
        CountDownLatch release = new CountDownLatch(1);
        when(service.execute(eq(plan), any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return "ok";
        });

        sch.fire();
        verify(service, timeout(2000)).execute(eq(plan), any());
        assertThat(sch.snapshot().get("lastFireResult")).asString().startsWith("SUBMITTED");

        sch.fire();   // 첫 회차가 아직 돈다
        assertThat(sch.snapshot().get("lastFireResult")).asString().startsWith("SKIPPED");
        release.countDown();
    }

    private UnstructuredBatchService.Plan periodicPlan() {
        UnstructuredBatchService.Plan plan = new UnstructuredBatchService.Plan(
                BatchWindow.periodic(LocalDateTime.now(), 20), ResumeMode.FULL, null, false, "SCHEDULER", false);
        when(service.planScheduled(any(), any())).thenReturn(plan);
        return plan;
    }

    private Instant onceAt(int i) {
        synchronized (onceAt) {
            return onceAt.get(i);
        }
    }

    private int onceCount() {
        synchronized (onceAt) {
            return onceAt.size();
        }
    }

    @Test
    @DisplayName("주기 실행 — 반영(기동 포함) 즉시 1회 · 배치가 끝난 시각 + 주기로 다음 회차 · 실행 중에는 다음 시각이 없다")
    void intervalRunsNowThenEndPlusPeriod() throws Exception {
        UnstructuredBatchService.Plan plan = periodicPlan();
        CountDownLatch release = new CountDownLatch(1);
        when(service.execute(eq(plan), any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return "ok";
        });
        UnstructuredBatchScheduler sch = scheduler();
        Instant applied = Instant.now();
        sch.applySchedule(s("Y", "INTERVAL_BASED", "00:30"), UnstructuredBatchScheduler.Source.STARTUP);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(onceCount()).isEqualTo(1);
        assertThat(onceAt(0)).as("첫 회차는 지금").isBetween(applied.minusSeconds(1), Instant.now().plusSeconds(1));

        onceTask.get(0).run();   // 발화 — 배치가 돌기 시작한다
        verify(service, timeout(2000)).execute(eq(plan), any());
        Map<String, Object> running = sch.snapshot();
        assertThat(running.get("nextRunAt")).as("끝나야 정해진다").isNull();
        assertThat(running.get("nextRunNote")).asString().contains("끝난 시각 + 주기");
        Thread.sleep(300);
        assertThat(onceCount()).as("배치가 끝나기 전에는 다음 회차를 걸지 않는다").isEqualTo(1);

        Instant end = Instant.now();
        release.countDown();
        await().atMost(3, TimeUnit.SECONDS).until(() -> onceCount() == 2);
        assertThat(onceAt(1)).as("끝난 시각 + 30분").isBetween(end.plus(Duration.ofMinutes(30)).minusSeconds(1),
                Instant.now().plus(Duration.ofMinutes(30)).plusSeconds(1));
        assertThat(sch.snapshot().get("nextRunAt")).isNotNull();
    }

    @Test
    @DisplayName("주기 실행 — 다른 배치가 돌고 있어 건너뛰면 지금 + 주기로 다음 회차")
    void intervalSkipReschedules() throws Exception {
        periodicPlan();
        CountDownLatch hold = new CountDownLatch(1);
        runner.submit(UnstructuredJobRunner.Kind.RUN, "ADMIN", null, sink -> {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "busy";
        });
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "INTERVAL_BASED", "00:10"), UnstructuredBatchScheduler.Source.REFRESH);
        Instant fired = Instant.now();
        onceTask.get(0).run();
        assertThat(sch.snapshot().get("lastFireResult")).asString().startsWith("SKIPPED");
        assertThat(onceCount()).isEqualTo(2);
        assertThat(onceAt(1)).isBetween(fired.plus(Duration.ofMinutes(10)).minusSeconds(1),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(1));
        verify(service, never()).execute(any(), any());
        hold.countDown();
    }

    @Test
    @DisplayName("주기 실행 — 설정이 바뀌면 이전 사슬은 발화해도 돌지 않고, 돌던 배치가 끝나도 다음 회차를 걸지 않는다")
    void staleChainStops() throws Exception {
        UnstructuredBatchService.Plan plan = periodicPlan();
        CountDownLatch release = new CountDownLatch(1);
        when(service.execute(eq(plan), any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return "ok";
        });
        UnstructuredBatchScheduler sch = scheduler();
        sch.applySchedule(s("Y", "INTERVAL_BASED", "00:10"), UnstructuredBatchScheduler.Source.REFRESH);
        Runnable first = onceTask.get(0);
        sch.applySchedule(s("Y", "INTERVAL_BASED", "00:20"), UnstructuredBatchScheduler.Source.REFRESH);
        verify(futures.get(0)).cancel(false);
        first.run();   // 이미 떠난 옛 예약
        verify(service, never()).planScheduled(any(), any());

        onceTask.get(1).run();   // 새 사슬 — 돈다
        verify(service, timeout(2000)).execute(eq(plan), any());
        sch.applySchedule(s("Y", "FIXED_TIME", "02:00"), UnstructuredBatchScheduler.Source.REFRESH);   // 도는 중에 정기로 바뀜
        release.countDown();
        await().atMost(3, TimeUnit.SECONDS).until(() -> !runner.isRunning());
        Thread.sleep(200);
        assertThat(onceCount()).as("끝나도 옛 주기 사슬은 다음 회차를 걸지 않는다").isEqualTo(2);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(CronTrigger.class));
    }
}
