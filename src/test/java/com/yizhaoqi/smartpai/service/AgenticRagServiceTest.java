package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import com.yizhaoqi.smartpai.entity.agent.AgentPlan;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import com.yizhaoqi.smartpai.entity.agent.EvidenceAssessment;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchRequest;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticRagServiceTest {

    @Mock
    private HybridSearchService searchService;

    @Mock
    private DeepSeekClient deepSeekClient;

    @Mock
    private GraphSearchService graphSearchService;

    @Mock
    private ScaEntityExtractionService scaEntityExtractionService;

    private AiProperties aiProperties;
    private AgenticRagService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aiProperties = new AiProperties();
        aiProperties.getAgentic().setEnabled(true);
        aiProperties.getAgentic().setMaxSearchRounds(2);
        aiProperties.getAgentic().setPlannerTimeoutMs(1000);
        aiProperties.getAgentic().setEvaluatorTimeoutMs(1000);
        aiProperties.getAgentic().setFirstRoundTopK(12);
        aiProperties.getAgentic().setRewriteRoundTopK(8);
        aiProperties.getAgentic().setMaxContextChars(220);
        when(scaEntityExtractionService.extract(anyString())).thenReturn(new AgentEntities());
        service = new AgenticRagService(searchService, graphSearchService, deepSeekClient, aiProperties, scaEntityExtractionService);
    }

    @Test
    void aiPropertiesUseProductionSafeAgenticRecallDefaults() {
        AiProperties defaults = new AiProperties();

        assertEquals(2, defaults.getAgentic().getMaxSubQueries());
        assertEquals(8, defaults.getAgentic().getFirstRoundTopK());
        assertEquals(5, defaults.getAgentic().getRewriteRoundTopK());
        assertEquals(5, defaults.getAgentic().getGraph().getTopK());
    }

    @Test
    void fallsBackToSinglePermissionSearchWhenPlannerFails() {
        SearchResult result = result("file-a", 1, "权限内文档片段", 0.9, "a.txt");
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.empty());
        when(searchService.searchWithPermission("原始问题", "alice", 12)).thenReturn(List.of(result));

        AgenticRagResult ragResult = service.run(request("alice", "原始问题"));

        assertEquals(1, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("[1] (a.txt | MD5:file-a)"));
        assertEquals(Map.of(1, "file-a"), ragResult.getReferenceMapping());
        assertTrue(ragResult.getTrace().stream().anyMatch(step -> "FALLBACK_SEARCH".equals(step.getStage())));
        verify(searchService).searchWithPermission("原始问题", "alice", 12);
        verify(deepSeekClient, never()).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void performsRewriteSearchWhenEvidenceIsInsufficient() {
        AgentPlan plan = new AgentPlan();
        plan.setIntent("compare");
        plan.setSubQueries(List.of("问题A", "问题B"));
        plan.setAnswerStrategy("先分别检索，再综合回答");

        EvidenceAssessment insufficient = new EvidenceAssessment();
        insufficient.setSufficient(false);
        insufficient.setMissingAspects(List.of("缺少B"));
        insufficient.setRewriteQueries(List.of("问题B 补充"));

        EvidenceAssessment sufficient = new EvidenceAssessment();
        sufficient.setSufficient(true);
        sufficient.setSelectedChunkIds(List.of("file-a:1", "file-b:2"));

        SearchResult first = result("file-a", 1, "A 的证据", 0.8, "a.txt");
        SearchResult second = result("file-b", 2, "B 的补充证据", 0.95, "b.txt");

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class)))
                .thenReturn(Optional.of(insufficient))
                .thenReturn(Optional.of(sufficient));
        when(searchService.searchWithPermission("问题A", "alice", 12)).thenReturn(List.of(first));
        when(searchService.searchWithPermission("问题B", "alice", 12)).thenReturn(List.of());
        when(searchService.searchWithPermission("问题B 补充", "alice", 8)).thenReturn(List.of(second));

        AgenticRagResult ragResult = service.run(request("alice", "原始问题"));

        assertEquals(2, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("A 的证据"));
        assertTrue(ragResult.getFinalContext().contains("B 的补充证据"));
        assertEquals("file-b", ragResult.getReferenceMapping().get(2));
        verify(searchService).searchWithPermission("问题A", "alice", 12);
        verify(searchService).searchWithPermission("问题B", "alice", 12);
        verify(searchService).searchWithPermission("问题B 补充", "alice", 8);
    }

    @Test
    void deduplicatesEvidenceAndHonorsContextBudget() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("预算问题"));

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        SearchResult duplicateLow = result("file-a", 1, "低分重复证据", 0.2, "a.txt");
        SearchResult duplicateHigh = result("file-a", 1, "高分重复证据", 0.9, "a.txt");
        SearchResult longResult = result("file-b", 2, "这是一个很长的证据片段，用来验证最终上下文会按照预算截断，避免把过多内容塞进模型提示词。", 0.8, "b.txt");

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("预算问题", "alice", 12))
                .thenReturn(List.of(duplicateLow, duplicateHigh, longResult));

        AgenticRagResult ragResult = service.run(request("alice", "原始问题"));

        assertEquals(2, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().length() <= aiProperties.getAgentic().getMaxContextChars());
        assertTrue(ragResult.getFinalContext().contains("高分重复证据"));
        assertEquals(Map.of(1, "file-a", 2, "file-b"), ragResult.getReferenceMapping());
    }

    @Test
    void returnsEmptyContextWhenFallbackSearchFails() {
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.empty());
        when(searchService.searchWithPermission("原始问题", "alice", 12)).thenThrow(new RuntimeException("es down"));

        AgenticRagResult ragResult = service.run(request("alice", "原始问题"));

        assertEquals("", ragResult.getFinalContext());
        assertTrue(ragResult.getSelectedEvidence().isEmpty());
        assertTrue(ragResult.getReferenceMapping().isEmpty());
        assertTrue(ragResult.getTrace().stream().anyMatch(step -> "FALLBACK_SEARCH".equals(step.getStage())));
        verify(deepSeekClient, never()).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void usesRankedEvidenceWhenEvaluatorFails() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("agentic fallback"));

        SearchResult result = result("file-a", 1, "usable evidence", 0.8, "a.txt");
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.empty());
        when(searchService.searchWithPermission("agentic fallback", "alice", 12)).thenReturn(List.of(result));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

        assertEquals(1, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("usable evidence"));
        assertTrue(ragResult.getTrace().stream().anyMatch(step ->
                "EVALUATOR_FALLBACK".equals(step.getStage())
                        && "evaluator_empty_or_invalid".equals(step.getFailureReason())));
        verify(searchService, never()).searchWithPermission(anyString(), eq("alice"), eq(8));
    }

    @Test
    void continuesFirstRoundWhenOneSearchQueryFails() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("broken query", "working query"));

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        SearchResult result = result("file-a", 1, "working evidence", 0.9, "a.txt");
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("broken query", "alice", 12)).thenThrow(new RuntimeException("es shard down"));
        when(searchService.searchWithPermission("working query", "alice", 12)).thenReturn(List.of(result));

        AgenticRagResult ragResult = service.run(request("alice", "plain question"));

        assertEquals(1, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("working evidence"));
        assertTrue(ragResult.getTrace().stream().anyMatch(step ->
                "SEARCH".equals(step.getStage())
                        && step.getFailureReason().contains("es shard down")));
        verify(searchService).searchWithPermission("working query", "alice", 12);
    }

    @Test
    void keepsFirstRoundEvidenceWhenRewriteSearchFails() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("first query"));

        EvidenceAssessment insufficient = new EvidenceAssessment();
        insufficient.setSufficient(false);
        insufficient.setRewriteQueries(List.of("rewrite query"));

        SearchResult result = result("file-a", 1, "first round evidence", 0.8, "a.txt");
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(insufficient));
        when(searchService.searchWithPermission("first query", "alice", 12)).thenReturn(List.of(result));
        when(searchService.searchWithPermission("rewrite query", "alice", 8)).thenThrow(new RuntimeException("rewrite search down"));

        AgenticRagResult ragResult = service.run(request("alice", "plain question"));

        assertEquals(1, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("first round evidence"));
        assertTrue(ragResult.getTrace().stream().anyMatch(step ->
                "REWRITE_SEARCH".equals(step.getStage())
                        && step.getFailureReason().contains("rewrite search down")));
        verify(deepSeekClient, times(1)).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void marksSearchAllFailedWhenEveryFirstRoundQueryFails() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("broken a", "broken b"));

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(searchService.searchWithPermission("broken a", "alice", 12)).thenThrow(new RuntimeException("es a down"));
        when(searchService.searchWithPermission("broken b", "alice", 12)).thenThrow(new RuntimeException("es b down"));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

        assertEquals("", ragResult.getFinalContext());
        assertTrue(ragResult.getSelectedEvidence().isEmpty());
        assertTrue(ragResult.getReferenceMapping().isEmpty());
        assertTrue(ragResult.getTrace().stream().anyMatch(step ->
                "SEARCH_ALL_FAILED".equals(step.getStage())
                        && "first_round_search_all_failed".equals(step.getFailureReason())));
        verify(deepSeekClient, never()).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void writesFallbackAuditWhenDetailedDecisionLoggingIsEnabled() {
        Logger agentLogger = (Logger) LoggerFactory.getLogger(AgenticRagService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);

        try {
            aiProperties.getAgentic().setLogLlmDecisions(true);

            AgentPlan plan = new AgentPlan();
            plan.setSubQueries(List.of("agentic fallback"));
            SearchResult result = result("file-a", 1, "usable evidence", 0.8, "a.txt");

            when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.empty());
            when(searchService.searchWithPermission("agentic fallback", "alice", 12)).thenReturn(List.of(result));

            service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_fallback")
                            && message.contains("traceId=trace-123")
                            && message.contains("chatTraceId=trace-123")
                            && message.contains("stage=EVALUATOR_FALLBACK")
                            && message.contains("reason=evaluator_empty_or_invalid")
                            && message.contains("action=use_ranked_evidence")
                            && message.contains("evidenceCount=1")));
        } finally {
            agentLogger.detachAppender(appender);
        }
    }

    @Test
    void triggersGraphSearchWhenPlannerExtractsScaEntities() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("log4j cve"));
        AgentEntities entities = new AgentEntities();
        entities.setComponents(List.of("log4j-core"));
        entities.setVersions(List.of("2.14.1"));
        entities.setCves(List.of("CVE-2021-44228"));
        plan.setEntities(entities);

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        SearchResult textResult = result("file-doc", 1, "text evidence", 0.7, "doc.txt");
        GraphSearchResult graphResult = new GraphSearchResult();
        graphResult.setPathText("log4j-core 2.14.1 --AFFECTED_BY--> CVE-2021-44228 --FIXED_IN--> 2.15.0");
        graphResult.setFileMd5("file-graph");
        graphResult.setChunkId(2);
        graphResult.setScore(0.95d);
        graphResult.setFileName("advisory.txt");
        graphResult.setIngestionTraceId("trc-ingest-graph");

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("log4j cve", "alice", 12)).thenReturn(List.of(textResult));
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5))).thenReturn(List.of(graphResult));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "log4j 2.14.1 受影响吗", List.of(), "session-1", "trace-123"));

        assertTrue(ragResult.getFinalContext().contains("[GRAPH#1] log4j-core 2.14.1 --AFFECTED_BY--> CVE-2021-44228"));
        assertTrue(ragResult.getReferenceMapping().containsValue("file-graph"));
        verify(graphSearchService).searchWithPermission(any(), eq("alice"), eq(5));
    }

    @Test
    void doesNotTriggerGraphSearchWhenPlannerHasNoScaEntities() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("ordinary question"));

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("ordinary question", "alice", 12)).thenReturn(List.of(result("file-a", 1, "plain evidence", 0.8, "a.txt")));

        service.run(request("alice", "ordinary question"));

        verify(graphSearchService, never()).searchWithPermission(any(), anyString(), anyInt());
    }

    @Test
    void keepsTextEvidenceWhenGraphSearchFails() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("log4j cve"));
        AgentEntities entities = new AgentEntities();
        entities.setCves(List.of("CVE-2021-44228"));
        plan.setEntities(entities);

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("log4j cve", "alice", 12)).thenReturn(List.of(result("file-a", 1, "text evidence", 0.8, "a.txt")));
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5))).thenThrow(new RuntimeException("graph db down"));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "log4j cve", List.of(), "session-1", "trace-123"));

        assertTrue(ragResult.getFinalContext().contains("text evidence"));
        assertTrue(ragResult.getTrace().stream().anyMatch(step ->
                "GRAPH_SEARCH_FAILED".equals(step.getStage())
                        && step.getFailureReason().contains("graph db down")));
    }

    @Test
    void evaluatorPromptSeparatesGraphFactsFromDocExcerpts() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("log4j cve"));
        AgentEntities entities = new AgentEntities();
        entities.setCves(List.of("CVE-2021-44228"));
        plan.setEntities(entities);

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        SearchResult docResult = result("file-doc", 1, "DOC_ONLY log4j advisory excerpt", 0.7, "doc.txt");
        GraphSearchResult graphResult = graphResult("file-graph", 2, "[GRAPH_ONLY] log4j-core --AFFECTED_BY--> CVE-2021-44228");

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("log4j cve", "alice", 12)).thenReturn(List.of(docResult));
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5))).thenReturn(List.of(graphResult));

        service.run(new AgenticRagRequest("alice", "log4j cve", List.of(), "session-1", "trace-123"));

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).completeJson(systemPromptCaptor.capture(), userPromptCaptor.capture(), eq(EvidenceAssessment.class));

        String systemPrompt = systemPromptCaptor.getValue();
        String userPrompt = userPromptCaptor.getValue();
        assertTrue(systemPrompt.contains("GRAPH facts"));
        assertTrue(systemPrompt.contains("DOC excerpts"));
        assertTrue(systemPrompt.contains("conflict"));

        int graphSection = userPrompt.indexOf("GRAPH facts:");
        int docSection = userPrompt.indexOf("DOC excerpts:");
        int graphEvidence = userPrompt.indexOf("[GRAPH#1] [GRAPH_ONLY]");
        int docEvidence = userPrompt.indexOf("DOC_ONLY log4j advisory excerpt");

        assertTrue(graphSection >= 0);
        assertTrue(docSection > graphSection);
        assertTrue(graphEvidence > graphSection && graphEvidence < docSection);
        assertTrue(docEvidence > docSection);
    }

    @Test
    void evaluatorPromptKeepsEmptyGraphSectionWhenOnlyDocEvidenceExists() {
        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("ordinary question"));

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("ordinary question", "alice", 12))
                .thenReturn(List.of(result("file-a", 1, "DOC_ONLY ordinary evidence", 0.8, "a.txt")));

        service.run(request("alice", "ordinary question"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).completeJson(anyString(), userPromptCaptor.capture(), eq(EvidenceAssessment.class));

        String userPrompt = userPromptCaptor.getValue();
        assertTrue(userPrompt.contains("GRAPH facts:\n- none"));
        assertTrue(userPrompt.contains("DOC excerpts:"));
        assertTrue(userPrompt.indexOf("DOC_ONLY ordinary evidence") > userPrompt.indexOf("DOC excerpts:"));
    }

    @Test
    void writesAgentTraceStepsWithTraceIdToLogs() {
        Logger agentLogger = (Logger) LoggerFactory.getLogger(AgenticRagService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);

        try {
            SearchResult result = result("file-a", 1, "allowed evidence", 0.9, "a.txt");
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.empty());
            when(searchService.searchWithPermission("plain question", "alice", 12)).thenReturn(List.of(result));

            service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1"));

            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel().equals(Level.INFO)
                            && event.getFormattedMessage().contains("agentic_rag_trace")
                            && event.getFormattedMessage().contains("traceId=session-1")
                            && event.getFormattedMessage().contains("sessionId=session-1")
                            && event.getFormattedMessage().contains("userId=alice")
                            && event.getFormattedMessage().contains("stage=FALLBACK_SEARCH")));
        } finally {
            agentLogger.detachAppender(appender);
        }
    }

    @Test
    void writesStructuredLlmDecisionAuditLogsWithTraceId() {
        Logger agentLogger = (Logger) LoggerFactory.getLogger(AgenticRagService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);

        try {
            aiProperties.getAgentic().setLogLlmDecisions(true);

            AgentPlan plan = new AgentPlan();
            plan.setIntent("explain test document");
            plan.setSubQueries(List.of("agentic rag test document"));
            plan.setAnswerStrategy("answer from selected evidence");
            plan.setDecisionReason("question asks for key content, so retrieve the named test document");

            EvidenceAssessment assessment = new EvidenceAssessment();
            assessment.setSufficient(true);
            assessment.setSelectedChunkIds(List.of("file-a:1"));
            assessment.setDecisionReason("selected chunk directly describes the test document");

            SearchResult result = result("file-a", 1, "allowed evidence", 0.9, "a.txt");
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
            when(searchService.searchWithPermission("agentic rag test document", "alice", 12)).thenReturn(List.of(result));

            service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_llm_decision")
                            && message.contains("traceId=trace-123")
                            && message.contains("stage=PLAN_QUERY")
                            && message.contains("\"intent\":\"explain test document\"")
                            && message.contains("\"decisionReason\":\"question asks for key content")));
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_llm_decision")
                            && message.contains("traceId=trace-123")
                            && message.contains("stage=EVALUATE_EVIDENCE")
                            && message.contains("\"sufficient\":true")
                            && message.contains("\"decisionReason\":\"selected chunk directly describes")));
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_search_audit")
                            && message.contains("traceId=trace-123")
                            && message.contains("stage=SEARCH")
                            && message.contains("file-a:1")
                            && message.contains("a.txt")
                            && message.contains("trc-ingest-file-a")));
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_final_audit")
                            && message.contains("traceId=trace-123")
                            && message.contains("contextChars=")
                            && message.contains("referenceMapping={1=file-a}")));
        } finally {
            agentLogger.detachAppender(appender);
        }
    }

    @Test
    void skipsDuplicateRewriteQueriesAndAuditsWhenAllRewritesAlreadySearched() {
        Logger agentLogger = (Logger) LoggerFactory.getLogger(AgenticRagService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        agentLogger.addAppender(appender);

        try {
            aiProperties.getAgentic().setLogLlmDecisions(true);

            AgentPlan plan = new AgentPlan();
            plan.setIntent("explain");
            plan.setSubQueries(List.of("Agentic RAG Test"));

            EvidenceAssessment assessment = new EvidenceAssessment();
            assessment.setSufficient(false);
            assessment.setSelectedChunkIds(List.of("file-a:1"));
            assessment.setRewriteQueries(List.of(" agentic   rag   test "));

            SearchResult result = result("file-a", 1, "allowed evidence", 0.9, "a.txt");
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
            when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
            when(searchService.searchWithPermission("Agentic RAG Test", "alice", 12)).thenReturn(List.of(result));

            service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

            verify(searchService).searchWithPermission("Agentic RAG Test", "alice", 12);
            verify(searchService, never()).searchWithPermission(anyString(), eq("alice"), eq(8));
            verify(deepSeekClient, times(1)).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(message ->
                    message.contains("agentic_rag_rewrite_skipped")
                            && message.contains("traceId=trace-123")
                            && message.contains("chatTraceId=trace-123")
                            && message.contains("reason=duplicate_rewrite_queries")
                            && message.contains("agentic rag test")));
        } finally {
            agentLogger.detachAppender(appender);
        }
    }

    @Test
    void searchesOnlyNewRewriteQueriesWhenEvaluatorReturnsMixedDuplicates() {
        AgentPlan plan = new AgentPlan();
        plan.setIntent("explain");
        plan.setSubQueries(List.of("Agentic RAG Test"));

        EvidenceAssessment insufficient = new EvidenceAssessment();
        insufficient.setSufficient(false);
        insufficient.setRewriteQueries(List.of("agentic rag test", "Agentic RAG extra detail"));

        EvidenceAssessment sufficient = new EvidenceAssessment();
        sufficient.setSufficient(true);
        sufficient.setSelectedChunkIds(List.of("file-a:1", "file-b:2"));

        SearchResult first = result("file-a", 1, "first evidence", 0.8, "a.txt");
        SearchResult second = result("file-b", 2, "second evidence", 0.9, "b.txt");

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class)))
                .thenReturn(Optional.of(insufficient))
                .thenReturn(Optional.of(sufficient));
        when(searchService.searchWithPermission("Agentic RAG Test", "alice", 12)).thenReturn(List.of(first));
        when(searchService.searchWithPermission("Agentic RAG extra detail", "alice", 8)).thenReturn(List.of(second));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "plain question", List.of(), "session-1", "trace-123"));

        assertEquals(2, ragResult.getSelectedEvidence().size());
        verify(searchService).searchWithPermission("Agentic RAG Test", "alice", 12);
        verify(searchService).searchWithPermission("Agentic RAG extra detail", "alice", 8);
        verify(searchService, never()).searchWithPermission("agentic rag test", "alice", 8);
        verify(deepSeekClient, times(2)).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void ruleEntitiesTriggerGraphSearchWhenPlannerMissesEntities() {
        AgentEntities ruleEntities = new AgentEntities();
        ruleEntities.setComponents(List.of("log4j-core"));
        ruleEntities.setVersions(List.of("2.14.1"));
        ruleEntities.setCves(List.of("CVE-2021-44228"));
        when(scaEntityExtractionService.extract("log4j-core 2.14.1 affected?")).thenReturn(ruleEntities);

        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("log4j-core 2.14.1 affected"));

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("log4j-core 2.14.1 affected", "alice", 12))
                .thenReturn(List.of(result("file-a", 1, "text evidence", 0.8, "a.txt")));
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5)))
                .thenReturn(List.of(graphResult("file-graph", 2, "log4j-core --AFFECTED_BY--> CVE-2021-44228")));

        AgenticRagResult ragResult = service.run(new AgenticRagRequest("alice", "log4j-core 2.14.1 affected?", List.of(), "session-1", "trace-123"));

        assertTrue(ragResult.getFinalContext().contains("[GRAPH#1] log4j-core --AFFECTED_BY--> CVE-2021-44228"));
        ArgumentCaptor<GraphSearchRequest> captor = ArgumentCaptor.forClass(GraphSearchRequest.class);
        verify(graphSearchService).searchWithPermission(captor.capture(), eq("alice"), eq(5));
        assertEquals(List.of("log4j-core"), captor.getValue().getEntities().getComponents());
        assertEquals(List.of("2.14.1"), captor.getValue().getEntities().getVersions());
        assertEquals(List.of("CVE-2021-44228"), captor.getValue().getEntities().getCves());
    }

    @Test
    void mergesRuleEntitiesBeforePlannerEntities() {
        AgentEntities ruleEntities = new AgentEntities();
        ruleEntities.setComponents(List.of("log4j-core"));
        ruleEntities.setCves(List.of("CVE-2021-44228"));
        when(scaEntityExtractionService.extract(anyString())).thenReturn(ruleEntities);

        AgentEntities plannerEntities = new AgentEntities();
        plannerEntities.setComponents(List.of("LOG4J-CORE", "spring-core"));
        plannerEntities.setVersions(List.of("5.3.0"));

        AgentPlan plan = new AgentPlan();
        plan.setSubQueries(List.of("component impact"));
        plan.setEntities(plannerEntities);

        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("component impact", "alice", 12)).thenReturn(List.of());
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5))).thenReturn(List.of());

        service.run(request("alice", "component impact"));

        ArgumentCaptor<GraphSearchRequest> captor = ArgumentCaptor.forClass(GraphSearchRequest.class);
        verify(graphSearchService).searchWithPermission(captor.capture(), eq("alice"), eq(5));
        AgentEntities merged = captor.getValue().getEntities();
        assertEquals(List.of("log4j-core", "spring-core"), merged.getComponents());
        assertEquals(List.of("5.3.0"), merged.getVersions());
        assertEquals(List.of("CVE-2021-44228"), merged.getCves());
    }

    @Test
    void plannerFailureUsesRuleEntitiesForFallbackGraphEvidence() {
        AgentEntities ruleEntities = new AgentEntities();
        ruleEntities.setCves(List.of("CVE-2021-44228"));
        when(scaEntityExtractionService.extract(anyString())).thenReturn(ruleEntities);

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.empty());
        when(searchService.searchWithPermission("log4j cve", "alice", 12))
                .thenReturn(List.of(result("file-a", 1, "fallback text evidence", 0.8, "a.txt")));
        when(graphSearchService.searchWithPermission(any(), eq("alice"), eq(5)))
                .thenReturn(List.of(graphResult("file-graph", 2, "CVE-2021-44228 --FIXED_IN--> 2.15.0")));

        AgenticRagResult ragResult = service.run(request("alice", "log4j cve"));

        assertTrue(ragResult.getFinalContext().contains("fallback text evidence"));
        assertTrue(ragResult.getFinalContext().contains("[GRAPH#1] CVE-2021-44228 --FIXED_IN--> 2.15.0"));
        verify(graphSearchService).searchWithPermission(any(), eq("alice"), eq(5));
        verify(deepSeekClient, never()).completeJson(anyString(), anyString(), eq(EvidenceAssessment.class));
    }

    @Test
    void plannerFailureWithoutRuleEntitiesKeepsCurrentFallbackBehavior() {
        when(scaEntityExtractionService.extract(anyString())).thenReturn(new AgentEntities());
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.empty());
        when(searchService.searchWithPermission("ordinary question", "alice", 12))
                .thenReturn(List.of(result("file-a", 1, "fallback text evidence", 0.8, "a.txt")));

        AgenticRagResult ragResult = service.run(request("alice", "ordinary question"));

        assertTrue(ragResult.getFinalContext().contains("fallback text evidence"));
        verify(graphSearchService, never()).searchWithPermission(any(), anyString(), anyInt());
    }

    @Test
    void finalContextDefaultsToSixEvidenceWhenBudgetAllowsMore() {
        aiProperties.getAgentic().setMaxContextChars(5000);
        AgentPlan plan = plan("component evidence limit");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        List<SearchResult> results = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> result("file-" + i, i, "evidence-" + i, 1.0d - (i * 0.01d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("component evidence limit", "alice", 12)).thenReturn(results);

        AgenticRagResult ragResult = service.run(request("alice", "component evidence limit"));

        assertEquals(6, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("[6] (file-6.txt | MD5:file-6)"));
        assertFalse(ragResult.getFinalContext().contains("[7] (file-7.txt | MD5:file-7)"));
        assertEquals(Map.of(1, "file-1", 2, "file-2", 3, "file-3", 4, "file-4", 5, "file-5", 6, "file-6"),
                ragResult.getReferenceMapping());
    }

    @Test
    void finalEvidenceLimitCanBeConfiguredToThree() {
        aiProperties.getAgentic().setMaxContextChars(5000);
        aiProperties.getAgentic().setMaxFinalEvidenceCount(3);
        AgentPlan plan = plan("configured final limit");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        List<SearchResult> results = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> result("file-" + i, i, "configured-evidence-" + i, 1.0d - (i * 0.01d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("configured final limit", "alice", 12)).thenReturn(results);

        AgenticRagResult ragResult = service.run(request("alice", "configured final limit"));

        assertEquals(3, ragResult.getSelectedEvidence().size());
        assertTrue(ragResult.getFinalContext().contains("[3] (file-3.txt | MD5:file-3)"));
        assertFalse(ragResult.getFinalContext().contains("[4] (file-4.txt | MD5:file-4)"));
        assertEquals(Map.of(1, "file-1", 2, "file-2", 3, "file-3"), ragResult.getReferenceMapping());
    }

    @Test
    void evaluatorPromptUsesConfiguredEvidenceLimit() {
        aiProperties.getAgentic().setMaxContextChars(5000);
        AgentPlan plan = plan("evaluator evidence limit");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        List<SearchResult> results = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> result("file-" + i, i, "evaluator-evidence-" + i, 1.0d - (i * 0.001d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("evaluator evidence limit", "alice", 12)).thenReturn(results);

        service.run(request("alice", "evaluator evidence limit"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).completeJson(anyString(), userPromptCaptor.capture(), eq(EvidenceAssessment.class));
        String evaluatorPrompt = userPromptCaptor.getValue();
        assertTrue(evaluatorPrompt.contains("file-24:24"));
        assertFalse(evaluatorPrompt.contains("file-25:25"));
    }

    @Test
    void evaluatorPromptUsesConfiguredCharacterBudget() {
        aiProperties.getAgentic().setMaxEvaluatorPromptChars(360);
        AgentPlan plan = plan("evaluator prompt budget");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        List<SearchResult> results = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> result("file-" + i, i, "long-evaluator-evidence-" + i + " " + "x".repeat(200),
                        1.0d - (i * 0.01d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("evaluator prompt budget", "alice", 12)).thenReturn(results);

        service.run(request("alice", "evaluator prompt budget"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).completeJson(anyString(), userPromptCaptor.capture(), eq(EvidenceAssessment.class));
        String evaluatorPrompt = userPromptCaptor.getValue();
        assertTrue(evaluatorPrompt.length() <= 360);
        assertTrue(evaluatorPrompt.contains("Question:"));
        assertTrue(evaluatorPrompt.contains("Intent:"));
        assertTrue(evaluatorPrompt.contains("GRAPH facts:"));
        assertTrue(evaluatorPrompt.contains("DOC excerpts:"));
    }

    @Test
    void selectedChunkIdsAreAppliedBeforeFinalEvidenceLimit() {
        aiProperties.getAgentic().setMaxContextChars(5000);
        aiProperties.getAgentic().setMaxFinalEvidenceCount(3);
        AgentPlan plan = plan("selected chunk final limit");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        assessment.setSelectedChunkIds(List.of("file-5:5", "file-4:4", "file-3:3", "file-2:2", "file-1:1"));
        List<SearchResult> results = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> result("file-" + i, i, "selected-evidence-" + i, 1.0d - (i * 0.01d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("selected chunk final limit", "alice", 12)).thenReturn(results);

        AgenticRagResult ragResult = service.run(request("alice", "selected chunk final limit"));

        assertEquals(3, ragResult.getSelectedEvidence().size());
        assertEquals(List.of("file-5", "file-4", "file-3"),
                ragResult.getSelectedEvidence().stream().map(SearchResult::getFileMd5).toList());
        assertEquals(Map.of(1, "file-5", 2, "file-4", 3, "file-3"), ragResult.getReferenceMapping());
    }

    @Test
    void nonPositiveLimitsFallBackToSafeMinimums() {
        aiProperties.getAgentic().setMaxContextChars(5000);
        aiProperties.getAgentic().setMaxFinalEvidenceCount(0);
        aiProperties.getAgentic().setMaxEvaluatorEvidenceCount(0);
        aiProperties.getAgentic().setMaxEvaluatorPromptChars(0);
        AgentPlan plan = plan("safe minimum limits");
        EvidenceAssessment assessment = new EvidenceAssessment();
        assessment.setSufficient(true);
        List<SearchResult> results = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> result("file-" + i, i, "safe-minimum-evidence-" + i, 1.0d - (i * 0.01d), "file-" + i + ".txt"))
                .toList();

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(AgentPlan.class))).thenReturn(Optional.of(plan));
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(EvidenceAssessment.class))).thenReturn(Optional.of(assessment));
        when(searchService.searchWithPermission("safe minimum limits", "alice", 12)).thenReturn(results);

        AgenticRagResult ragResult = service.run(request("alice", "safe minimum limits"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).completeJson(anyString(), userPromptCaptor.capture(), eq(EvidenceAssessment.class));
        assertEquals(1, ragResult.getSelectedEvidence().size());
        assertTrue(userPromptCaptor.getValue().contains("Question:"));
        assertTrue(userPromptCaptor.getValue().contains("Intent:"));
        assertTrue(userPromptCaptor.getValue().contains("file-1:1"));
        assertFalse(userPromptCaptor.getValue().contains("file-2:2"));
    }

    private AgenticRagRequest request(String userId, String message) {
        return new AgenticRagRequest(userId, message, List.of(), "session-1");
    }

    private AgentPlan plan(String query) {
        AgentPlan plan = new AgentPlan();
        plan.setIntent("answer");
        plan.setSubQueries(List.of(query));
        return plan;
    }

    private GraphSearchResult graphResult(String fileMd5, int chunkId, String path) {
        GraphSearchResult graphResult = new GraphSearchResult();
        graphResult.setPathText(path);
        graphResult.setFileMd5(fileMd5);
        graphResult.setChunkId(chunkId);
        graphResult.setScore(0.95d);
        graphResult.setFileName("graph.txt");
        graphResult.setIngestionTraceId("trc-ingest-" + fileMd5);
        return graphResult;
    }

    private SearchResult result(String fileMd5, int chunkId, String text, double score, String fileName) {
        return new SearchResult(fileMd5, chunkId, text, score, "owner", "org", true, fileName, "trc-ingest-" + fileMd5);
    }
}
