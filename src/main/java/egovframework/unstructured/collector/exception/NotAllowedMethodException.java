package egovframework.unstructured.collector.exception;

import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ResponseStatus;
import egovframework.unstructured.collector.response.ResponseCode;

@ResponseStatus(value = HttpStatus.METHOD_NOT_ALLOWED)
public class NotAllowedMethodException extends ExceptionBase {
    public NotAllowedMethodException(Logger l) {
        logger = l;
        errorCode = ResponseCode.METHOD_NOT_ALLOWED;
    }
    public NotAllowedMethodException(Logger l, @Nullable String message) {
        logger = l;
        errorCode = ResponseCode.METHOD_NOT_ALLOWED;
        this.additionalMessage = message;
    }

    @Override
    public int getStatusCode() {
        return HttpStatus.METHOD_NOT_ALLOWED.value();
    }
}
