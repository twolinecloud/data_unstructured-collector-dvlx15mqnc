package egovframework.unstructured.collector.batch.schedule;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 스케줄 설정({@code execSchedTypeCd + schedVal})을 Spring {@link Trigger} 로 바꾼다 — data-collector {@code ScheduleTriggers} 와 같은 규칙.
 *
 * <ul>
 *   <li>{@code FIXED_TIME} + {@code "02:00"} → 매일 02시 크론 {@code "0 0 2 * * *"}</li>
 *   <li>{@code INTERVAL_BASED} + {@code "00:30"} → 30분 주기</li>
 * </ul>
 *
 * <p><b>data-collector 와 다른 점</b>: {@link PeriodicTrigger} 는 등록 즉시 한 번 발화한다. 관리 화면에서 주기 값을
 * 바꿀 때마다(그리고 5분 재조회로 다시 걸 때마다) 배치가 곧바로 한 번 돌지 않도록 <b>첫 발화를 한 주기 뒤로</b> 미룬다.</p>
 */
public final class ScheduleTriggers {

    private ScheduleTriggers() {
    }

    /**
     * @throws IllegalArgumentException 알 수 없는 유형 · 형식 오류 · 0 이하 주기
     */
    public static Trigger of(String execSchedTypeCd, String schedVal, ZoneId zone) {
        if (BatchSchedule.FIXED_TIME.equals(execSchedTypeCd)) {
            return new CronTrigger(cronExpr(schedVal), zone);
        }
        if (BatchSchedule.INTERVAL_BASED.equals(execSchedTypeCd)) {
            Duration period = period(schedVal);
            PeriodicTrigger t = new PeriodicTrigger(period);
            t.setInitialDelay(period);   // 등록 즉시 발화하지 않게
            return t;
        }
        throw new IllegalArgumentException("알 수 없는 스케줄 유형: " + execSchedTypeCd);
    }

    /** {@code "02:00"} · {@code "02:00:00"} → 크론 {@code "0 0 2 * * *"}(초 분 시 매일). */
    public static String cronExpr(String schedVal) {
        int[] t = hms(schedVal);
        if (t[0] > 23 || t[1] > 59 || t[2] > 59) {
            throw new IllegalArgumentException("FIXED_TIME 시각 범위 오류(00:00~23:59): " + schedVal);
        }
        return t[2] + " " + t[1] + " " + t[0] + " * * *";
    }

    /** {@code "00:30"} → 30분. 0 이하면 오류. */
    public static Duration period(String schedVal) {
        int[] t = hms(schedVal);
        Duration p = Duration.ofHours(t[0]).plusMinutes(t[1]).plusSeconds(t[2]);
        if (p.isZero() || p.isNegative()) {
            throw new IllegalArgumentException("INTERVAL_BASED 주기가 0 이하: " + schedVal);
        }
        return p;
    }

    /**
     * 다음 실행 예정 시각 — 상태 화면용.
     *
     * @param anchor INTERVAL_BASED 의 기준(마지막 발화, 없으면 등록 시각). FIXED_TIME 은 쓰지 않는다
     * @return 계산할 수 없으면 null
     */
    public static LocalDateTime nextRun(BatchSchedule s, LocalDateTime now, LocalDateTime anchor) {
        if (s == null || !s.isActive()) {
            return null;
        }
        try {
            if (BatchSchedule.FIXED_TIME.equals(s.execSchedTypeCd())) {
                return CronExpression.parse(cronExpr(s.schedVal())).next(now);
            }
            if (BatchSchedule.INTERVAL_BASED.equals(s.execSchedTypeCd())) {
                Duration p = period(s.schedVal());
                LocalDateTime next = (anchor == null ? now : anchor).plus(p);
                while (next.isBefore(now)) {
                    next = next.plus(p);
                }
                return next;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static int[] hms(String schedVal) {
        String[] p = (schedVal == null ? "" : schedVal.trim()).split(":");
        if (p.length < 2 || p.length > 3) {
            throw new IllegalArgumentException("schedVal 형식 오류(HH:mm[:ss]): " + schedVal);
        }
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            int s = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
            if (h < 0 || m < 0 || s < 0) {
                throw new IllegalArgumentException("schedVal 음수: " + schedVal);
            }
            return new int[] {h, m, s};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("schedVal 형식 오류(HH:mm[:ss]): " + schedVal, e);
        }
    }
}
