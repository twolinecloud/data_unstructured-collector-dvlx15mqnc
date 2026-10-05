package egovframework.unstructured.collector.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일 단위 자동 생성 스케줄러 — 기본 꺼짐 · 실행 중 ON/OFF 와 PV 파일(재기동 뒤 유지 · 설정값으로 되돌리면 삭제) ·
 * 꺼져 있으면 cron 이 건너뜀 · 한 번에 하나만 · 입력 검증. DB 없이 — 생성 · 판정은 DummyDataServiceTest 가 본다.
 */
class MockDataSchedulerTest {

    @TempDir
    Path dir;

    private static MockDataProperties props(boolean daily) {
        return new MockDataProperties(new MockDataProperties.Dashboard(true),
                new MockDataProperties.Daily(daily, "0 30 1 * * *", List.of(DummyDataType.PHONE), 1, 0, 0, 0, 7));
    }

    @Test
    @DisplayName("기본값 — 설정을 안 주면 일 단위 자동 생성은 꺼져 있다")
    void defaultIsOff() {
        MockDataProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("unstructured.mock", MockDataProperties.class);
        assertThat(p.daily().enabled()).isFalse();
        assertThat(p.daily().cron()).isEqualTo("0 30 1 * * *");
        assertThat(p.daily().retainDays()).isEqualTo(7);

        Map<String, Object> s = new MockDataScheduler(null, p, () -> dir.resolve(MockDataScheduler.FILE)).status();
        assertThat(s).containsEntry("enabled", false).containsEntry("source", "CONFIG").containsEntry("stateFileExists", false);
        assertThat(s.get("nextRun")).isNull();
        assertThat(s.get("nextFire")).asString().endsWith("T01:30");
    }

