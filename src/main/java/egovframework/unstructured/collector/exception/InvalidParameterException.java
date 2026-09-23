package egovframework.unstructured.collector.exception;

import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ResponseStatus;
import egovframework.unstructured.collector.response.ResponseCode;

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
public class InvalidParameterException extends ExceptionBase {
    public InvalidParameterException(Logger l) {
        logger = l;
        errorCode = ResponseCode.INVALID_PARAMETER;
    }
    public InvalidParameterException(Logger l, @Nullable String message) {
        logger = l;
        errorCode = ResponseCode.INVALID_PARAMETER;
        this.additionalMessage = message;
    }

    @Override
    public int getStatusCode() {
        return HttpStatus.BAD_REQUEST.value();
    }
}
