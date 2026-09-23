package egovframework.unstructured.collector.response;

public record Response<T>(boolean success, int code, int http_status_code, T result) {

    public static Response<Void> ok() {
        return new Response<>(true, ResponseCode.SUCCESS.getCode(), 200, null);
    }

    public static <T> Response<T> of(T result) {
        return new Response<>(true, ResponseCode.SUCCESS.getCode(), 200, result);
    }
}
