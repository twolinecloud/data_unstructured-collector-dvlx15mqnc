package egovframework.unstructured.collector.voice.transfer;

import egovframework.unstructured.collector.voice.batch.BatchProgress;
import egovframework.unstructured.collector.voice.perf.PerfStage;
import egovframework.unstructured.collector.voice.perf.PerfStageMeter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * 성능 시험의 <b>건당 비식별 처리 시간</b> — 비식별 엔진(AI-R)의 처리 시간을 흉내 낸다.
 *
 * <p>평소(운영 배치)에는 0 이라 아무것도 하지 않는다. 성능 시험이 {@code deidentEnabled=true} 로 돌 때만
 * {@link #apply} 로 켜진다 — 단순 전달({@code false})은 무거운 연산이 없으므로 0ms 다.</p>
 *
 * <ul>
 *   <li><b>고속 모드</b> — 기다리지 않고 그 시간을 {@link PerfStageMeter} 에 가상으로 적는다. 리포트가 워커에 나눠
 *       총 소요에 더한다(가상 STT 와 같은 방식)</li>
 *   <li><b>실제 대기 모드</b> — 그만큼 실제로 기다린다. 중단 요청이 오면 바로 깬다</li>
 * </ul>
 */
@Log4j2
@Component
public class DeidentLoad {

    private static final long SLICE_MS = 200L;

    private volatile long ms;
    private volatile boolean fastForward;

    public void apply(long perItemMs, boolean fastForward) {
        this.ms = Math.max(0L, perItemMs);
        this.fastForward = fastForward;
        if (this.ms > 0) {
            log.info("[Deident] 건당 비식별 처리 시간 {}ms · {}", this.ms, fastForward ? "고속 모드(가상 합산)" : "실제 대기");
        }
    }

    public void clear() {
        this.ms = 0L;
        this.fastForward = false;
    }

    public long perItemMs() {
        return ms;
    }

    /** 비식별을 수행하는 한 건 — 시간을 가상으로 적거나 실제로 기다린다. */
    public void simulate(PerfStageMeter meter, BatchProgress progress) {
        long d = ms;
        if (d <= 0) {
            return;
        }
        if (fastForward) {
            meter.addVirtual(PerfStage.DEIDENT, d);
            return;
        }
        long until = System.currentTimeMillis() + d;
        try {
            for (long left = d; left > 0; left = until - System.currentTimeMillis()) {
                if (progress.isCancelRequested()) {
                    throw new IllegalStateException("중단됨 — 비식별 처리 대기 중 멈췄습니다");
                }
                Thread.sleep(Math.min(left, SLICE_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("비식별 처리 대기 중 중단됨", e);
        }
    }
}
