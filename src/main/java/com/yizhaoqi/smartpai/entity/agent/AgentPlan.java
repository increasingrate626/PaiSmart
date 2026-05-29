package com.yizhaoqi.smartpai.entity.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentPlan {
    private String intent;
    private List<String> subQueries = new ArrayList<>();
    private String answerStrategy;
    private boolean needsClarification;
    private String decisionReason;
}
