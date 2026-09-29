package egovframework.unstructured.collector.image.model;

/**
 * 사진 한 건의 처리 결과.
 *
 * @param status    SUCCESS · FAIL · SKIPPED
 * @param failedAt  실패한 단계(성공·건너뜀이면 null)
 * @param mapResult DB 매핑 결과 — INSERTED · UPDATED · STALE(더 최신 사진이 이미 매핑돼 있어 덮지 않음)
 * @param photoPath 저장된 경로(= 매핑 테이블의 photo_path)
 * @param injected  의도적 실패(6번 탭 실패 주입)로 실패했다 — 실제 오류와 나눠 센다
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
        long elapsedMs,
        boolean injected
) {

    public static ImageOutcome success(ImageTarget t, String mapResult, String path, long size, String format, long ms) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "SUCCESS", null, mapResult, path, size, format, null, ms, false);
    }

    public static ImageOutcome fail(ImageTarget t, ImageStage at, String reason, long ms) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "FAIL", at, null, null, 0L, null, reason, ms, false);
    }

    /** 의도적 실패(주입) — 실패 건수에는 들어가지만 리포트는 실제 오류와 나눠 보인다. */
    public static ImageOutcome injectedFail(ImageTarget t, ImageStage at, String reason, long ms) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "FAIL", at, null, null, 0L, null, reason, ms, true);
    }

    public static ImageOutcome skipped(ImageTarget t, String reason) {
        return new ImageOutcome(t.corrNo(), t.imageSn(), t.fileKey(), "SKIPPED", null, null, null, 0L, null, reason, 0L, false);
    }

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean isFail() {
        return "FAIL".equals(status);
    }
}
