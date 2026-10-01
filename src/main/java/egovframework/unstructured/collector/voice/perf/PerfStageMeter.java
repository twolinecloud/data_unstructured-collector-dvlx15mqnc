package egovframework.unstructured.collector.voice.perf;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 건별 단계 시간 — <b>성능 시험이 도는 동안에만</b> 센다.
 *
 * <p>배치 코드에 넣는 것은 두 줄뿐이다 — {@code long m = meter.start();} 와 {@code meter.add(단계, m);}.
 * 꺼져 있으면 {@link #start()} 가 0 을 돌려주고 {@link #add} 는 아무것도 하지 않는다. 운영 배치에서는
 * 시계를 읽지도 않는다.</p>
 *
 * <p>평균 외에 <b>최댓값·에러 수·진행 중인 대기</b>를 둔다 — 임계 성능 시험(램프업)의 조기 종료가
 * "XVARM 확보 대기가 N초를 넘었다" · "STT 에러가 났다" 를 <b>단계가 끝나기 전에</b> 알아야 하기 때문이다.
 * 대기 중인 건은 아직 끝나지 않아 합계에 없으므로 {@link #start(PerfStage)} 로 따로 적어 둔다.</p>
 *
 * <p>워커가 여럿이어도 된다 — {@link LongAdder}·{@link ConcurrentHashMap} 으로 센다. 한 번에 한 성능 시험만
 * 돌므로(배치도 한 번에 하나) 전역 하나로 충분하다.</p>
 */
@Component
public class PerfStageMeter {

    private volatile boolean active;
    private final Map<PerfStage, LongAdder> nanos = new EnumMap<>(PerfStage.class);
    private final Map<PerfStage, LongAdder> counts = new EnumMap<>(PerfStage.class);
    private final Map<PerfStage, AtomicLong> maxNanos = new EnumMap<>(PerfStage.class);
    private final Map<PerfStage, LongAdder> errors = new EnumMap<>(PerfStage.class);
    /** 고속 모드에서 기다리지 않은 건별 처리 시간(ms) — 단계(STT)별. 리포트가 워커에 나눠 총 소요에 더한다. */
    private final Map<PerfStage, java.util.Queue<Long>> virtualMs = new EnumMap<>(PerfStage.class);
    /** 지금 그 단계에 머물러 있는 스레드와 들어간 시각 — 한 스레드는 한 번에 한 건만 처리한다. */
    private final Map<PerfStage, Map<Thread, Long>> inFlight = new EnumMap<>(PerfStage.class);

    // ── 생산자-소비자(확보 워커 → STT 워커) 모의용 ──────────────────────────────
    /** STT 워커가 처리한 건마다 한 줄 — 확보가 끝난 시각 · STT 워커가 쓴 실제 시간 · 기다리지 않은 가상 시간. */
    private final java.util.Queue<PipelineItem> pipelineItems = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 확보 워커가 마지막 건을 끝낸 시각(루프 시작 기준 ms) — 확보에서 끝난(실패·건너뜀) 건까지. */
    private final AtomicLong acquireSpanMs = new AtomicLong();
    /** 생산자-소비자 루프의 실제 경과(ms). 순차로 돌았으면 -1. */
    private volatile long pipelineLoopMs = -1L;
    /** 지금 이 STT 워커가 처리 중인 건에 적힌 가상 시간 — {@link #beginItem()} ~ {@link #endItem()}. */
    private final ThreadLocal<long[]> itemVirtual = new ThreadLocal<>();

    /**
     * STT 워커가 처리한 한 건.
     *
     * @param readyMs   확보가 끝나 대기열에 들어간 시각(루프 시작 기준)
     * @param serviceMs STT 워커가 실제로 쓴 시간(복호화 ~ 저장)
     * @param virtualMs 고속 모드로 기다리지 않은 시간(STT)
     */
    public record PipelineItem(long readyMs, long serviceMs, long virtualMs) {}

    /** 생산자-소비자 한 번의 기록 — 고속 모드 리포트가 이것으로 총 소요를 다시 짠다. */
    public record Pipeline(List<PipelineItem> items, long acquireSpanMs, long loopMs) {}

    public PerfStageMeter() {
        for (PerfStage s : PerfStage.values()) {
            nanos.put(s, new LongAdder());
            counts.put(s, new LongAdder());
            maxNanos.put(s, new AtomicLong());
            errors.put(s, new LongAdder());
            inFlight.put(s, new ConcurrentHashMap<>());
            virtualMs.put(s, new java.util.concurrent.ConcurrentLinkedQueue<>());
        }
    }

    /** 새로 센다. */
    public void begin() {
        for (PerfStage s : PerfStage.values()) {
            nanos.get(s).reset();
            counts.get(s).reset();
            maxNanos.get(s).set(0L);
            errors.get(s).reset();
            inFlight.get(s).clear();
        }
        virtualMs.values().forEach(java.util.Queue::clear);
        pipelineItems.clear();
        acquireSpanMs.set(0L);
        pipelineLoopMs = -1L;
        active = true;
    }

    public void end() {
        active = false;
        inFlight.values().forEach(Map::clear);
    }

    public boolean isActive() {
        return active;
    }

    /** 단계 시작 시각 — 꺼져 있으면 0(시계를 읽지 않는다). */
    public long start() {
        return active ? System.nanoTime() : 0L;
    }

    /** 위와 같되, 끝나기 전에도 "얼마나 오래 머물러 있나" 를 볼 수 있게 적어 둔다. 짝이 되는 {@link #add} 가 지운다. */
    public long start(PerfStage stage) {
        long t = start();
        if (t != 0L) {
            inFlight.get(stage).put(Thread.currentThread(), t);
        }
        return t;
    }

    /** {@link #start()} 로 받은 시각부터 지금까지를 그 단계에 더한다. */
    public void add(PerfStage stage, long startedNanos) {
        inFlight.get(stage).remove(Thread.currentThread());
        if (!active || startedNanos == 0L) {
            return;
        }
        long d = System.nanoTime() - startedNanos;
        nanos.get(stage).add(d);
        counts.get(stage).increment();
        maxNanos.get(stage).accumulateAndGet(d, Math::max);
    }

    /** 그 단계에서 에러가 났다(STT 호출 실패·타임아웃 등). */
    public void error(PerfStage stage) {
        if (active) {
            errors.get(stage).increment();
        }
    }

    /** 고속 모드 — 이 건이 실제였다면 STT 에 썼을 시간(ms). */
    public void addVirtual(long ms) {
        addVirtual(PerfStage.STT, ms);
    }

    /** 고속 모드 — 이 건이 실제였다면 그 단계에 썼을 시간(ms). */
    public void addVirtual(PerfStage stage, long ms) {
        if (active) {
            long v = Math.max(0L, ms);
            virtualMs.get(stage).add(v);
            long[] item = itemVirtual.get();
            if (item != null) {
                item[0] += v;   // 이 STT 워커가 처리 중인 건의 몫 — 파이프라인 모의에 쓴다
            }
        }
    }

    // ── 생산자-소비자 ──────────────────────────────────────────────────────

    /** STT 워커가 한 건을 집었다 — 이제부터 이 스레드에 적히는 가상 시간은 이 건의 몫이다. */
    public void beginItem() {
        if (active) {
            itemVirtual.set(new long[1]);
        }
    }

    /** 그 건이 끝났다 — 그동안 적힌 가상 시간(ms)을 돌려준다. */
    public long endItem() {
        long[] item = itemVirtual.get();
        itemVirtual.remove();
        return item == null ? 0L : item[0];
    }

    /** STT 워커가 처리한 한 건을 적는다. */
    public void pipelineItem(long readyMs, long serviceMs, long virtualMs) {
        if (active) {
            pipelineItems.add(new PipelineItem(readyMs, serviceMs, virtualMs));
        }
    }

    /** 확보 워커가 한 건을 끝냈다(대기열로 넘겼든 확보에서 실패했든) — 그 시각(루프 시작 기준 ms). */
    public void acquireDone(long atMs) {
        if (active) {
            acquireSpanMs.accumulateAndGet(atMs, Math::max);
        }
    }

    /** 생산자-소비자 루프가 끝났다 — 실제 경과(ms). */
    public void pipelineLoop(long wallMs) {
        if (active) {
            pipelineLoopMs = wallMs;
        }
    }

    /** 이번 측정의 생산자-소비자 기록. 순차로 돌았으면 null. */
    public Pipeline pipeline() {
        long loop = pipelineLoopMs;
        if (loop < 0) {
            return null;
        }
        return new Pipeline(List.copyOf(pipelineItems), acquireSpanMs.get(), loop);
    }

    /** 고속 모드로 건너뛴 건별 STT 처리 시간(ms) — 적힌 순서대로. */
    public List<Long> virtualSttMs() {
        return virtualMs(PerfStage.STT);
    }

    /** 고속 모드로 건너뛴 그 단계의 건별 처리 시간(ms). */
    public List<Long> virtualMs(PerfStage stage) {
        return List.copyOf(virtualMs.get(stage));
    }

    public long errors(PerfStage stage) {
        return errors.get(stage).sum();
    }

    /** 끝난 건 중 가장 오래 걸린 시간(ms). */
    public double maxMs(PerfStage stage) {
        return maxNanos.get(stage).get() / 1_000_000d;
    }

    /** 아직 끝나지 않은 건 중 가장 오래 머문 시간(ms). 없으면 0. */
    public double oldestInFlightMs(PerfStage stage) {
        long now = System.nanoTime();
        return inFlight.get(stage).values().stream().mapToLong(t -> now - t).max().orElse(0L) / 1_000_000d;
    }

    /** 단계별 평균·최대(ms)·건수·에러 — 화면 막대 그래프 순서대로. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>(PerfStage.values().length);
        for (PerfStage s : PerfStage.values()) {
            long n = counts.get(s).sum();
            long total = nanos.get(s).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.name());
            m.put("label", s.label());
            m.put("count", n);
            m.put("avgMs", n == 0 ? 0d : round1(total / 1_000_000d / n));
            m.put("maxMs", round1(maxMs(s)));
            m.put("totalMs", Math.round(total / 1_000_000d));
            m.put("errors", errors(s));
            out.add(m);
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
