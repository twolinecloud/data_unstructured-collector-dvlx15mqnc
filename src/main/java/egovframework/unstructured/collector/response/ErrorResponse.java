package egovframework.unstructured.collector.response;

import egovframework.unstructured.collector.exception.ExceptionBase;
import org.assertj.core.util.Strings;
import org.springframework.http.HttpStatus;

import java.util.HashMap;

public class ErrorResponse extends HashMap<String, Object> {
    public ErrorResponse(ExceptionBase exception) {
        super();
        this.put("error", true);
        this.put("http_status_code", exception.getStatusCode());
        this.put("error_code", exception.getErrorCode());
        this.put("error_message", exception.getErrorMessage());

        String message = exception.getAdditionalMessage();
        if (!Strings.isNullOrEmpty(message)) {
            this.put("message", message);
        }
    }

    public ErrorResponse() {
        super();
        this.put("error", true);
        this.put("http_status_code", HttpStatus.SERVICE_UNAVAILABLE.value());
        this.put("error_code", ResponseCode.UNKNOWN_ERROR);
        this.put("error_message", ResponseCode.UNKNOWN_ERROR.toString());
    }
}
