package egovframework.unstructured.collector.voice.perf;

import com.fasterxml.jackson.annotation.JsonAlias;
import egovframework.unstructured.collector.voice.batch.Workers;
import egovframework.unstructured.collector.voice.source.SimulationDataService;
import egovframework.unstructured.collector.voice.stt.MockSttLatency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;

/**
 * 기본 부하 검증(4번 탭) 한 회차의 조건.
 *
 * <p>비운 값은 {@link #withDefaults()} 가 화면 기본값으로 채운다 — 접견 150 · 전화 150(합 300, 일일 한도) ·
 * 기 STT 3% · XVARM 확보 워커 1 · STT 처리 워커 31 · 건당 STT 처리 시간 접견 180,000ms / 전화 120,000ms(고정) ·
 * 타임아웃 없음 · 고속 모드.</p>
 *
 * <p><b>워커가 둘인 이유</b>: 확보(I/O)와 STT(연산)를 한 워커가 다 하면 워커를 늘릴 때 확보 요청도 같이 늘어
 * 브로커가 밀리고 확보 대기가 폭증한다. 확보 워커가 받아 둔 파일을 STT 워커가 곧바로 집어 간다({@link Workers}).</p>
 */
@Schema(description = "기본 부하 검증 조건")
public record PerfRequest(
        @Schema(description = "시나리오 이름 — 이력에 그대로 남는다", example = "A 순수 처리량") String scenario,
        @Schema(description = "접견 건수 — 0 이상, 전화와 합쳐 2~300", example = "150") Integer meetCount,
        @Schema(description = "전화 건수 — 0 이상, 접견과 합쳐 2~300", example = "150") Integer phoneCount,
        @Schema(description = "기 STT 존재 비율(%) — 전화 중 이 비율에 TELP_STT_FLPTH_NM 을 채워 STT 를 건너뛰게 한다", example = "3")
        Integer sttPercent,
        @Schema(description = "XVARM 확보 워커 수 — 1~64. 파일 확보(브로커 추출 → 수신 폴더 도착)를 차례로 한다", example = "1")
        Integer acquireWorkers,
        @Schema(description = "STT 처리 워커 수 — 1~64. 확보된 파일을 곧바로 받아 복호화 · STT · 제논 전송을 한다", example = "31")
        @JsonAlias("concurrency") Integer sttWorkers,
        @Schema(description = "건당 STT 처리 시간 방식 — FIXED(고정) · RANGE(기준값 ±변동 폭 균등 난수)", example = "FIXED") String latencyMode,
        @Schema(description = "접견 건당 STT 처리 시간(ms) — 0~600,000. 평균 접견 15분 → 건당 처리 시간 180초", example = "180000")
        Long meetLatencyMs,
        @Schema(description = "전화 건당 STT 처리 시간(ms) — 0~600,000. 평균 통화 5분 → 건당 처리 시간 120초", example = "120000")
        Long phoneLatencyMs,
        @Schema(description = "범위일 때 기준값 대비 ± 변동 폭(%) — 0~100", example = "50") Integer jitterPercent,
        @Schema(description = "STT 타임아웃(ms) — 건당 처리 시간이 이 값을 넘으면 타임아웃 실패. 비우거나 0 이면 적용하지 않는다",
                example = "200000") Long sttTimeoutMs,
        @Schema(description = "실제 대기 모드 — true 면 처리 시간만큼 실제로 기다린다. 비우거나 false 면 고속 모드"
                + "(기다리지 않고 가상 시간을 리포트에 합산)", example = "false") Boolean realSleep
) {

    public static final int MIN_TOTAL = 2;
    public static final int MAX_TOTAL = SimulationDataService.PERF_MAX_TOTAL;
    public static final long MAX_LATENCY_MS = 600_000L;
    public static final long MIN_TIMEOUT_MS = 100L;
    public static final long MAX_TIMEOUT_MS = 1_800_000L;
    public static final int DEFAULT_MEET = 150;
    public static final int DEFAULT_PHONE = 150;
    public static final int DEFAULT_STT_PERCENT = 3;
    public static final long DEFAULT_MEET_LATENCY_MS = 180_000L;
    public static final long DEFAULT_PHONE_LATENCY_MS = 120_000L;
    public static final int DEFAULT_JITTER = 50;
    public static final int DEFAULT_ACQUIRE_WORKERS = 1;
    public static final int DEFAULT_STT_WORKERS = 31;
    public static final int MAX_WORKERS = 64;

    /** 비운 값을 기본값으로 채운다. */
    public PerfRequest withDefaults() {
        String mode = mode(latencyMode);
        return new PerfRequest(
                scenario == null || scenario.isBlank() ? "직접 입력" : scenario.trim(),
                meetCount == null ? DEFAULT_MEET : meetCount,
                phoneCount == null ? DEFAULT_PHONE : phoneCount,
                sttPercent == null ? DEFAULT_STT_PERCENT : sttPercent,
                acquireWorkers == null ? DEFAULT_ACQUIRE_WORKERS : acquireWorkers,
                sttWorkers == null ? DEFAULT_STT_WORKERS : sttWorkers,
                mode,
                meetLatencyMs == null ? DEFAULT_MEET_LATENCY_MS : meetLatencyMs,
                phoneLatencyMs == null ? DEFAULT_PHONE_LATENCY_MS : phoneLatencyMs,
                "RANGE".equals(mode) ? (jitterPercent == null ? DEFAULT_JITTER : jitterPercent) : 0,
                timeout(sttTimeoutMs),
                Boolean.TRUE.equals(realSleep));
    }

    /**
     * 범위를 확인한다 — 어긋나면 {@link IllegalArgumentException}(화면에는 400 과 이 문구).
     *
     * @param maxFilesPerRun 배치 1회 처리 상한({@code voice.batch.max-files-per-run}) — 넘으면 일부만 처리된다
     */
    public void validate(int maxFilesPerRun) {
        validateData(meetCount, phoneCount, sttPercent, maxFilesPerRun);
        validateWorkers("XVARM 확보 워커", acquireWorkers);
        validateWorkers("STT 처리 워커", sttWorkers);
        validateLoad(latencyMode, meetLatencyMs, phoneLatencyMs, jitterPercent, sttTimeoutMs);
    }

    public int count() {
        return meetCount + phoneCount;
    }

    /** 이 회차의 워커 구성. */
    public Workers workers() {
        return new Workers(acquireWorkers, sttWorkers);
    }

    public MockSttLatency.Mode mode() {
        return MockSttLatency.Mode.valueOf(latencyMode);
    }

    // ── 4번·5번 탭이 같이 쓰는 검사 ───────────────────────────────────────

    static String mode(String m) {
        return m == null || m.isBlank() ? "FIXED" : m.trim().toUpperCase(Locale.ROOT);
    }

    static Long timeout(Long ms) {
        return ms == null || ms <= 0 ? null : ms;
    }

    /** 시험 데이터 — 접견·전화 건수와 기 STT 비율. */
    static void validateData(Integer meet, Integer phone, Integer sttPercent, int maxFilesPerRun) {
        if (meet == null || phone == null || meet < 0 || phone < 0) {
            throw new IllegalArgumentException("접견·전화 건수는 0 이상이어야 합니다: 접견 " + meet + " · 전화 " + phone);
        }
        int total = meet + phone;
        if (total < MIN_TOTAL || total > MAX_TOTAL) {
            throw new IllegalArgumentException("접견 + 전화 합계는 %d~%d 이어야 합니다: %d".formatted(MIN_TOTAL, MAX_TOTAL, total));
        }
        if (total > maxFilesPerRun) {
            throw new IllegalArgumentException("합계 %d 가 배치 1회 상한(voice.batch.max-files-per-run=%d)보다 많습니다"
                    .formatted(total, maxFilesPerRun));
        }
        if (sttPercent == null || sttPercent < 0 || sttPercent > 100) {
            throw new IllegalArgumentException("기 STT 존재 비율은 0~100% 이어야 합니다: " + sttPercent);
        }
    }

    /** 건당 STT 처리 시간 — 방식 · 트랙별 시간 · 변동 폭 · 타임아웃. */
    static void validateLoad(String latencyMode, Long meetMs, Long phoneMs, Integer jitter, Long timeoutMs) {
        try {
            MockSttLatency.Mode.valueOf(latencyMode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지연 방식은 FIXED(고정) 또는 RANGE(범위)여야 합니다: " + latencyMode);
        }
        checkLatency("접견", meetMs);
        checkLatency("전화", phoneMs);
        if (jitter == null || jitter < 0 || jitter > 100) {
            throw new IllegalArgumentException("변동 폭은 0~100% 이어야 합니다: " + jitter);
        }
        if (timeoutMs != null && (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException("STT 타임아웃은 %d~%dms 이어야 합니다(비우면 적용 안 함): %d"
                    .formatted(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, timeoutMs));
        }
    }

    /** 워커 수 — 1~{@link #MAX_WORKERS}. */
    static void validateWorkers(String what, Integer n) {
        if (n == null || n < 1 || n > MAX_WORKERS) {
            throw new IllegalArgumentException("%s 는 1~%d 이어야 합니다: %s".formatted(what, MAX_WORKERS, n));
        }
    }

    private static void checkLatency(String what, Long ms) {
        if (ms == null || ms < 0 || ms > MAX_LATENCY_MS) {
            throw new IllegalArgumentException("%s 건당 STT 처리 시간은 0~%dms 이어야 합니다: %s".formatted(what, MAX_LATENCY_MS, ms));
        }
    }
}
