package egovframework.unstructured.collector.utility;

import com.nimbusds.jwt.SignedJWT;
import egovframework.unstructured.collector.exception.AuthException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import egovframework.unstructured.collector.response.ResponseCode;

import java.text.ParseException;
import java.util.Map;

public class JWTUtils {
    static Logger logger = LogManager.getLogger(JWTUtils.class);

    private static Map<String, Object> parseJWT(String _token) throws ParseException {
        SignedJWT signedJWT = SignedJWT.parse(_token);
        return signedJWT.getJWTClaimsSet().getClaims();
    }

    public static String getClaim(String _token, String _key) throws AuthException {
        Map<String, Object> claims;
        try {
            claims = parseJWT(_token);
        } catch (ParseException e) {
            throw new AuthException(logger, ResponseCode.INVALID_AUTH_TOKEN);
        }
        return claims.get(_key).toString();
    }
}
