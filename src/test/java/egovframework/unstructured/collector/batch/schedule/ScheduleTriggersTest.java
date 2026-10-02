package egovframework.unstructured.collector.batch.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** admin 스케줄 값 → 트리거 변환 — data-collector 와 같은 규칙 + 주기 첫 발화 지연. */
class ScheduleTriggersTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("FIXED_TIME — HH:mm · HH:mm:ss 둘 다 매일 그 시각 크론")
    void fixedTime() {
        assertThat(ScheduleTriggers.cronExpr("02:00")).isEqualTo("0 0 2 * * *");
        assertThat(ScheduleTriggers.cronExpr("02:00:00")).isEqualTo("0 0 2 * * *");
        assertThat(ScheduleTriggers.cronExpr("23:50")).isEqualTo("0 50 23 * * *");
        assertThat(ScheduleTriggers.of("FIXED_TIME", "03:10", KST)).isInstanceOf(CronTrigger.class);
    }

    @Test
    @DisplayName("INTERVAL_BASED — HH:mm · HH:mm:ss 주기, 등록 즉시 발화하지 않고 한 주기 뒤")
    void intervalBased() {
        assertThat(ScheduleTriggers.period("00:30")).isEqualTo(Duration.ofMinutes(30));
        assertThat(ScheduleTriggers.period("01:00:00")).isEqualTo(Duration.ofHours(1));
        Trigger t = ScheduleTriggers.of("INTERVAL_BASED", "00:30", KST);
        assertThat(t).isInstanceOf(PeriodicTrigger.class);
        Instant before = Instant.now();
        Instant first = t.nextExecution(new SimpleTriggerContext());
        assertThat(first).as("첫 발화는 한 주기(30분) 뒤").isAfterOrEqualTo(before.plus(Duration.ofMinutes(30)).minusSeconds(1));
    }

    @Test
    @DisplayName("잘못된 값 — 형식 · 범위 · 0 주기 · 모르는 유형은 IllegalArgumentException")
    void invalid() {
        assertThatThrownBy(() -> ScheduleTriggers.of("FIXED_TIME", "aa:bb", KST)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTriggers.of("FIXED_TIME", "0200", KST)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTriggers.of("FIXED_TIME", "24:00", KST)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTriggers.of("INTERVAL_BASED", "00:00", KST)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTriggers.of("WEEKLY", "02:00", KST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다음 실행 시각 — 정기는 오늘/내일 그 시각, 주기는 기준 + 주기, 사용 N 이면 없음")
    void nextRun() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 2, 10, 15);
        assertThat(ScheduleTriggers.nextRun(new BatchSchedule("UNSTRUCTURED", "Y", "FIXED_TIME", "02:00"), now, null))
                .isEqualTo(LocalDateTime.of(2026, 10, 3, 2, 0));
        assertThat(ScheduleTriggers.nextRun(new BatchSchedule("UNSTRUCTURED", "Y", "FIXED_TIME", "23:00:00"), now, null))
                .isEqualTo(LocalDateTime.of(2026, 10, 2, 23, 0));
        assertThat(ScheduleTriggers.nextRun(new BatchSchedule("UNSTRUCTURED", "Y", "INTERVAL_BASED", "00:30"), now,
                LocalDateTime.of(2026, 10, 2, 10, 0))).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 30));
        assertThat(ScheduleTriggers.nextRun(new BatchSchedule("UNSTRUCTURED", "N", "FIXED_TIME", "02:00"), now, null)).isNull();
    }
}
