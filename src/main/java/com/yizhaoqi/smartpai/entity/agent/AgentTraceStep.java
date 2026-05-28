package com.yizhaoqi.smartpai.entity.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceStep {
    private String stage;
    private long durationMs;
    private String inputSummary;
    private String outputSummary;
    private String failureReason;
}
