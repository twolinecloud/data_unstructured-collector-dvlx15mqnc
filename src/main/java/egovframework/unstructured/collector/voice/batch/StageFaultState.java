package egovframework.unstructured.collector.voice.batch;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 단계별 장애 주입 — <b>COLLECT · ANALYZE · SEND 를 따로 켜고 끈다.</b>
 *
 * <p><b>왜 전역 Chaos 를 걷어냈나</b>: 예전에는 스위치 하나가 모든 구간에 걸렸다. 그래서
 * "STT 만 죽었을 때 복호화 파일로 이어서 처리되는가" 를 보려 해도 수집까지 같이 깨져,
 * 무엇 때문에 실패했는지 시나리오마다 헷갈렸다. T4 의 {@code STEP_TYPE_CD}(C05) 가 이미
 * 세 단계로 갈려 있으므로 스위치도 같은 단위로 나눈다.</p>
 *
 * <p><b>부분 성공은 확률이 아니라 건수다.</b> "10건 중 1건" 이 기대 동작인데 주사위를 굴리면
 * 5건짜리 배치에서 0건이 나오기도 하고 3건이 나오기도 해서, 같은 버튼을 눌러도 화면이 매번 다르다.
 * 배치 시작에 {@link #beginBatch(int)} 로 <b>실패시킬 건수를 미리 정해</b> 두고 그만큼만 떨어뜨린다.
 * 최소 1건은 실패시킨다 — 그러지 않으면 작은 배치에서 '부분 성공' 을 켜도 전건 성공으로 끝나
 * 스위치가 동작하지 않는 것처럼 보인다.</p>
 */
@Log4j2
@Component
public class StageFaultState {

    /** T4 {@code STEP_TYPE_CD}(C05) 와 같은 단위. */
    public enum Stage { COLLECT, ANALYZE, SEND }

    /** OFF = 정상 · ALL = 전건 실패 · PARTIAL = 정해진 건수만 실패(나머지 성공). */
    public enum Mode { OFF, ALL, PARTIAL }

    /** 부분 성공의 기본 실패 비율(%) — 10건 중 1건. */
    public static final int DEFAULT_FAIL_PERCENT = 10;

    private final Map<Stage, Mode> modes = new EnumMap<>(Stage.class);
    private final Map<Stage, Integer> failPercent = new EnumMap<>(Stage.class);
    /** 이번 배치에서 이 단계에 남은 실패 건수(PARTIAL 전용). */
    private final Map<Stage, AtomicInteger> quota = new EnumMap<>(Stage.class);
    private final Map<Stage, AtomicInteger> injected = new EnumMap<>(Stage.class);

    public StageFaultState() {
        for (Stage s : Stage.values()) {
            modes.put(s, Mode.OFF);
            failPercent.put(s, DEFAULT_FAIL_PERCENT);
            quota.put(s, new AtomicInteger(0));
            injected.put(s, new AtomicInteger(0));
        }
    }

    // ── 설정 ──────────────────────────────────────────────────────────────

    /**
     * 한 단계의 장애 모드를 바꾼다.
     *
     * @param percent {@code PARTIAL} 일 때의 실패 비율(%). null 이면 기존 값을 유지한다
     */
    public synchronized void set(Stage stage, Mode mode, Integer percent) {
        modes.put(stage, mode == null ? Mode.OFF : mode);
        if (percent != null) {
            if (percent < 1 || percent > 100) {
                throw new IllegalArgumentException("실패 비율은 1~100 이어야 한다: " + percent);
            }
            failPercent.put(stage, percent);
        }
        log.info("[Fault] {} → {}{}", stage, modes.get(stage),
                modes.get(stage) == Mode.PARTIAL ? " (실패율 " + failPercent.get(stage) + "%)" : "");
    }

    /** 전부 끈다 — 시나리오를 바꿀 때 앞 설정이 남아 간섭하지 않게. */
    public synchronized void clear() {
        for (Stage s : Stage.values()) {
            modes.put(s, Mode.OFF);
        }
        log.info("[Fault] 전 단계 해제");
    }

    public boolean anyOn() {
        return modes.values().stream().anyMatch(m -> m != Mode.OFF);
    }

    // ── 배치 ──────────────────────────────────────────────────────────────

    /**
     * 배치 시작 — 이번 회차에 단계별로 <b>몇 건을 실패시킬지</b> 정한다.
     *
     * @param total 이번 배치의 대상 건수
     */
    public synchronized void beginBatch(int total) {
        for (Stage s : Stage.values()) {
            injected.get(s).set(0);
            int q = 0;
            if (modes.get(s) == Mode.ALL) {
                q = Integer.MAX_VALUE;
            } else if (modes.get(s) == Mode.PARTIAL) {
                // 최소 1건 — 작은 배치에서 0건이 되면 스위치가 안 먹는 것처럼 보인다
                q = Math.max(1, Math.round(total * failPercent.get(s) / 100f));
            }
            quota.get(s).set(q);
        }
        if (anyOn()) {
            log.info("[Fault] 배치 시작 — 대상 {}건 · 계획 {}", total, plan());
        }
    }

    /**
     * 이 건을 이 단계에서 실패시킬 것인가. <b>호출할 때마다 할당량이 줄어든다</b> —
     * 판정용으로 두 번 부르면 건수가 어긋난다.
     */
    public boolean shouldFail(Stage stage) {
        Mode m = modes.get(stage);
        if (m == Mode.OFF) {
            return false;
        }
        if (quota.get(stage).getAndUpdate(v -> v > 0 ? v - 1 : 0) <= 0) {
            return false;
        }
        injected.get(stage).incrementAndGet();
        return true;
    }

    /** 실패시킬 때 던질 예외 — 시나리오마다 사유가 달라 메시지를 받는다. */
    public static IllegalStateException fault(Stage stage, String reason) {
        return new IllegalStateException("[주입된 장애/" + stage + "] " + reason);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Stage s : Stage.values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("mode", modes.get(s).name());
            one.put("failPercent", failPercent.get(s));
            one.put("injected", injected.get(s).get());
            out.put(s.name(), one);
        }
        out.put("anyOn", anyOn());
        return out;
    }

    private String plan() {
        StringBuilder sb = new StringBuilder();
        for (Stage s : Stage.values()) {
            if (modes.get(s) == Mode.OFF) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            int q = quota.get(s).get();
            sb.append(s).append('=').append(modes.get(s) == Mode.ALL ? "전건" : q + "건");
        }
        return sb.toString();
    }
}
