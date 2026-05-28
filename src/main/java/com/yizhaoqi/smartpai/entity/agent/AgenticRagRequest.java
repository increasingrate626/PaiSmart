package com.yizhaoqi.smartpai.entity.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgenticRagRequest {
    private String userId;
    private String message;
    private List<Map<String, String>> history;
    private String sessionId;
}
