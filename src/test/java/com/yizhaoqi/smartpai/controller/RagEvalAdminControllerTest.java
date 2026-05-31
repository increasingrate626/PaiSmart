package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.RagEvalRun;
import com.yizhaoqi.smartpai.service.RagEvalCaseImportResult;
import com.yizhaoqi.smartpai.service.RagEvalCaseImportService;
import com.yizhaoqi.smartpai.service.RagEvalCorpusImportResult;
import com.yizhaoqi.smartpai.service.RagEvalCorpusImportService;
import com.yizhaoqi.smartpai.service.RagEvalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalAdminControllerTest {

    @Mock
    private RagEvalCaseImportService importService;

    @Mock
    private RagEvalService ragEvalService;

    @Mock
    private RagEvalCorpusImportService corpusImportService;

    private RagEvalAdminController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new RagEvalAdminController(importService, corpusImportService, ragEvalService);
    }

    @Test
    void importsDefaultScaCases() {
        when(importService.importDefaultCases()).thenReturn(new RagEvalCaseImportResult(
                "classpath:eval/sca_eval_cases.json",
                5,
                5,
                0,
                0
        ));

        ResponseEntity<?> response = controller.importDefaultCases();

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals(200, body.get("code"));
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(5, data.get("insertedCases"));
        verify(importService).importDefaultCases();
    }

    @Test
    void importsDefaultScaCorpus() {
        when(corpusImportService.importDefaultCorpus()).thenReturn(new RagEvalCorpusImportResult(
                "classpath:eval/sca_eval_corpus.json",
                5,
                5,
                5,
                0
        ));

        ResponseEntity<?> response = controller.importDefaultCorpus();

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals(200, body.get("code"));
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(5, data.get("indexedChunks"));
        assertEquals(5, data.get("upsertedFiles"));
        verify(corpusImportService).importDefaultCorpus();
    }

    @Test
    void runsEnabledCasesForRequestedUser() {
        RagEvalRun run = new RagEvalRun();
        run.setId(11L);
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(LocalDateTime.now());
        run.setTotalCases(5);
        run.setPassedCases(4);
        run.setFailedCases(1);
        run.setAverageEvidenceHitRate(0.8d);
        run.setNote("stage-one");
        when(ragEvalService.runEnabledCases("admin", "stage-one")).thenReturn(run);

        ResponseEntity<?> response = controller.runEnabledCases("admin", "stage-one");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals(200, body.get("code"));
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(11L, data.get("id"));
        assertEquals(5, data.get("totalCases"));
        assertEquals(4, data.get("passedCases"));
        assertEquals(1, data.get("failedCases"));
        verify(ragEvalService).runEnabledCases("admin", "stage-one");
    }

    @Test
    void exportsRunResultsForDeepEvalHarness() {
        when(ragEvalService.exportRunResults(11L)).thenReturn(Map.of(
                "run", Map.of("id", 11L, "totalCases", 1),
                "cases", List.of(Map.of(
                        "caseId", 1L,
                        "question", "Is log4j affected?",
                        "answer", "Use 2.15.0 (source#1: a.txt)",
                        "finalContext", "[1] log4j evidence",
                        "expectedEvidence", List.of("file-a:7")
                ))
        ));

        ResponseEntity<?> response = controller.exportRunResults(11L);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals(200, body.get("code"));
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(Map.of("id", 11L, "totalCases", 1), data.get("run"));
        verify(ragEvalService).exportRunResults(11L);
    }

    @Test
    void recordsDeepEvalMetrics() {
        RagEvalRun run = new RagEvalRun();
        run.setId(11L);
        run.setAverageFaithfulness(0.91d);
        run.setAverageCitationAccuracy(0.82d);
        run.setAverageFixedVersionAccuracy(1.0d);
        run.setQualityGatePassed(true);
        Map<String, Object> payload = Map.of("summary", Map.of("qualityGatePassed", true), "cases", List.of());
        when(ragEvalService.recordDeepEvalMetrics(11L, payload)).thenReturn(run);

        ResponseEntity<?> response = controller.recordDeepEvalMetrics(11L, payload);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(0.91d, data.get("averageFaithfulness"));
        assertEquals(true, data.get("qualityGatePassed"));
        verify(ragEvalService).recordDeepEvalMetrics(11L, payload);
    }
}
