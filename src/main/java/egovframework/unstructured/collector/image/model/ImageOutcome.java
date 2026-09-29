package egovframework.unstructured.collector.image.model;

/**
 * 사진 한 건의 처리 결과.
 *
 * @param status    SUCCESS · FAIL · SKIPPED
 * @param failedAt  실패한 단계(성공·건너뜀이면 null)
 * @param mapResult DB 매핑 결과 — INSERTED · UPDATED · STALE(더 최신 사진이 이미 매핑돼 있어 덮지 않음)
 * @param photoPath 저장된 경로(= 매핑 테이블의 photo_path)
 */
public record ImageOutcome(
        String corrNo,
        int imageSn,
        String fileKey,
        String status,
        ImageStage failedAt,
        String mapResult,
        String photoPath,
        long fileSize,
        String format,
        String errMsg,
        long elapsedMs
) {

    public static ImageOutcome success(ImageTarget t, String mapResult, String path, long size, String format, long ms) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "SUCCESS", null, mapResult, path, size, format, null, ms);
    }

    public static ImageOutcome fail(ImageTarget t, ImageStage at, String reason, long ms) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "FAIL", at, null, null, 0L, null, reason, ms);
    }

    public static ImageOutcome skipped(ImageTarget t, String reason) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "SKIPPED", null, null, null, 0L, null, reason, 0L);
    }

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
}
