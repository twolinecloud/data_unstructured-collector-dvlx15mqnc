package egovframework.unstructured.collector.voice.batch;

/**
 * 재처리(Resume) 시작점 — 보존물이 없으면 앞 단계로 내려간다.
 *
 * <ul>
 *   <li>{@link #FULL} — 수집부터 전부</li>
 *   <li>{@link #FROM_ANALYZE} — STT 부터(보존된 복호화 오디오 {@code decrypted_*})</li>
 *   <li>{@link #FROM_SEND} — 에이전트 커넥터 전송부터(보존된 전사 {@code stt_temp}). 전송에서 깨진 건을 STT 없이 다시 보낸다</li>
 * </ul>
 *
 * <p>비정형 3단계({@link egovframework.unstructured.collector.common.model.StepType} — COLLECT · ANALYZE · SEND)와 1:1 이다.</p>
 */
public enum ResumeMode {
    FULL,
    FROM_ANALYZE,
    FROM_SEND;

    /** 보존된 전사(stt_temp)에서 시작하는가. */
    public boolean fromTranscript() {
        return this == FROM_SEND;
    }

    /**
     * 로그 컬렉터 reopen 에 넘길 시작 단계(T2 {@code STEP_TYPE_CD}) — <b>이 단계와 그 뒤만</b> 지워진다.
     *
     * <p>{@link #FULL} 은 null 이다(전부 다시). {@link #FROM_ANALYZE}·{@link #FROM_SEND} 는 앞 단계를 보존해야
     * 분석 노드가 비지 않고 수집 건수도 줄지 않는다(D17 · 2026-11-05).</p>
     *
     * <p>{@link #FROM_SEND} 를 {@code DEIDENT} 로 두는 것은 의도다 — 전사부터 다시 도는 경로가 비식별을 다시 하므로,
     * 전송만이 아니라 비식별 단계도 새로 쓴다. {@code SEND} 로 두면 옛 비식별 기록이 남아 건수가 겹친다.</p>
     */
    public String reopenFromStep() {
        return switch (this) {
            case FULL -> null;
            case FROM_ANALYZE -> "ANALYZE";
            case FROM_SEND -> "DEIDENT";
        };
    }
}
