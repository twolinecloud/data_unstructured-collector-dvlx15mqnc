package egovframework.unstructured.collector.voice.batch;

/**
 * 재처리(Resume) 시작점 — 보존물이 없으면 앞 단계로 내려간다.
 *
 * <ul>
 *   <li>{@link #FULL} — 수집부터 전부</li>
 *   <li>{@link #FROM_ANALYZE} — STT 부터(보존된 복호화 오디오 {@code decrypted_*})</li>
 *   <li>{@link #FROM_DEIDENT} — 비식별부터(보존된 전사 {@code stt_temp}). 비식별 단계에서 깨진 건을 잇는다.
 *       <b>재시도 시점의 {@code deidentEnabled} 를 따른다</b> — 앞 회차가 비식별에서 깨졌어도 지금 {@code false} 면
 *       비식별을 다시 하지 않고 단순 전달(SEND)로 최종 저장까지 마감한다</li>
 *   <li>{@link #FROM_SEND} — 최종 저장부터(보존된 전사). 비식별 단계가 생긴 뒤로는 {@link #FROM_DEIDENT} 와 같다 —
 *       비식별된 텍스트는 보존하지 않으므로(PII 잔재) 전사에서 비식별 단계를 다시 지난다</li>
 * </ul>
 */
public enum ResumeMode {
    FULL,
    FROM_ANALYZE,
    FROM_DEIDENT,
    FROM_SEND;

    /** 보존된 전사(stt_temp)에서 시작하는가. */
    public boolean fromTranscript() {
        return this == FROM_DEIDENT || this == FROM_SEND;
    }
}
