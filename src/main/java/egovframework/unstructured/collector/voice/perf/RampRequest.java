package egovframework.unstructured.collector.voice.perf;

import egovframework.unstructured.collector.voice.batch.Workers;
import egovframework.unstructured.collector.voice.stt.MockSttLatency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 임계 성능 시험(5번 탭) — <b>워커 램프업</b> 조건.
 *
 * <p>시작 워커부터 단계마다 워커를 늘려 <b>같은 건수</b>를 처리한다. 건수가 같으니 단계 총 소요(=응답 시간)가
 * 바로 비교된다 — 짧아지는 동안은 워커를 늘리는 것이 효과가 있고, 더 짧아지지 않으면 어딘가(브로커·NFS·DB)가
 * 포화된 것이다.</p>
 *
 * <p><b>늘릴 워커</b>({@code rampTarget}) — 워커가 둘이라({@link Workers}) 한쪽만 늘리고 다른 쪽은 고정한다.</p>
 * <ul>
 *   <li>{@code STT}(기본) — STT 처리 워커를 늘린다. XVARM 확보 워커는 {@code acquireWorkers}(기본 1)로 고정</li>
 *   <li>{@code ACQUIRE} — XVARM 확보 워커를 늘린다. STT 처리 워커는 {@code sttWorkers}(기본 31)로 고정 —
 *       브로커(추출 스레드 4개)·NFS 가 버티는 확보 동시성을 찾는다</li>
 * </ul>
 *
 * <p><b>조기 종료</b></p>
 * <ul>
 *   <li>최저 응답 시간(단계 총 소요)을 기록한 뒤 {@code patience} 번 연속 그보다 빨라지지 않으면 — 포화</li>
 *   <li>XVARM 확보 대기가 {@code acquireLimitSec} 초를 넘으면 — 단계 도중이라도 즉시</li>
 *   <li>STT 에러·타임아웃이 한 건이라도 나면 — 단계 도중이라도 즉시({@code stopOnSttError})</li>
 * </ul>
 */
