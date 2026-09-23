package egovframework.unstructured.collector.utility;

import egovframework.unstructured.collector.exception.ExceptionBase;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;

public class LogUtils {
    public static String clientIP(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null) {
            ipAddress = request.getRemoteAddr();
        }
        return ipAddress;
    }

    public static void accessLog(Logger logger, HttpServletRequest request) {
        String method = String.format("%-6s", request.getMethod());
        logger.info("[{}] {} (IP={})", method, request.getRequestURI(), LogUtils.clientIP(request));
    }

    public static void errorLog(Logger logger, HttpServletRequest request) {
        String method = String.format("%-6s", request.getMethod());
        logger.error("[{}] {} (IP={})", method, request.getRequestURI(), LogUtils.clientIP(request));
    }

    public static void errorLog(Logger logger, HttpServletRequest request, ExceptionBase exception) {
        String method = String.format("%-6s", request.getMethod());
        logger.error("[{}] {} (IP={}) - {}", method, request.getRequestURI(), LogUtils.clientIP(request), exception.toString());
    }

    public static void errorLog(Logger logger, HttpServletRequest request, Exception exception) {
        String method = String.format("%-6s", request.getMethod());
        logger.error("[{}] {} (IP={}) - UNHANDLED EXCEPTION!!!! {}", method, request.getRequestURI(), LogUtils.clientIP(request), exception.getClass().getName());
    }

    public static void warnLog(Logger logger, HttpServletRequest request, Exception e) {
        String method = String.format("%-6s", request.getMethod());
        logger.warn("[{}] {} (IP={}) - Exception Name = {}", method, request.getRequestURI(), LogUtils.clientIP(request), e.getClass().getName());
    }
}
