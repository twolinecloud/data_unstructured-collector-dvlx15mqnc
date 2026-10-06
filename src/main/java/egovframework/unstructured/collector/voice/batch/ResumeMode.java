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
}