@Schema(description = "임계 성능 시험(워커 램프업) 조건")
public record RampRequest(
        @Schema(description = "단계마다 만들 접견 건수", example = "150") Integer meetCount,
        @Schema(description = "단계마다 만들 전화 건수", example = "150") Integer phoneCount,
        @Schema(description = "기 STT 존재 비율(%)", example = "3") Integer sttPercent,
        @Schema(description = "건당 STT 처리 시간 방식 — FIXED · RANGE", example = "FIXED") String latencyMode,
        @Schema(description = "접견 건당 STT 처리 시간(ms)", example = "180000") Long meetLatencyMs,
        @Schema(description = "전화 건당 STT 처리 시간(ms)", example = "120000") Long phoneLatencyMs,
        @Schema(description = "범위일 때 ± 변동 폭(%)", example = "0") Integer jitterPercent,
        @Schema(description = "STT 타임아웃(ms) — 비우면 적용 안 함", example = "") Long sttTimeoutMs,
        @Schema(description = "시작 워커 수 — 1~64", example = "1") Integer startWorkers,
        @Schema(description = "증가 방식 — ADD(+N) · MULTIPLY(×N)", example = "ADD") String stepMode,
        @Schema(description = "증가 값 — ADD 면 1~32, MULTIPLY 면 2~4", example = "1") Integer stepValue,
        @Schema(description = "최대 워커 수 — 시작 이상 64 이하", example = "32") Integer maxWorkers,
        @Schema(description = "최저 응답 뒤 연속으로 개선이 없으면 멈출 횟수 — 1~10", example = "3") Integer patience,
        @Schema(description = "XVARM 확보 대기 한도(초) — 넘으면 즉시 멈춤. 0 이면 보지 않는다", example = "60")
        Integer acquireLimitSec,
        @Schema(description = "STT 에러·타임아웃이 나면 즉시 멈출지", example = "true") Boolean stopOnSttError,
        @Schema(description = "실제 대기 모드 — 비우거나 false 면 고속 모드(가상 시간 합산)", example = "false") Boolean realSleep,
        @Schema(description = "늘릴 워커 — STT(STT 처리 워커, 기본) · ACQUIRE(XVARM 확보 워커)", example = "STT") String rampTarget,
        @Schema(description = "XVARM 확보 워커 — STT 를 늘릴 때 고정값. 1~64, 기본 1", example = "1") Integer acquireWorkers,
        @Schema(description = "STT 처리 워커 — 확보를 늘릴 때 고정값. 1~64, 기본 31", example = "31") Integer sttWorkers
) {

    public static final int MAX_WORKERS = 64;
    /** +1 씩 1 → 64 까지도 한 번에 돌 수 있게 — 보통은 포화 판정이 먼저 멈춘다. */
    public static final int MAX_STEPS = 64;

    public enum StepMode { ADD, MULTIPLY }

    /** 늘릴 워커. */
    public enum Target {
        STT("STT 처리 워커"), ACQUIRE("XVARM 확보 워커");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public RampRequest withDefaults() {
        String mode = PerfRequest.mode(latencyMode);
        return new RampRequest(
                meetCount == null ? PerfRequest.DEFAULT_MEET : meetCount,
                phoneCount == null ? PerfRequest.DEFAULT_PHONE : phoneCount,
                sttPercent == null ? PerfRequest.DEFAULT_STT_PERCENT : sttPercent,
                mode,
                meetLatencyMs == null ? PerfRequest.DEFAULT_MEET_LATENCY_MS : meetLatencyMs,
                phoneLatencyMs == null ? PerfRequest.DEFAULT_PHONE_LATENCY_MS : phoneLatencyMs,
                "RANGE".equals(mode) ? (jitterPercent == null ? PerfRequest.DEFAULT_JITTER : jitterPercent) : 0,
                PerfRequest.timeout(sttTimeoutMs),
                startWorkers == null ? 1 : startWorkers,
                stepMode == null || stepMode.isBlank() ? "ADD" : stepMode.trim().toUpperCase(Locale.ROOT),
                stepValue == null ? 1 : stepValue,
                maxWorkers == null ? 32 : maxWorkers,
                patience == null ? 3 : patience,
                acquireLimitSec == null ? 60 : acquireLimitSec,
                stopOnSttError == null ? Boolean.TRUE : stopOnSttError,
                Boolean.TRUE.equals(realSleep),
                rampTarget == null || rampTarget.isBlank() ? Target.STT.name() : rampTarget.trim().toUpperCase(Locale.ROOT),
                acquireWorkers == null ? PerfRequest.DEFAULT_ACQUIRE_WORKERS : acquireWorkers,
                sttWorkers == null ? PerfRequest.DEFAULT_STT_WORKERS : sttWorkers);
    }

    public void validate(int maxFilesPerRun) {
        PerfRequest.validateData(meetCount, phoneCount, sttPercent, maxFilesPerRun);
        PerfRequest.validateLoad(latencyMode, meetLatencyMs, phoneLatencyMs, jitterPercent, sttTimeoutMs);
        try {
            Target.valueOf(rampTarget);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("늘릴 워커는 STT(STT 처리 워커) 또는 ACQUIRE(XVARM 확보 워커)여야 합니다: " + rampTarget);
        }
        PerfRequest.validateWorkers("XVARM 확보 워커(고정)", acquireWorkers);
        PerfRequest.validateWorkers("STT 처리 워커(고정)", sttWorkers);
        StepMode sm;
        try {
            sm = StepMode.valueOf(stepMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("증가 방식은 ADD(+N) 또는 MULTIPLY(×N)여야 합니다: " + stepMode);
        }
        if (startWorkers < 1 || startWorkers > MAX_WORKERS) {
            throw new IllegalArgumentException("시작 워커는 1~%d 이어야 합니다: %d".formatted(MAX_WORKERS, startWorkers));
        }
        if (maxWorkers < startWorkers || maxWorkers > MAX_WORKERS) {
            throw new IllegalArgumentException("최대 워커는 시작 워커(%d) 이상 %d 이하여야 합니다: %d"
                    .formatted(startWorkers, MAX_WORKERS, maxWorkers));
        }
        if (sm == StepMode.ADD && (stepValue < 1 || stepValue > 32)) {
            throw new IllegalArgumentException("+N 증가 값은 1~32 이어야 합니다: " + stepValue);
        }
        if (sm == StepMode.MULTIPLY && (stepValue < 2 || stepValue > 4)) {
            throw new IllegalArgumentException("×N 증가 값은 2~4 이어야 합니다: " + stepValue);
        }
        if (patience < 1 || patience > 10) {
            throw new IllegalArgumentException("개선 없음 허용 횟수는 1~10 이어야 합니다: " + patience);
        }
        if (acquireLimitSec < 0 || acquireLimitSec > 3_600) {
            throw new IllegalArgumentException("XVARM 확보 대기 한도는 0~3,600초 이어야 합니다(0 = 보지 않음): " + acquireLimitSec);
        }
        if (plan().size() > MAX_STEPS) {
            throw new IllegalArgumentException("단계가 %d개를 넘습니다(%d개) — 증가 값을 키우거나 최대 워커를 줄이십시오"
                    .formatted(MAX_STEPS, plan().size()));
        }
    }

    /** 돌 단계들의 워커 수 — 마지막은 늘 최대 워커다(곱해서 넘치면 최대에서 끝낸다). */
    public List<Integer> plan() {
        List<Integer> out = new ArrayList<>();
        int w = startWorkers;
        while (true) {
            out.add(w);
            if (w >= maxWorkers || out.size() > MAX_STEPS) {
                return out;
            }
            int next = StepMode.valueOf(stepMode) == StepMode.ADD ? w + stepValue : w * stepValue;
            w = Math.min(next, maxWorkers);
        }
    }

    public int count() {
        return meetCount + phoneCount;
    }

    public Target target() {
        return Target.valueOf(rampTarget);
    }

    /** 단계의 워커 구성 — 늘리는 쪽이 {@code w}, 다른 쪽은 고정값. */
    public Workers workersAt(int w) {
        return target() == Target.STT ? new Workers(acquireWorkers, w) : new Workers(w, sttWorkers);
    }

    public MockSttLatency.Mode mode() {
        return MockSttLatency.Mode.valueOf(latencyMode);
    }
}
