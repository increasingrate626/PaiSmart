package com.yizhaoqi.smartpai.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void createsTraceIdsWithExpectedPrefix() {
        String traceId = TraceContext.createTraceId();

        assertTrue(traceId.startsWith("trc-"));
        assertTrue(traceId.length() > "trc-".length());
    }

    @Test
    void writesAndClearsTraceFieldsInMdc() {
        TraceContext.setTraceContext("trc-main", "trc-ingest", "trc-chat", "session-1", "user-1");

        assertEquals("trc-main", MDC.get(TraceContext.TRACE_ID));
        assertEquals("trc-ingest", MDC.get(TraceContext.INGESTION_TRACE_ID));
        assertEquals("trc-chat", MDC.get(TraceContext.CHAT_TRACE_ID));
        assertEquals("session-1", MDC.get(TraceContext.SESSION_ID));
        assertEquals("user-1", MDC.get(TraceContext.USER_ID));

        TraceContext.clearTraceContext();

        assertNull(MDC.get(TraceContext.TRACE_ID));
        assertNull(MDC.get(TraceContext.INGESTION_TRACE_ID));
        assertNull(MDC.get(TraceContext.CHAT_TRACE_ID));
    }

    @Test
    void logUtilsDoesNotClearExistingTraceContext() {
        TraceContext.setTraceContext("trc-main", "trc-ingest", "trc-chat", "session-1", "user-1");

        LogUtils.logBusiness("TRACE_TEST", "user-1", "trace context should survive");

        assertEquals("trc-main", MDC.get(TraceContext.TRACE_ID));
        assertEquals("trc-ingest", MDC.get(TraceContext.INGESTION_TRACE_ID));
        assertEquals("trc-chat", MDC.get(TraceContext.CHAT_TRACE_ID));
        assertEquals("session-1", MDC.get(TraceContext.SESSION_ID));
        assertEquals("user-1", MDC.get(TraceContext.USER_ID));
    }
}
