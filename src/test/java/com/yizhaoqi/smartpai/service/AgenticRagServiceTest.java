package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgentPlan;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import com.yizhaoqi.smartpai.entity.agent.EvidenceAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticRagServiceTest {

    @Mock
    private HybridSearchService searchService;

    @Mock
    private DeepSeekClient deepSeekClient;

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
        service = new AgenticRagService(searchService, deepSeekClient, aiProperties);
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

    private AgenticRagRequest request(String userId, String message) {
        return new AgenticRagRequest(userId, message, List.of(), "session-1");
    }

    private SearchResult result(String fileMd5, int chunkId, String text, double score, String fileName) {
        return new SearchResult(fileMd5, chunkId, text, score, "owner", "org", true, fileName);
    }
}