    @Test
    @DisplayName("ON/OFF — 설정값과 다르면 PV 파일에 남아 재기동 뒤에도 유지되고, 설정값으로 되돌리면 파일이 지워진다")
    void togglePersistsUntilBackToConfig() {
        Path f = dir.resolve(MockDataScheduler.FILE);
        MockDataScheduler s = new MockDataScheduler(null, props(false), () -> f);

        Map<String, Object> on = s.setEnabled(true);
        assertThat(on).containsEntry("enabled", true).containsEntry("source", "UI").containsEntry("changed", true);
        assertThat(on.get("nextRun")).isNotNull();
        assertThat(f).isRegularFile();

        // 재기동 — 파일 값으로 시작
        MockDataScheduler restarted = new MockDataScheduler(null, props(false), () -> f);
        assertThat(restarted.isEnabled()).isTrue();
        assertThat(restarted.status()).containsEntry("source", "UI");

        // 설정값(false)으로 되돌림 — 파일 삭제, 재기동하면 설정값
        assertThat(restarted.setEnabled(false)).containsEntry("enabled", false).containsEntry("source", "CONFIG");
        assertThat(f).doesNotExist();
        assertThat(new MockDataScheduler(null, props(false), () -> f).isEnabled()).isFalse();

        // 설정이 켜짐이면 끈 값이 파일에 남는다
        MockDataScheduler onByConfig = new MockDataScheduler(null, props(true), () -> f);
        assertThat(onByConfig.isEnabled()).isTrue();
        onByConfig.setEnabled(false);
        assertThat(f).isRegularFile();
        assertThat(new MockDataScheduler(null, props(true), () -> f).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("스위치 파일이 깨져 있으면 설정값으로 간다(기동은 멈추지 않는다)")
    void brokenFileFallsBackToConfig() throws Exception {
        Path f = dir.resolve(MockDataScheduler.FILE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{ not json");
        MockDataScheduler s = new MockDataScheduler(null, props(false), () -> f);
        assertThat(s.isEnabled()).isFalse();
        assertThat(s.status()).containsEntry("source", "CONFIG");
    }

    @Test
    @DisplayName("cron — 꺼져 있으면 아무것도 만들지 않고, 켜면 어제 날짜로 만들고 보존 기간을 정리한다")
    void cronRespectsSwitch() {
        DummyDataService dummy = mock(DummyDataService.class);
        when(dummy.generate(any())).thenReturn(Map.of("totals", Map.of("rows", 1)));
        when(dummy.purgeDashboardBefore(any())).thenReturn(Map.of("counts", Map.of("dbRows", 0)));
        MockDataScheduler s = new MockDataScheduler(dummy, props(false));

        s.daily();
        verify(dummy, never()).generate(any());
        assertThat(s.status().get("lastRun")).isNull();

        s.setEnabled(true);
        s.daily();
        verify(dummy).generate(any());
        verify(dummy).purgeDashboardBefore(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) s.status().get("lastRun");
        assertThat(last).containsEntry("trigger", "SCHEDULER");
    }

    @Test
    @DisplayName("즉시 실행 — 대상일 다음 날을 실행일로 본다 · 미래 대상일은 거절 · 스위치와 상관없이 돈다")
    @SuppressWarnings("unchecked")
    void triggerUsesNextDayAsRunDate() {
        DummyDataService dummy = mock(DummyDataService.class);
        when(dummy.generate(any())).thenReturn(Map.of("totals", Map.of("rows", 1)));
        when(dummy.purgeDashboardBefore(any())).thenReturn(Map.of("counts", Map.of("dbRows", 0)));
        when(dummy.dashboardDays(any(), any())).thenReturn(Map.of());
        MockDataScheduler s = new MockDataScheduler(dummy, props(false));

        LocalDate target = LocalDate.of(2026, 10, 1);
        Map<String, Object> r = s.trigger(target);
        assertThat(r).containsEntry("trigger", "MANUAL").containsEntry("targetDate", "2026-10-01")
                .containsEntry("runDate", "2026-10-02").containsEntry("purgeBefore", "2026-09-25").containsEntry("ok", true);
        verify(dummy).purgeDashboardBefore(LocalDate.of(2026, 9, 25));
        assertThat((Map<String, Object>) s.status().get("lastRun")).containsEntry("trigger", "MANUAL");

        assertThatThrownBy(() -> s.trigger(LocalDate.now().plusDays(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.simulate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.simulate(MockDataScheduler.MAX_SIM_DAYS + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대시보드 더미가 꺼져 있으면 즉시 실행 · 시뮬레이션을 거절한다(403)")
    void dashboardOffRejects() {
        MockDataProperties off = new MockDataProperties(new MockDataProperties.Dashboard(false), props(false).daily());
        MockDataScheduler s = new MockDataScheduler(mock(DummyDataService.class), off);
        assertThatThrownBy(() -> s.trigger(null)).isInstanceOf(DummyDataService.DashboardDisabledException.class);
        assertThatThrownBy(() -> s.simulate(null)).isInstanceOf(DummyDataService.DashboardDisabledException.class);
    }

    @Test
    @DisplayName("한 번에 하나만 — 즉시 실행이 도는 동안 시뮬레이션은 BUSY(409)")
    void oneAtATime() throws Exception {
        DummyDataService dummy = mock(DummyDataService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dummy.generate(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of("totals", Map.of());
        });
        when(dummy.purgeDashboardBefore(any())).thenReturn(Map.of("counts", Map.of()));
        when(dummy.dashboardDays(any(), any())).thenReturn(Map.of());
        MockDataScheduler s = new MockDataScheduler(dummy, props(false));

        Thread t = new Thread(() -> s.trigger(null));
        t.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(s.status()).containsEntry("running", true).containsEntry("runningWhat", "즉시 실행");
        assertThatThrownBy(() -> s.simulate(1)).isInstanceOf(MockDataScheduler.BusyException.class);
        release.countDown();
        t.join(5000);
        assertThat(s.status()).containsEntry("running", false);
    }
}
