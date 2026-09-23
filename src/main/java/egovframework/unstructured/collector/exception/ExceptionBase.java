package egovframework.unstructured.collector.exception;

import org.apache.logging.log4j.Logger;
import org.assertj.core.util.Strings;
import egovframework.unstructured.collector.response.ResponseCode;

public abstract class ExceptionBase extends RuntimeException {
    public abstract int getStatusCode();
    protected String additionalMessage = null;
    protected ResponseCode errorCode;
    protected Logger logger;

    public int getErrorCode() {
        return errorCode.getCode();
    }

    public String getErrorMessage() {
        return errorCode.toString();
    }

    public String getAdditionalMessage() {
        return additionalMessage;
    }

    @Override
    public String getMessage() {
        return additionalMessage;
    }

    public String toString() {
        if(Strings.isNullOrEmpty(additionalMessage)) {
            return String.format("[%d], ERR_CODE=%d(%s)", getStatusCode(), errorCode.getCode(), errorCode.toString());
        } else {
            return String.format("[%d], ERR_CODE=%d(%s) : %s", getStatusCode(), errorCode.getCode(), errorCode.toString(), additionalMessage);
        }
    }
}
