package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import com.yizhaoqi.smartpai.utils.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class LoggingInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    private static final String START_TIME_ATTRIBUTE = "startTime";
    private static final String REQUEST_ID_ATTRIBUTE = "requestId";
    private static final String TRACE_ID_ATTRIBUTE = "traceId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long startTime = System.currentTimeMillis();
        request.setAttribute(START_TIME_ATTRIBUTE, startTime);

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String traceId = TraceContext.createTraceId();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader("X-Trace-Id", traceId);

        String userId = extractUserId(request);
        String sessionId = request.getSession(false) != null ? request.getSession().getId() : null;

        LogUtils.setRequestContext(requestId, userId, sessionId);
        TraceContext.setTraceId(traceId);

        String path = request.getRequestURI();
        if (isApiRequest(path)) {
            LogUtils.logBusiness("REQUEST_START", userId,
                    "Start request method=%s path=%s traceId=%s", request.getMethod(), path, traceId);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        try {
            Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                String userId = extractUserId(request);
                String path = request.getRequestURI();

                if (isApiRequest(path)) {
                    LogUtils.logApiCall(request.getMethod(), path, userId, response.getStatus(), duration);
                    if (ex != null) {
                        LogUtils.logBusinessError("REQUEST_ERROR", userId,
                                "Request failed method=%s path=%s traceId=%s",
                                ex, request.getMethod(), path, request.getAttribute(TRACE_ID_ATTRIBUTE));
                    }

                    if (duration > 3000) {
                        LogUtils.logPerformance("SLOW_REQUEST", duration,
                                String.format("method=%s path=%s userId=%s", request.getMethod(), path, userId));
                    }
                }
            }
        } finally {
            LogUtils.clearRequestContext();
        }
    }

    private String extractUserId(HttpServletRequest request) {
        try {
            String token = extractToken(request);
            if (token != null) {
                return jwtUtils.extractUserIdFromToken(token);
            }
        } catch (Exception e) {
            // Ignore token parsing errors for logging.
        }
        return "anonymous";
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private boolean isApiRequest(String path) {
        return path.startsWith("/api/") || path.startsWith("/chat/");
    }
}
