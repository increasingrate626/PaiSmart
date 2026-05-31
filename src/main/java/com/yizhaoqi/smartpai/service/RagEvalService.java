package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RagEvalService {

    public static final String NO_EVIDENCE = "NO_EVIDENCE";
    public static final String MISSING_EXPECTED_EVIDENCE = "MISSING_EXPECTED_EVIDENCE";
    public static final String MISSING_KEY_POINTS = "MISSING_KEY_POINTS";
    public static final String MISSING_EXPECTED_ENTITIES = "MISSING_EXPECTED_ENTITIES";
    public static final String ANSWER_GENERATION_FAILED = "ANSWER_GENERATION_FAILED";
    public static final String AGENT_EXCEPTION = "AGENT_EXCEPTION";

    private static final Logger logger = LoggerFactory.getLogger(RagEvalService.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final RagEvalCaseRepository caseRepository;
    private final RagEvalRunRepository runRepository;
    private final RagEvalCaseResultRepository resultRepository;
    private final AgenticRagService agenticRagService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagEvalService(RagEvalCaseRepository caseRepository,
                          RagEvalRunRepository runRepository,
                          RagEvalCaseResultRepository resultRepository,
                          AgenticRagService agenticRagService,
                          DeepSeekClient deepSeekClient) {
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.agenticRagService = agenticRagService;
        this.deepSeekClient = deepSeekClient;
    }

    @Transactional
    public RagEvalRun runEnabledCases(String userId, String note) {
        List<RagEvalCase> cases = caseRepository.findByEnabledTrue();
        RagEvalRun run = new RagEvalRun();
        run.setStartedAt(LocalDateTime.now());
        run.setTotalCases(cases.size());
        run.setNote(note);
        run = runRepository.save(run);

        int passed = 0;
        int failed = 0;
        double evidenceHitRateTotal = 0.0d;
        for (RagEvalCase evalCase : cases) {
            RagEvalCaseResult result;
            try {
                AgenticRagResult ragResult = agenticRagService.run(new AgenticRagRequest(
                        userId,
                        evalCase.getQuestion(),
                        List.of(),
                        "rag-eval-run-" + run.getId() + "-case-" + evalCase.getId()
                ));
                result = evaluate(run.getId(), evalCase, ragResult);
            } catch (Exception e) {
                logger.warn("RAG eval case failed: {}", evalCase.getId(), e);
                result = exceptionResult(run.getId(), evalCase, e);
            }

            resultRepository.save(result);
            evidenceHitRateTotal += result.getEvidenceHitRate();
            if (result.isPassed()) {
                passed++;
            } else {
                failed++;
            }
        }

        run.setPassedCases(passed);
        run.setFailedCases(failed);
        run.setAverageEvidenceHitRate(cases.isEmpty() ? 0.0d : evidenceHitRateTotal / cases.size());
        run.setQualityGatePassed(failed == 0);
        run.setFinishedAt(LocalDateTime.now());
        return runRepository.save(run);
    }

    RagEvalCaseResult evaluate(Long runId, RagEvalCase evalCase, AgenticRagResult ragResult) {
        List<SearchResult> evidence = ragResult.getSelectedEvidence() != null ? ragResult.getSelectedEvidence() : List.of();
        String finalContext = ragResult.getFinalContext() != null ? ragResult.getFinalContext() : "";
        String answer = deepSeekClient.completeAnswer(evalCase.getQuestion(), finalContext, List.of())
                .orElse("");

        EvidenceMatch evidenceMatch = matchEvidence(expectedEvidence(evalCase), evidence);
        String combinedText = combinedText(finalContext, answer, evidence, ragResult.getTrace());
        boolean keyPointsMatched = containsAll(combinedText, readList(evalCase.getExpectedKeyPointsJson()));
        boolean entityCoverageMatched = containsAll(combinedText, expectedEntities(evalCase));

        String failureReason = "";
        if (evidence.isEmpty()) {
            failureReason = NO_EVIDENCE;
        } else if (!evidenceMatch.matched()) {
            failureReason = MISSING_EXPECTED_EVIDENCE;
        } else if (answer.isBlank()) {
            failureReason = ANSWER_GENERATION_FAILED;
        } else if (!keyPointsMatched) {
            failureReason = MISSING_KEY_POINTS;
        } else if (!entityCoverageMatched) {
            failureReason = MISSING_EXPECTED_ENTITIES;
        }

        RagEvalCaseResult result = new RagEvalCaseResult();
        result.setRunId(runId);
        result.setCaseId(evalCase.getId());
        result.setEvidenceMatched(evidenceMatch.matched());
        result.setKeyPointsMatched(keyPointsMatched);
        result.setEntityCoverageMatched(entityCoverageMatched);
        result.setEvidenceHitRate(evidenceMatch.hitRate());
        result.setFailureReason(failureReason);
        result.setPassed(failureReason.isBlank());
        result.setAnswer(answer);
        result.setFinalContext(finalContext);
        result.setSelectedEvidenceJson(toJson(evidenceSummary(evidence)));
        result.setReferenceMappingJson(toJson(ragResult.getReferenceMapping() != null ? ragResult.getReferenceMapping() : Map.of()));
        result.setTraceJson(toJson(ragResult.getTrace() != null ? ragResult.getTrace() : List.of()));
        return result;
    }

    private RagEvalCaseResult exceptionResult(Long runId, RagEvalCase evalCase, Exception e) {
        RagEvalCaseResult result = new RagEvalCaseResult();
        result.setRunId(runId);
        result.setCaseId(evalCase.getId());
        result.setPassed(false);
        result.setEvidenceMatched(false);
        result.setKeyPointsMatched(false);
        result.setEntityCoverageMatched(false);
        result.setEvidenceHitRate(0.0d);
        result.setFailureReason(AGENT_EXCEPTION);
        result.setAnswer("");
        result.setFinalContext("");
        result.setSelectedEvidenceJson("[]");
        result.setReferenceMappingJson("{}");
        result.setTraceJson(toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportRunResults(Long runId) {
        RagEvalRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("RAG eval run not found: " + runId));
        List<RagEvalCaseResult> results = resultRepository.findByRunIdOrderByIdAsc(runId);
        List<Long> caseIds = results.stream()
                .map(RagEvalCaseResult::getCaseId)
                .toList();
        Map<Long, RagEvalCase> casesById = new LinkedHashMap<>();
        for (RagEvalCase evalCase : caseRepository.findAllById(caseIds)) {
            casesById.put(evalCase.getId(), evalCase);
        }

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("run", runSummary(run));
        List<Map<String, Object>> exportedCases = new ArrayList<>();
        for (RagEvalCaseResult result : results) {
            RagEvalCase evalCase = casesById.get(result.getCaseId());
            exportedCases.add(caseResultSummary(result, evalCase));
        }
        export.put("cases", exportedCases);
        return export;
    }

    @Transactional
    public RagEvalRun recordDeepEvalMetrics(Long runId, Map<String, Object> payload) {
        RagEvalRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("RAG eval run not found: " + runId));
        Map<String, Object> summary = asMap(payload.get("summary"));
        run.setAverageFaithfulness(asDouble(summary.get("averageFaithfulness")));
        run.setAverageCitationAccuracy(asDouble(summary.get("averageCitationAccuracy")));
        run.setAverageFixedVersionAccuracy(asDouble(summary.get("averageFixedVersionAccuracy")));
        run.setQualityGatePassed(Boolean.TRUE.equals(summary.get("qualityGatePassed")));

        Map<Long, Map<String, Object>> metricsByCaseId = new LinkedHashMap<>();
        for (Object item : asList(payload.get("cases"))) {
            Map<String, Object> caseMetrics = asMap(item);
            Long caseId = asLong(caseMetrics.get("caseId"));
            if (caseId != null) {
                metricsByCaseId.put(caseId, caseMetrics);
            }
        }

        List<RagEvalCaseResult> results = resultRepository.findByRunIdOrderByIdAsc(runId);
        for (RagEvalCaseResult result : results) {
            Map<String, Object> caseMetrics = metricsByCaseId.get(result.getCaseId());
            if (caseMetrics == null) {
                continue;
            }
            result.setFaithfulnessScore(asDouble(caseMetrics.get("faithfulnessScore")));
            result.setCitationAccuracy(asDouble(caseMetrics.get("citationAccuracy")));
            result.setFixedVersionAccuracy(asDouble(caseMetrics.get("fixedVersionAccuracy")));
            result.setMetricDetailsJson(toJson(caseMetrics));
        }
        resultRepository.saveAll(results);
        return runRepository.save(run);
    }

    private List<String> expectedEvidence(RagEvalCase evalCase) {
        return readList(evalCase.getExpectedEvidenceJson());
    }

    private List<String> expectedEntities(RagEvalCase evalCase) {
        List<String> values = new ArrayList<>();
        values.addAll(readList(evalCase.getExpectedComponentsJson()));
        values.addAll(readList(evalCase.getExpectedVersionsJson()));
        values.addAll(readList(evalCase.getExpectedCvesJson()));
        values.addAll(readList(evalCase.getExpectedProjectsJson()));
        values.addAll(readList(evalCase.getExpectedFixedVersionsJson()));
        return values;
    }

    private EvidenceMatch matchEvidence(List<String> expectedEvidence, List<SearchResult> actualEvidence) {
        if (expectedEvidence.isEmpty()) {
            return new EvidenceMatch(true, 1.0d);
        }
        Set<String> actualIds = new LinkedHashSet<>();
        for (SearchResult result : actualEvidence) {
            actualIds.add(chunkKey(result));
        }
        long matched = expectedEvidence.stream()
                .filter(expected -> actualIds.contains(expected.trim()))
                .count();
        return new EvidenceMatch(matched == expectedEvidence.size(), (double) matched / expectedEvidence.size());
    }

    private boolean containsAll(String text, List<String> expectedValues) {
        if (expectedValues.isEmpty()) {
            return true;
        }
        String haystack = text != null ? text.toLowerCase(Locale.ROOT) : "";
        return expectedValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .allMatch(value -> haystack.contains(value.toLowerCase(Locale.ROOT).trim()));
    }

    private String combinedText(String finalContext, String answer, List<SearchResult> evidence, List<AgentTraceStep> trace) {
        StringBuilder builder = new StringBuilder(finalContext != null ? finalContext : "");
        builder.append('\n').append(answer != null ? answer : "");
        for (SearchResult result : evidence) {
            builder.append('\n')
                    .append(result.getFileMd5()).append(':').append(result.getChunkId()).append(' ')
                    .append(result.getFileName()).append(' ')
                    .append(result.getTextContent());
        }
        if (trace != null) {
            for (AgentTraceStep step : trace) {
                builder.append('\n')
                        .append(step.getStage()).append(' ')
                        .append(step.getInputSummary()).append(' ')
                        .append(step.getOutputSummary()).append(' ')
                        .append(step.getFailureReason());
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> evidenceSummary(List<SearchResult> evidence) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (SearchResult result : evidence) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", chunkKey(result));
            item.put("fileMd5", result.getFileMd5());
            item.put("fileName", result.getFileName());
            item.put("score", result.getScore());
            item.put("textContent", result.getTextContent());
            item.put("ingestionTraceId", result.getIngestionTraceId());
            summary.add(item);
        }
        return summary;
    }

    private Map<String, Object> runSummary(RagEvalRun run) {
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

    private Map<String, Object> caseResultSummary(RagEvalCaseResult result, RagEvalCase evalCase) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultId", result.getId());
        data.put("caseId", result.getCaseId());
        data.put("question", evalCase != null ? evalCase.getQuestion() : "");
        data.put("passed", result.isPassed());
        data.put("failureReason", result.getFailureReason());
        data.put("answer", result.getAnswer() != null ? result.getAnswer() : "");
        data.put("finalContext", result.getFinalContext() != null ? result.getFinalContext() : "");
        data.put("selectedEvidence", readJsonValue(result.getSelectedEvidenceJson(), List.of()));
        data.put("referenceMapping", readJsonValue(result.getReferenceMappingJson(), Map.of()));
        data.put("trace", readJsonValue(result.getTraceJson(), List.of()));
        data.put("expectedEvidence", evalCase != null ? expectedEvidence(evalCase) : List.of());
        data.put("expectedKeyPoints", evalCase != null ? readList(evalCase.getExpectedKeyPointsJson()) : List.of());
        data.put("expectedComponents", evalCase != null ? readList(evalCase.getExpectedComponentsJson()) : List.of());
        data.put("expectedVersions", evalCase != null ? readList(evalCase.getExpectedVersionsJson()) : List.of());
        data.put("expectedCves", evalCase != null ? readList(evalCase.getExpectedCvesJson()) : List.of());
        data.put("expectedProjects", evalCase != null ? readList(evalCase.getExpectedProjectsJson()) : List.of());
        data.put("expectedFixedVersions", evalCase != null ? readList(evalCase.getExpectedFixedVersionsJson()) : List.of());
        data.put("faithfulnessScore", result.getFaithfulnessScore());
        data.put("citationAccuracy", result.getCitationAccuracy());
        data.put("fixedVersionAccuracy", result.getFixedVersionAccuracy());
        data.put("metricDetails", readJsonValue(result.getMetricDetailsJson(), Map.of()));
        return data;
    }

    private String chunkKey(SearchResult result) {
        return (result.getFileMd5() != null ? result.getFileMd5() : "")
                + ":"
                + (result.getChunkId() != null ? result.getChunkId() : "");
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (JsonProcessingException e) {
            logger.warn("Invalid RAG eval JSON list: {}", json, e);
            return List.of();
        }
    }

    private Object readJsonValue(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            logger.warn("Invalid RAG eval JSON value: {}", json, e);
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize RAG eval value", e);
            return "{}";
        }
    }

    private record EvidenceMatch(boolean matched, double hitRate) {
    }
}
