package egovframework.unstructured.collector.image.batch;

import egovframework.unstructured.collector.image.model.ImageStage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 이미지 배치 한 번의 단계별 시간 — 여러 워커가 동시에 더한다({@link LongAdder}). 배치마다 새로 만든다.
 */
public final class ImageMetrics {

    private final Map<ImageStage, LongAdder> nanos = new EnumMap<>(ImageStage.class);
    private final Map<ImageStage, LongAdder> counts = new EnumMap<>(ImageStage.class);
    private final Map<ImageStage, AtomicLong> max = new EnumMap<>(ImageStage.class);
    private final Map<ImageStage, LongAdder> errors = new EnumMap<>(ImageStage.class);

    public ImageMetrics() {
        for (ImageStage s : ImageStage.values()) {
            nanos.put(s, new LongAdder());
            counts.put(s, new LongAdder());
            max.put(s, new AtomicLong());
            errors.put(s, new LongAdder());
        }
    }

    public static long start() {
        return System.nanoTime();
    }

    public void add(ImageStage s, long startedNanos) {
        long d = System.nanoTime() - startedNanos;
        nanos.get(s).add(d);
        counts.get(s).increment();
        max.get(s).accumulateAndGet(d, Math::max);
    }

    public void error(ImageStage s) {
        errors.get(s).increment();
    }

    public double avgMs(ImageStage s) {
        long n = counts.get(s).sum();
        return n == 0 ? 0d : round1(nanos.get(s).sum() / 1_000_000d / n);
    }

    /** 단계별 평균·최대·건수·에러 — 화면 막대 순서대로. 한 번도 안 돈 가상 지연 단계는 뺀다. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImageStage s : ImageStage.values()) {
            long n = counts.get(s).sum();
            if (s == ImageStage.VIRTUAL && n == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.name());
            m.put("label", s.label());
            m.put("count", n);
            m.put("avgMs", avgMs(s));
            m.put("maxMs", round1(max.get(s).get() / 1_000_000d));
            m.put("totalMs", Math.round(nanos.get(s).sum() / 1_000_000d));
            m.put("errors", errors.get(s).sum());
            out.add(m);
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
