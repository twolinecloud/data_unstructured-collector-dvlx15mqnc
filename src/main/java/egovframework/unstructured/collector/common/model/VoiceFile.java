package egovframework.unstructured.collector.common.model;

import java.nio.file.Path;

/**
 * 수집 구간을 통과한 실제 파일.
 *
 * @param target   이 파일이 어느 대상의 것인지
 * @param path     로컬(또는 PV) 경로
 * @param sizeBytes 크기
 * @param format   추정 포맷(m4a/mp4/wav/unknown). ESB 수신 파일은 확장자가 {@code .DAT} 라
 *                 파일명으로 판별할 수 없어 매직 넘버와 원본 파일명으로 정한다.
 * @param decrypted 복호화를 거쳤는지
 */
public record VoiceFile(
        VoiceTarget target,
        Path path,
        long sizeBytes,
        String format,
        boolean decrypted
) {
    public VoiceFile withPath(Path newPath, long newSize, boolean nowDecrypted) {
        return new VoiceFile(target, newPath, newSize, format, nowDecrypted);
    }

    /**
     * 포맷만 바꾼 사본 — <b>복호화 직후 다시 판별한 값</b>을 얹는 데 쓴다.
     *
     * <p>수신 시점의 파일은 암호문이라 매직 넘버를 읽을 수 없어 확장자로 떨어진다(접견은 {@code m4a}).
     * 복호화하고 나서야 실제 내용이 드러나므로, 그때 다시 본 값으로 덮는다. 그러지 않으면
     * 내용은 WAV 인데 라벨은 m4a 인 채로 STT 에 넘어간다 — MOCK 은 무시하지만 NPU 는 그 값을
     * 그대로 받는다.</p>
     */
    public VoiceFile withFormat(String newFormat) {
        return new VoiceFile(target, path, sizeBytes, newFormat, decrypted);
    }
}
