package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 장애 주입(Chaos) — 일부러 실패와 지연을 섞는다.
 *
 * <p><b>무엇을 증명하려는가</b>: 파일 1건이 깨져도 배치가 죽지 않고 <b>failCnt 만 올리며
 * 끝까지 도는가</b>. 1,300건을 도는 배치에서 한 건 때문에 전체가 멈추면 나머지 1,299건을
 * 다시 처리해야 한다. {@code VoiceCollectService.processOne} 이 건별로 예외를 가두고 있는데,
 * 그게 실제로 동작하는지는 <b>진짜 예외를 던져 봐야</b> 알 수 있다.</p>
 *
 * <p><b>기본은 꺼져 있다.</b> 켜져 있는 줄 모르고 시연하면 실패 건수를 버그로 오해한다.
 * 상태 조회({@code /api/v1/voice/status})와 시뮬레이터 화면에 항상 노출한다.</p>
 *
 * <p>실패·지연 판정은 독립이다 — 한 건이 지연되면서 실패할 수도 있다(실제 장애가 그렇다).</p>
 */
@Log4j2
@Component
public class FaultInjector {

    /** 장애 주입 지점. 로그·통계에서 어느 구간이 맞았는지 구분한다. */
    public enum Stage {
        STT("STT");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private volatile boolean enabled = false;
    private volatile int failPercent = 10;
    private volatile int delayPercent = 10;
    private volatile long delayMs = 2_000;

    private final AtomicLong injectedFailures = new AtomicLong();
    private final AtomicLong injectedDelays = new AtomicLong();

    /**
     * 이 지점에서 장애를 주입할지 판정하고, 해당되면 지연시키거나 예외를 던진다.
     *
     * @throws InjectedFaultException 실패로 판정된 경우
     */
    public void maybeInject(Stage stage) {
        if (!enabled) {
            return;
        }
        if (roll(delayPercent)) {
            injectedDelays.incrementAndGet();
            log.warn("[Chaos] {} 지연 주입 — {}ms", stage.label(), delayMs);
            sleep(delayMs);
        }
        if (roll(failPercent)) {
            injectedFailures.incrementAndGet();
            log.warn("[Chaos] {} 실패 주입", stage.label());
            throw new InjectedFaultException(stage);
        }
    }

    private boolean roll(int percent) {
        return percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("장애 주입 지연 중 인터럽트", e);
        }
    }

    // ── 설정 ───────────────────────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void configure(Boolean on, Integer failPct, Integer delayPct, Long delayMillis) {
        if (on != null) {
            this.enabled = on;
        }
        if (failPct != null) {
            this.failPercent = clampPercent("failPercent", failPct);
        }
        if (delayPct != null) {
            this.delayPercent = clampPercent("delayPercent", delayPct);
        }
        if (delayMillis != null) {
            if (delayMillis < 0 || delayMillis > 60_000) {
                throw new IllegalArgumentException("delayMs 는 0~60000 이어야 한다: " + delayMillis);
            }
            this.delayMs = delayMillis;
        }
        log.info("[Chaos] 설정 — enabled={} fail={}% delay={}% ({}ms)",
                enabled, failPercent, delayPercent, delayMs);
    }

    /** 주입 통계를 0으로 되돌린다(배치 시작 시). */
    public void resetCounters() {
        injectedFailures.set(0);
        injectedDelays.set(0);
    }

    public long injectedFailures() {
        return injectedFailures.get();
    }

    public long injectedDelays() {
        return injectedDelays.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("failPercent", failPercent);
        m.put("delayPercent", delayPercent);
        m.put("delayMs", delayMs);
        m.put("injectedFailures", injectedFailures.get());
        m.put("injectedDelays", injectedDelays.get());
        return m;
    }

    private int clampPercent(String name, int v) {
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException(name + " 는 0~100 이어야 한다: " + v);
        }
        return v;
    }

    /** 주입된 장애임을 명확히 하는 예외 — 진짜 버그와 로그에서 구분된다. */
    public static class InjectedFaultException extends RuntimeException {
        public InjectedFaultException(Stage stage) {
            super("[장애 시뮬레이션] " + stage.label() + " 구간에서 의도적으로 발생시킨 실패");
        }
    }
}
