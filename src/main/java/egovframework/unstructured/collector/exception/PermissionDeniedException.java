package egovframework.unstructured.collector.exception;

import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ResponseStatus;
import egovframework.unstructured.collector.response.ResponseCode;

@ResponseStatus(value = HttpStatus.FORBIDDEN)
public class PermissionDeniedException  extends ExceptionBase {
    public PermissionDeniedException(Logger l) {
        logger = l;
        errorCode = ResponseCode.NOT_ALLOWED;
    }
    public PermissionDeniedException(Logger l, @Nullable String message) {
        logger = l;
        errorCode = ResponseCode.NOT_ALLOWED;
        this.additionalMessage = message;
    }

    @Override
    public int getStatusCode() {
        return HttpStatus.FORBIDDEN.value();
    }
}
