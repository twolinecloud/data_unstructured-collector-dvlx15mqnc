package egovframework.unstructured.collector.voice.batch;

/**
 * 재처리(Resume) 시작점 — 보존물이 없으면 앞 단계로 내려간다.
 *
 * <ul>
 *   <li>{@link #FULL} — 수집부터 전부</li>
 *   <li>{@link #FROM_ANALYZE} — STT 부터(보존된 복호화 오디오 {@code decrypted_*})</li>
 *   <li>{@link #FROM_SEND} — 제논 전송부터(보존된 전사 {@code stt_temp}). 전송에서 깨진 건을 STT 없이 다시 보낸다</li>
 * </ul>
 *
 * <p>2026-10-01 3단계 복원으로 비식별부터 잇던 {@code FROM_DEIDENT} 는 없앴다.</p>
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
