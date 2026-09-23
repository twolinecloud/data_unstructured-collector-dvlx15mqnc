package egovframework.unstructured.collector.exception;

import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ResponseStatus;
import egovframework.unstructured.collector.response.ResponseCode;

@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
public class AuthException extends ExceptionBase {
    public AuthException(Logger l) {
        errorCode = ResponseCode.UNKNOWN_AUTH_ERROR;
        logger = l;
    }

    public AuthException(Logger l, ResponseCode _responseCode) {
        logger = l;
        errorCode = _responseCode;
    }

    public AuthException(Logger l, ResponseCode _responseCode, @Nullable String message) {
        logger = l;
        errorCode = _responseCode;
        this.additionalMessage = message;
    }

    @Override
    public int getStatusCode() {
        return HttpStatus.UNAUTHORIZED.value();
    }
}
