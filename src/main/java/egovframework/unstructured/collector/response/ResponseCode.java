package egovframework.unstructured.collector.response;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum ResponseCode {
    SUCCESS(0),

    // HTTP_CODE 400
    // InvalidParameterException
    INVALID_PARAMETER(1000),

    // HTTP_CODE 401
    // AuthException
    NO_AUTH_TOKEN(2001),
    INVALID_AUTH_TOKEN(2002),
    EXPIRED_AUTH_TOKEN(2003),
    UNKNOWN_AUTH_ERROR(2000),

    // HTTP_CODE 403
    // PermissionDeniedException
    NOT_ALLOWED(3000),

    // HTTP_CODE 404
    // NotFoundException
    NOT_FOUND(4000),

    // HTTP_CODE 405
    // UnavailableException
    METHOD_NOT_ALLOWED(5000),

    // HTTP_CODE 503
    SERVICE_UNAVAIABLE(6000),

    // HTTP_CODE 500
    UNKNOWN_ERROR(-1);

    private int code;

    private static final Map<Integer, ResponseCode> findByProfile =
    Collections.unmodifiableMap(Stream.of(values())
    .collect(Collectors.toMap(ResponseCode::getCode, Function.identity())));

    ResponseCode(int c) {
        this.code = c;
    }

    public int getCode() {
        return this.code;
    }

    public static ResponseCode valueOf(int code) {
        return Optional.ofNullable(findByProfile.get(code)).orElse(UNKNOWN_ERROR);
    }

    public String toString() {
        switch (this) {
            case SUCCESS: {
                return "ok";
            }
            case INVALID_PARAMETER: {
                return "Invalid Parameter";
            }
            case NO_AUTH_TOKEN: {
                return "No idToken";
            }
            case INVALID_AUTH_TOKEN: {
                return "Invalid idToken";
            }
            case EXPIRED_AUTH_TOKEN: {
                return "Expired idToken";
            }
            case UNKNOWN_AUTH_ERROR: {
                return "Unknown Auth Error";
            }
            case NOT_ALLOWED: {
                return "Not Allowed";
            }
            case NOT_FOUND: {
                return "Not Found";
            }
            case METHOD_NOT_ALLOWED: {
                return "Not Allowed Method";
            }
            case SERVICE_UNAVAIABLE: {
                return "Temporary Service Unavailable";
            }
            case UNKNOWN_ERROR: {
                return "Unknown Error";
            }
            default: {
                return "Unhandled error";
            }
        }
    }
}
