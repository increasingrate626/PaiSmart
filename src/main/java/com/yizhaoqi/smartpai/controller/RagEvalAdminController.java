package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.RagEvalRun;
import com.yizhaoqi.smartpai.service.RagEvalCaseImportResult;
import com.yizhaoqi.smartpai.service.RagEvalCaseImportService;
import com.yizhaoqi.smartpai.service.RagEvalCorpusImportResult;
import com.yizhaoqi.smartpai.service.RagEvalCorpusImportService;
import com.yizhaoqi.smartpai.service.RagEvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/rag-eval")
public class RagEvalAdminController {

    private final RagEvalCaseImportService importService;
    private final RagEvalCorpusImportService corpusImportService;
    private final RagEvalService ragEvalService;

    public RagEvalAdminController(RagEvalCaseImportService importService,
                                  RagEvalCorpusImportService corpusImportService,
                                  RagEvalService ragEvalService) {
        this.importService = importService;
        this.corpusImportService = corpusImportService;
        this.ragEvalService = ragEvalService;
    }

    @PostMapping("/cases/import-default")
    public ResponseEntity<?> importDefaultCases() {
        RagEvalCaseImportResult result = importService.importDefaultCases();
        return ResponseEntity.ok(response("Default SCA eval cases imported", importResultToMap(result)));
    }

    @PostMapping("/corpus/import-default")
    public ResponseEntity<?> importDefaultCorpus() {
        RagEvalCorpusImportResult result = corpusImportService.importDefaultCorpus();
        return ResponseEntity.ok(response("Default SCA eval corpus imported", corpusImportResultToMap(result)));
    }

    @PostMapping("/runs")
    public ResponseEntity<?> runEnabledCases(@RequestParam(defaultValue = "admin") String userId,
                                             @RequestParam(defaultValue = "") String note) {
        RagEvalRun run = ragEvalService.runEnabledCases(userId, note);
        return ResponseEntity.ok(response("RAG eval run finished", runToMap(run)));
    }

    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<?> exportRunResults(@PathVariable Long runId) {
        return ResponseEntity.ok(response("RAG eval run results exported", ragEvalService.exportRunResults(runId)));
    }

    @PostMapping("/runs/{runId}/metrics")
    public ResponseEntity<?> recordDeepEvalMetrics(@PathVariable Long runId,
                                                   @RequestBody Map<String, Object> payload) {
        RagEvalRun run = ragEvalService.recordDeepEvalMetrics(runId, payload);
        return ResponseEntity.ok(response("RAG eval metrics recorded", runToMap(run)));
    }

    private Map<String, Object> response(String message, Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> importResultToMap(RagEvalCaseImportResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", result.source());
        data.put("totalCases", result.totalCases());
        data.put("insertedCases", result.insertedCases());
        data.put("updatedCases", result.updatedCases());
        data.put("skippedCases", result.skippedCases());
        return data;
    }

    private Map<String, Object> corpusImportResultToMap(RagEvalCorpusImportResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", result.source());
        data.put("totalDocuments", result.totalDocuments());
        data.put("indexedChunks", result.indexedChunks());
        data.put("upsertedFiles", result.upsertedFiles());
        data.put("skippedDocuments", result.skippedDocuments());
        return data;
    }

    private Map<String, Object> runToMap(RagEvalRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", run.getId());
        data.put("startedAt", run.getStartedAt());
        data.put("finishedAt", run.getFinishedAt());
        data.put("totalCases", run.getTotalCases());
        data.put("passedCases", run.getPassedCases());
        data.put("failedCases", run.getFailedCases());
        data.put("averageEvidenceHitRate", run.getAverageEvidenceHitRate());
        data.put("averageFaithfulness", run.getAverageFaithfulness());
        data.put("averageCitationAccuracy", run.getAverageCitationAccuracy());
        data.put("averageFixedVersionAccuracy", run.getAverageFixedVersionAccuracy());
        data.put("qualityGatePassed", run.isQualityGatePassed());
        data.put("note", run.getNote());
        return data;
    }
}
