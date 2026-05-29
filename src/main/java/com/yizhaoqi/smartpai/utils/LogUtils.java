package com.yizhaoqi.smartpai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

public class LogUtils {

    private static final Logger BUSINESS_LOGGER = LoggerFactory.getLogger("com.yizhaoqi.smartpai.business");
    private static final Logger PERFORMANCE_LOGGER = LoggerFactory.getLogger("com.yizhaoqi.smartpai.performance");

    public static final String USER_ID = TraceContext.USER_ID;
    public static final String REQUEST_ID = TraceContext.REQUEST_ID;
    public static final String SESSION_ID = TraceContext.SESSION_ID;
    public static final String OPERATION = TraceContext.OPERATION;

    public static void logBusiness(String operation, String userId, String message, Object... args) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            MDC.put(OPERATION, operation);
            BUSINESS_LOGGER.info("[{}] [user:{}] {}", operation, userId, formatMessage(message, args));
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logBusinessError(String operation, String userId, String message, Throwable throwable, Object... args) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            MDC.put(OPERATION, operation);
            BUSINESS_LOGGER.error("[{}] [user:{}] {}", operation, userId, formatMessage(message, args), throwable);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logPerformance(String operation, long duration, String details) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            MDC.put(OPERATION, operation);
            PERFORMANCE_LOGGER.info("[performance] [{}] durationMs={} {}", operation, duration, details);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logUserOperation(String userId, String operation, String resource, String result) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            MDC.put(OPERATION, operation);
            BUSINESS_LOGGER.info("[user_operation] userId={} operation={} resource={} result={}", userId, operation, resource, result);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logApiCall(String method, String path, String userId, int statusCode, long duration) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            MDC.put(OPERATION, "API_CALL");
            BUSINESS_LOGGER.info("[api] method={} path={} userId={} status={} durationMs={}", method, path, userId, statusCode, duration);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logFileOperation(String userId, String operation, String fileName, String fileMd5, String result) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            MDC.put(OPERATION, "FILE_" + operation);
            BUSINESS_LOGGER.info("[file_operation] userId={} operation={} fileName={} fileMd5={} result={}",
                    userId, operation, fileName, fileMd5, result);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logChat(String userId, String sessionId, String messageType, int messageLength) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            TraceContext.setUserId(userId);
            TraceContext.setSessionId(sessionId);
            MDC.put(OPERATION, "CHAT");
            BUSINESS_LOGGER.info("[chat] userId={} sessionId={} type={} length={}",
                    userId, sessionId, messageType, messageLength);
        } finally {
            restoreContext(previousContext);
        }
    }

    public static void logSystemStart(String component, String status, String details) {
        BUSINESS_LOGGER.info("[system_start] component={} status={} {}", component, status, details);
    }

    public static void logSystemError(String component, String error, Throwable throwable) {
        BUSINESS_LOGGER.error("[system_error] component={} error={}", component, error, throwable);
    }

    public static void setRequestContext(String requestId, String userId, String sessionId) {
        TraceContext.setRequestId(requestId);
        TraceContext.setUserId(userId);
        TraceContext.setSessionId(sessionId);
    }

    public static void clearRequestContext() {
        TraceContext.clearTraceContext();
    }

    private static String formatMessage(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        try {
            return String.format(message, args);
        } catch (Exception e) {
            return message + " [format_error " + e.getMessage() + "]";
        }
    }

    private static void restoreContext(Map<String, String> previousContext) {
        if (previousContext == null || previousContext.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    public static class PerformanceMonitor {
        private final String operation;
        private final long startTime;

        public PerformanceMonitor(String operation) {
            this.operation = operation;
            this.startTime = System.currentTimeMillis();
        }

        public void end() {
            end("");
        }

        public void end(String details) {
            long duration = System.currentTimeMillis() - startTime;
            logPerformance(operation, duration, details);
        }
    }

    public static PerformanceMonitor startPerformanceMonitor(String operation) {
        return new PerformanceMonitor(operation);
    }
}
