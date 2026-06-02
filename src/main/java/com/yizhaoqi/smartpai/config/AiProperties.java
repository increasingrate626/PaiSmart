package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private Agentic agentic = new Agentic();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    @Data
    public static class Agentic {
        private boolean enabled = true;
        private Graph graph = new Graph();
        private int maxSearchRounds = 2;
        private int plannerTimeoutMs = 1500;
        private int evaluatorTimeoutMs = 1500;
        private int maxContextChars = 1600;
        private boolean emitTraceEvents = false;
        private boolean logLlmDecisions = false;
        private int logPromptPreviewChars = 300;
        private int logOutputPreviewChars = 1200;
        private int firstRoundTopK = 8;
        private int rewriteRoundTopK = 5;
        private int maxSubQueries = 2;
        private int maxFinalEvidenceCount = 6;
        private int maxEvaluatorEvidenceCount = 24;
        private int maxEvaluatorPromptChars = 6000;
    }

    @Data
    public static class Graph {
        private boolean enabled = true;
        private int maxDepth = 3;
        private int topK = 5;
        private boolean extractionEnabled = true;
    }
}
