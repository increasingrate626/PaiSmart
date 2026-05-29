package com.yizhaoqi.smartpai.utils;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String INGESTION_TRACE_ID = "ingestionTraceId";
    public static final String CHAT_TRACE_ID = "chatTraceId";
    public static final String REQUEST_ID = "requestId";
    public static final String SESSION_ID = "sessionId";
    public static final String USER_ID = "userId";
    public static final String OPERATION = "operation";

    private TraceContext() {
    }

    public static String createTraceId() {
        return "trc-" + UUID.randomUUID();
    }

    public static void setTraceId(String traceId) {
        putOrRemove(TRACE_ID, traceId);
    }

    public static void setIngestionTraceId(String ingestionTraceId) {
        putOrRemove(INGESTION_TRACE_ID, ingestionTraceId);
    }

    public static void setChatTraceId(String chatTraceId) {
        putOrRemove(CHAT_TRACE_ID, chatTraceId);
    }

    public static void setSessionId(String sessionId) {
        putOrRemove(SESSION_ID, sessionId);
    }

    public static void setUserId(String userId) {
        putOrRemove(USER_ID, userId);
    }

    public static void setRequestId(String requestId) {
        putOrRemove(REQUEST_ID, requestId);
    }

    public static void setTraceContext(String traceId,
                                       String ingestionTraceId,
                                       String chatTraceId,
                                       String sessionId,
                                       String userId) {
        setTraceId(traceId);
        setIngestionTraceId(ingestionTraceId);
        setChatTraceId(chatTraceId);
        setSessionId(sessionId);
        setUserId(userId);
    }

    public static void clearTraceContext() {
        MDC.remove(TRACE_ID);
        MDC.remove(INGESTION_TRACE_ID);
        MDC.remove(CHAT_TRACE_ID);
        MDC.remove(SESSION_ID);
        MDC.remove(USER_ID);
        MDC.remove(REQUEST_ID);
        MDC.remove(OPERATION);
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
