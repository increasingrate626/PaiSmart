package com.yizhaoqi.smartpai.entity.agent;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AgenticRagRequest {
    private String userId;
    private String message;
    private List<Map<String, String>> history;
    private String sessionId;
    private String traceId;

    public AgenticRagRequest(String userId,
                             String message,
                             List<Map<String, String>> history,
                             String sessionId) {
        this(userId, message, history, sessionId, sessionId);
    }

    public AgenticRagRequest(String userId,
                             String message,
                             List<Map<String, String>> history,
                             String sessionId,
                             String traceId) {
        this.userId = userId;
        this.message = message;
        this.history = history;
        this.sessionId = sessionId;
        this.traceId = traceId;
    }
}
