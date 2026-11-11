package egovframework.unstructured.collector.common.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 업로드 한도 초과를 413 JSON 으로 — <b>컨트롤러 안의 {@code @ExceptionHandler} 로는 못 잡는다.</b>
 *
 * <p>multipart 는 디스패처가 핸들러를 고르기 전에 풀기 때문에 한도 초과가 컨트롤러에 닿지 않고, 그대로 두면
 * 500 HTML 오류 페이지가 나간다. 화면(시뮬레이터 9 · 11번 탭)이 사유를 보여 줄 수 있게 여기서 바꾼다.
 * 한도는 {@code spring.servlet.multipart.max-file-size}(기본 25MB).</p>
 */
@Log4j2
@RestControllerAdvice
public class UploadLimitAdvice {

    @Value("${spring.servlet.multipart.max-file-size:25MB}")
    private String maxFileSize;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        log.warn("[업로드] 한도 초과(413) — 한도 {}", maxFileSize);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "ok", false,
                "stage", "UPLOAD",
                "message", "업로드 한도(" + maxFileSize + ") 초과 — 서버에 닿기 전에 막혔다"));
    }
}
