package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgentTraceStep;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import com.yizhaoqi.smartpai.model.RagEvalCase;
import com.yizhaoqi.smartpai.model.RagEvalCaseResult;
import com.yizhaoqi.smartpai.model.RagEvalRun;
import com.yizhaoqi.smartpai.repository.RagEvalCaseRepository;
import com.yizhaoqi.smartpai.repository.RagEvalCaseResultRepository;
import com.yizhaoqi.smartpai.repository.RagEvalRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalServiceTest {

    @Mock
    private RagEvalCaseRepository caseRepository;

    @Mock
    private RagEvalRunRepository runRepository;

    @Mock
    private RagEvalCaseResultRepository resultRepository;

    @Mock
    private AgenticRagService agenticRagService;

    private RagEvalService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagEvalService(caseRepository, runRepository, resultRepository, agenticRagService);
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(10L);
            }
            return run;
        });
        when(resultRepository.save(any(RagEvalCaseResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void enabledCasesCallAgentAndPersistRunAndResult() {
        RagEvalCase evalCase = caseWithExpectations();
        when(caseRepository.findByEnabledTrue()).thenReturn(List.of(evalCase));
        when(agenticRagService.run(any(AgenticRagRequest.class))).thenReturn(resultWithExpectedEvidence());

        RagEvalRun run = service.runEnabledCases("alice", "nightly");

        assertEquals(1, run.getTotalCases());
        assertEquals(1, run.getPassedCases());
        assertEquals(0, run.getFailedCases());
        assertEquals(1.0d, run.getAverageEvidenceHitRate());
        assertNotNull(run.getStartedAt());
        assertNotNull(run.getFinishedAt());
        assertEquals("nightly", run.getNote());

        ArgumentCaptor<AgenticRagRequest> requestCaptor = ArgumentCaptor.forClass(AgenticRagRequest.class);
        verify(agenticRagService).run(requestCaptor.capture());
        assertEquals("alice", requestCaptor.getValue().getUserId());
        assertEquals(evalCase.getQuestion(), requestCaptor.getValue().getMessage());

        ArgumentCaptor<RagEvalCaseResult> resultCaptor = ArgumentCaptor.forClass(RagEvalCaseResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        RagEvalCaseResult saved = resultCaptor.getValue();
        assertEquals(10L, saved.getRunId());
        assertEquals(1L, saved.getCaseId());
        assertTrue(saved.isPassed());
        assertTrue(saved.isEvidenceMatched());
        assertTrue(saved.isKeyPointsMatched());
        assertTrue(saved.isEntityCoverageMatched());
        assertEquals("", saved.getFailureReason());
        assertTrue(saved.getFinalContext().contains("CVE-2021-44228"));
        assertTrue(saved.getSelectedEvidenceJson().contains("file-a:7"));
        assertTrue(saved.getTraceJson().contains("PLAN_QUERY"));
    }

    @Test
    void missingExpectedEvidencePersistsFailureReason() {
        RagEvalCase evalCase = caseWithExpectations();
        when(caseRepository.findByEnabledTrue()).thenReturn(List.of(evalCase));
        when(agenticRagService.run(any(AgenticRagRequest.class))).thenReturn(new AgenticRagResult(
                "[1] unrelated context",
                List.of(new SearchResult("file-b", 1, "unrelated", 0.5d, "b.txt")),
                List.of(),
                Map.of(1, "file-b")
        ));

        RagEvalRun run = service.runEnabledCases("alice", "");

        assertEquals(0, run.getPassedCases());
        assertEquals(1, run.getFailedCases());

        ArgumentCaptor<RagEvalCaseResult> captor = ArgumentCaptor.forClass(RagEvalCaseResult.class);
        verify(resultRepository).save(captor.capture());
        assertFalse(captor.getValue().isPassed());
        assertEquals("MISSING_EXPECTED_EVIDENCE", captor.getValue().getFailureReason());
        assertEquals(0.0d, captor.getValue().getEvidenceHitRate());
    }

    @Test
    void missingKeyPointsPersistsFailureReason() {
        RagEvalCase evalCase = caseWithExpectations();
        evalCase.setExpectedEvidenceJson("[\"file-a:7\"]");
        evalCase.setExpectedKeyPointsJson("[\"fixed in 2.15.0\"]");
        when(caseRepository.findByEnabledTrue()).thenReturn(List.of(evalCase));
        when(agenticRagService.run(any(AgenticRagRequest.class))).thenReturn(new AgenticRagResult(
                "[1] log4j-core CVE-2021-44228 evidence",
                List.of(new SearchResult("file-a", 7, "log4j-core CVE-2021-44228 evidence", 0.9d, "a.txt")),
                List.of(),
                Map.of(1, "file-a")
        ));

        service.runEnabledCases("alice", "");

        ArgumentCaptor<RagEvalCaseResult> captor = ArgumentCaptor.forClass(RagEvalCaseResult.class);
        verify(resultRepository).save(captor.capture());
        assertFalse(captor.getValue().isPassed());
        assertEquals("MISSING_KEY_POINTS", captor.getValue().getFailureReason());
    }

    @Test
    void agentExceptionDoesNotStopWholeRun() {
        RagEvalCase failingCase = caseWithExpectations();
        RagEvalCase passingCase = caseWithExpectations();
        passingCase.setId(2L);
        passingCase.setQuestion("second case");
        when(caseRepository.findByEnabledTrue()).thenReturn(List.of(failingCase, passingCase));
        when(agenticRagService.run(any(AgenticRagRequest.class)))
                .thenThrow(new RuntimeException("llm down"))
                .thenReturn(resultWithExpectedEvidence());

        RagEvalRun run = service.runEnabledCases("alice", "batch");

        assertEquals(2, run.getTotalCases());
        assertEquals(1, run.getPassedCases());
        assertEquals(1, run.getFailedCases());

        ArgumentCaptor<RagEvalCaseResult> captor = ArgumentCaptor.forClass(RagEvalCaseResult.class);
        verify(resultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("AGENT_EXCEPTION", captor.getAllValues().get(0).getFailureReason());
        assertTrue(captor.getAllValues().get(1).isPassed());
    }

    private RagEvalCase caseWithExpectations() {
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setId(1L);
        evalCase.setEnabled(true);
        evalCase.setQuestion("Is log4j-core 2.14.1 affected by CVE-2021-44228?");
        evalCase.setExpectedComponentsJson("[\"log4j-core\"]");
        evalCase.setExpectedVersionsJson("[\"2.14.1\"]");
        evalCase.setExpectedCvesJson("[\"CVE-2021-44228\"]");
        evalCase.setExpectedFixedVersionsJson("[\"2.15.0\"]");
        evalCase.setExpectedEvidenceJson("[\"file-a:7\"]");
        evalCase.setExpectedKeyPointsJson("[\"CVE-2021-44228\", \"2.15.0\"]");
        evalCase.setTagsJson("[\"sca\", \"log4j\"]");
        return evalCase;
    }

    private AgenticRagResult resultWithExpectedEvidence() {
        return new AgenticRagResult(
                "[1] log4j-core 2.14.1 is affected by CVE-2021-44228 and fixed in 2.15.0",
                List.of(new SearchResult("file-a", 7, "log4j-core CVE-2021-44228 fixed in 2.15.0", 0.9d, "a.txt")),
                List.of(new AgentTraceStep("PLAN_QUERY", 1, "question", "entities=log4j-core,CVE-2021-44228", "")),
                Map.of(1, "file-a")
        );
    }
}
