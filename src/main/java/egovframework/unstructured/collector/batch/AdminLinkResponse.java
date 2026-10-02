package egovframework.unstructured.collector.batch;

/**
 * admin 연동 응답 래퍼 — data-collector {@code Response} · admin-api 응답과 같은 모양
 * ({@code {"success":true,"code":0,"http_status_code":200,"result":...}}).
 * refresh 응답에만 쓴다(data-collector 와 같게). 바로 실행·재처리(202/409/400)는 data-collector 재처리처럼 평평한 본문이다.
 */
public record AdminLinkResponse<T>(boolean success, int code, int http_status_code, T result) {

    public static <T> AdminLinkResponse<T> of(T result) {
        return new AdminLinkResponse<>(true, 0, 200, result);
    }
}
