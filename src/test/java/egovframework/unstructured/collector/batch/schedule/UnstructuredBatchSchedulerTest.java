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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
 * + 최종 안전 스위치({@code VOICE_SCHEDULE_ENABLED}) · 잘못된 값이면 기존 트리거 유지.
 */
class UnstructuredBatchSchedulerTest {

    private TaskScheduler taskScheduler;
    private UnstructuredBatchService service;
    private UnstructuredJobRunner runner;
    private VoiceProperties.Batch batch;
    private FakeAdmin admin;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

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
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
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
}
