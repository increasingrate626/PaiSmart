package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import com.yizhaoqi.smartpai.entity.agent.AgentPlan;
import com.yizhaoqi.smartpai.entity.agent.AgentTraceStep;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import com.yizhaoqi.smartpai.entity.agent.EvidenceAssessment;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchRequest;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class AgenticRagService {

    private static final Logger logger = LoggerFactory.getLogger(AgenticRagService.class);
    private static final int MAX_SNIPPET_LEN = 300;

    private final HybridSearchService searchService;
    private final GraphSearchService graphSearchService;
    private final DeepSeekClient deepSeekClient;
    private final AiProperties aiProperties;
    private final ScaEntityExtractionService scaEntityExtractionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgenticRagService(HybridSearchService searchService,
                             DeepSeekClient deepSeekClient,
                             AiProperties aiProperties) {
        this(searchService, null, deepSeekClient, aiProperties, null);
    }

    public AgenticRagService(HybridSearchService searchService,
                             GraphSearchService graphSearchService,
                             DeepSeekClient deepSeekClient,
                             AiProperties aiProperties) {
        this(searchService, graphSearchService, deepSeekClient, aiProperties, null);
    }

    @Autowired
    public AgenticRagService(HybridSearchService searchService,
                             GraphSearchService graphSearchService,
                             DeepSeekClient deepSeekClient,
                             AiProperties aiProperties,
                             ScaEntityExtractionService scaEntityExtractionService) {
        this.searchService = searchService;
        this.graphSearchService = graphSearchService;
        this.deepSeekClient = deepSeekClient;
        this.aiProperties = aiProperties;
        this.scaEntityExtractionService = scaEntityExtractionService;
    }

    public AgenticRagResult run(AgenticRagRequest request) {
        List<AgentTraceStep> trace = new ArrayList<>();
        AiProperties.Agentic cfg = aiProperties.getAgentic();

        if (!cfg.isEnabled()) {
            return fallbackSearch(request, trace, "agentic disabled");
        }

        AgentEntities ruleEntities = new AgentEntities();
        try {
            ruleEntities = extractRuleEntities(request);
            JsonStageResult<AgentPlan> planResult = callWithTimeout(
                    () -> deepSeekClient.completeJson(buildPlannerSystemPrompt(), buildPlannerUserPrompt(request), AgentPlan.class),
                    cfg.getPlannerTimeoutMs(),
                    "PLAN_QUERY",
                    request,
                    trace,
                    request.getMessage()
            );

            if (planResult.value().isEmpty()) {
                auditFallback(request, "PLAN_QUERY", planResult.failureReason(), "fallback_search", evidenceCount(List.of()));
                return fallbackSearch(request, trace, planResult.failureReason(), ruleEntities);
            }

            AgentPlan plan = planResult.value().get();
            AgentEntities mergedEntities = mergeEntities(ruleEntities, plan.getEntities());
            plan.setEntities(mergedEntities);
            auditRuleEntities(request, ruleEntities, mergedEntities);
            addTrace(trace, request, "PLAN_QUERY", 0, summarize(request.getMessage()), summarizePlan(plan), null);
            auditLlmDecision(request, "PLAN_QUERY", buildPlannerUserPrompt(request), plan);

            List<SearchResult> evidence = new ArrayList<>();
            Set<String> searchedQueryKeys = new LinkedHashSet<>();
            List<String> firstRoundQueries = filterNewQueries(
                    normalizeQueries(plan.getSubQueries(), request.getMessage(), cfg.getMaxSubQueries()),
                    searchedQueryKeys
            );
            SearchExecution firstRoundSearch = searchQueries(request, firstRoundQueries, cfg.getFirstRoundTopK(), evidence, trace, "SEARCH");
            searchGraphIfNeeded(request, plan, evidence, trace, cfg);
            if (evidence.isEmpty() && firstRoundSearch.allQueriesFailed()) {
                addTrace(trace, request, "SEARCH_ALL_FAILED", 0, summarize(firstRoundQueries.toString()), "results=0", "first_round_search_all_failed");
                auditFallback(request, "SEARCH_ALL_FAILED", "first_round_search_all_failed", "return_empty_context", evidenceCount(evidence));
                return buildResult(request, List.of(), null, trace, cfg.getMaxContextChars());
            }

            Optional<EvidenceAssessment> assessmentOptional = assessEvidence(request, plan, evidence, trace, cfg);
            EvidenceAssessment assessment = assessmentOptional.orElse(null);

            boolean canRewrite = assessment != null
                    && !assessment.isSufficient()
                    && cfg.getMaxSearchRounds() > 1
                    && assessment.getRewriteQueries() != null
                    && !assessment.getRewriteQueries().isEmpty();

            if (canRewrite) {
                List<String> rewriteCandidates = normalizeQueries(assessment.getRewriteQueries(), request.getMessage(), cfg.getMaxSubQueries());
                List<String> rewriteQueries = filterNewQueries(rewriteCandidates, searchedQueryKeys);
                if (rewriteQueries.isEmpty()) {
                    auditRewriteSkipped(request, rewriteCandidates);
                } else {
                    SearchExecution rewriteSearch = searchQueries(request, rewriteQueries, cfg.getRewriteRoundTopK(), evidence, trace, "REWRITE_SEARCH");
                    if (rewriteSearch.allQueriesFailed()) {
                        auditFallback(request, "REWRITE_SEARCH", "rewrite_search_all_failed", "use_first_round_evidence", evidenceCount(evidence));
                    } else {
                        assessment = assessEvidence(request, plan, evidence, trace, cfg).orElse(assessment);
                    }
                }
            }

            return buildResult(request, evidence, assessment, trace, cfg.getMaxContextChars());
        } catch (Exception e) {
            logger.error("Agentic RAG failed, falling back to normal RAG", e);
            return fallbackSearch(request, trace, e.getMessage(), ruleEntities);
        }
    }

    private Optional<EvidenceAssessment> assessEvidence(AgenticRagRequest request,
                                                        AgentPlan plan,
                                                        List<SearchResult> evidence,
                                                        List<AgentTraceStep> trace,
                                                        AiProperties.Agentic cfg) {
        List<SearchResult> evaluatorEvidence = limitEvidence(
                deduplicateAndSort(evidence),
                safePositive(cfg.getMaxEvaluatorEvidenceCount(), 1)
        );
        String userPrompt = buildEvaluatorUserPrompt(
                request,
                plan,
                evaluatorEvidence,
                safePositive(cfg.getMaxEvaluatorPromptChars(), 6000)
        );
        auditEvaluatorPrompt(request, evaluatorEvidence.size(), userPrompt.length());
        JsonStageResult<EvidenceAssessment> result = callWithTimeout(
                () -> deepSeekClient.completeJson(buildEvaluatorSystemPrompt(), userPrompt, EvidenceAssessment.class),
                cfg.getEvaluatorTimeoutMs(),
                "EVALUATE_EVIDENCE",
                request,
                trace,
                summarize(request.getMessage())
        );
        result.value().ifPresent(assessment ->
        {
            addTrace(trace, request, "EVALUATE_EVIDENCE", 0, summarize(request.getMessage()), summarizeAssessment(assessment), null);
            auditLlmDecision(request, "EVALUATE_EVIDENCE", userPrompt, assessment);
        });
        if (result.value().isEmpty()) {
            addTrace(trace, request, "EVALUATOR_FALLBACK", 0, summarize(request.getMessage()), "use_ranked_evidence", result.failureReason());
            auditFallback(request, "EVALUATOR_FALLBACK", result.failureReason(), "use_ranked_evidence", evidenceCount(evidence));
        }
        return result.value();
    }

    private SearchExecution searchQueries(AgenticRagRequest request,
                                          List<String> queries,
                                          int topK,
                                          List<SearchResult> evidence,
                                          List<AgentTraceStep> trace,
                                          String stage) {
        int failures = 0;
        int resultCount = 0;
        for (String query : queries) {
            long start = System.nanoTime();
            try {
                List<SearchResult> results = searchService.searchWithPermission(query, request.getUserId(), topK);
                evidence.addAll(results);
                resultCount += results.size();
                addTrace(trace, request, stage, elapsedMs(start), summarize(query), "results=" + results.size(), null);
                auditSearch(request, stage, query, results);
            } catch (Exception e) {
                failures++;
                addTrace(trace, request, stage, elapsedMs(start), summarize(query), "results=0", e.getMessage());
                auditFallback(request, stage, safeFailureReason(e), "continue_other_queries", evidenceCount(evidence));
                logger.warn("Agentic RAG search failed for query: {}", query, e);
            }
        }
        return new SearchExecution(queries.size(), failures, resultCount);
    }

    private void searchGraphIfNeeded(AgenticRagRequest request,
                                     AgentPlan plan,
                                     List<SearchResult> evidence,
                                     List<AgentTraceStep> trace,
                                     AiProperties.Agentic cfg) {
        if (graphSearchService == null
                || cfg.getGraph() == null
                || !cfg.getGraph().isEnabled()
                || plan.getEntities() == null
                || !plan.getEntities().hasAny()) {
            return;
        }

        long start = System.nanoTime();
        try {
            GraphSearchRequest graphRequest = new GraphSearchRequest(plan.getEntities(), cfg.getGraph().getMaxDepth());
            List<GraphSearchResult> graphResults = graphSearchService.searchWithPermission(
                    graphRequest,
                    request.getUserId(),
                    cfg.getGraph().getTopK()
            );
            List<SearchResult> graphEvidence = toGraphEvidence(graphResults, request);
            evidence.addAll(graphEvidence);
            addTrace(trace, request, "GRAPH_SEARCH", elapsedMs(start), summarize(plan.getEntities().toString()), "results=" + graphResults.size(), null);
            auditGraphSearch(request, plan, graphResults);
        } catch (Exception e) {
            addTrace(trace, request, "GRAPH_SEARCH_FAILED", elapsedMs(start), summarize(plan.getEntities().toString()), "results=0", safeFailureReason(e));
            auditFallback(request, "GRAPH_SEARCH_FAILED", safeFailureReason(e), "continue_text_evidence", evidenceCount(evidence));
            logger.warn("Agentic RAG graph search failed", e);
        }
    }

    private List<SearchResult> toGraphEvidence(List<GraphSearchResult> graphResults, AgenticRagRequest request) {
        List<SearchResult> converted = new ArrayList<>();
        if (graphResults == null) {
            return converted;
        }
        int index = 1;
        for (GraphSearchResult graphResult : graphResults) {
            String text = "[GRAPH#" + index + "] " + (graphResult.getPathText() != null ? graphResult.getPathText() : "");
            converted.add(new SearchResult(
                    graphResult.getFileMd5(),
                    graphResult.getChunkId(),
                    text,
                    graphResult.getScore(),
                    request.getUserId(),
                    null,
                    false,
                    graphResult.getFileName() != null ? graphResult.getFileName() : "graph",
                    graphResult.getIngestionTraceId()
            ));
            index++;
        }
        return converted;
    }

    private AgenticRagResult fallbackSearch(AgenticRagRequest request, List<AgentTraceStep> trace, String reason) {
        return fallbackSearch(request, trace, reason, null);
    }

    private AgenticRagResult fallbackSearch(AgenticRagRequest request,
                                            List<AgentTraceStep> trace,
                                            String reason,
                                            AgentEntities ruleEntities) {
        long start = System.nanoTime();
        try {
            List<SearchResult> results = searchService.searchWithPermission(
                    request.getMessage(),
                    request.getUserId(),
                    aiProperties.getAgentic().getFirstRoundTopK()
            );
            addTrace(trace, request, "FALLBACK_SEARCH", elapsedMs(start), summarize(request.getMessage()), "results=" + results.size(), reason);
            auditSearch(request, "FALLBACK_SEARCH", request.getMessage(), results);
            List<SearchResult> evidence = new ArrayList<>(results);
            if (ruleEntities != null && ruleEntities.hasAny()) {
                AgentPlan fallbackPlan = new AgentPlan();
                fallbackPlan.setEntities(ruleEntities);
                searchGraphIfNeeded(request, fallbackPlan, evidence, trace, aiProperties.getAgentic());
            }
            return buildResult(request, evidence, null, trace, aiProperties.getAgentic().getMaxContextChars());
        } catch (Exception e) {
            addTrace(trace, request, "FALLBACK_SEARCH", elapsedMs(start), summarize(request.getMessage()), "results=0", e.getMessage());
            logger.error("Agentic RAG fallback search failed", e);
            return buildResult(request, List.of(), null, trace, aiProperties.getAgentic().getMaxContextChars());
        }
    }

    private AgenticRagResult buildResult(AgenticRagRequest request,
                                         List<SearchResult> rawEvidence,
                                         EvidenceAssessment assessment,
                                         List<AgentTraceStep> trace,
                                         int budget) {
        List<SearchResult> deduped = deduplicateAndSort(rawEvidence);
        List<SearchResult> selected = selectEvidence(deduped, assessment);
        ContextBuildResult context = buildContext(
                selected,
                budget,
                safePositive(aiProperties.getAgentic().getMaxFinalEvidenceCount(), 1),
                evidenceCount(rawEvidence),
                deduped.size()
        );
        auditFinalContext(request, context);
        return new AgenticRagResult(context.context, context.includedEvidence, trace, context.referenceMapping);
    }

    private List<SearchResult> selectEvidence(List<SearchResult> deduped, EvidenceAssessment assessment) {
        if (assessment == null || assessment.getSelectedChunkIds() == null || assessment.getSelectedChunkIds().isEmpty()) {
            return deduped;
        }

        Map<String, SearchResult> byChunkId = deduped.stream()
                .collect(Collectors.toMap(this::chunkKey, r -> r, (left, right) -> left, LinkedHashMap::new));

        List<SearchResult> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String chunkId : assessment.getSelectedChunkIds()) {
            SearchResult result = byChunkId.get(chunkId);
            if (result != null && seen.add(chunkId)) {
                selected.add(result);
            }
        }

        return selected.isEmpty() ? deduped : selected;
    }

    private List<SearchResult> limitEvidence(List<SearchResult> evidence, int maxCount) {
        return evidence.stream()
                .limit(maxCount)
                .toList();
    }

    private List<SearchResult> deduplicateAndSort(List<SearchResult> rawEvidence) {
        Map<String, SearchResult> bestByChunk = new LinkedHashMap<>();
        for (SearchResult result : rawEvidence) {
            String key = chunkKey(result);
            SearchResult existing = bestByChunk.get(key);
            if (existing == null || score(result) > score(existing)) {
                bestByChunk.put(key, result);
            }
        }
        return bestByChunk.values().stream()
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .toList();
    }

    private ContextBuildResult buildContext(List<SearchResult> selected,
                                            int budget,
                                            int maxEvidenceCount,
                                            int rawEvidenceCount,
                                            int dedupedEvidenceCount) {
        StringBuilder context = new StringBuilder();
        List<SearchResult> includedEvidence = new ArrayList<>();
        Map<Integer, String> referenceMapping = new LinkedHashMap<>();

        int referenceNumber = 1;
        for (SearchResult result : selected) {
            if (includedEvidence.size() >= maxEvidenceCount) {
                break;
            }
            String fileName = result.getFileName() != null ? result.getFileName() : "unknown";
            String fileMd5 = result.getFileMd5() != null ? result.getFileMd5() : "";
            String snippet = result.getTextContent() != null ? result.getTextContent() : "";
            if (snippet.length() > MAX_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_SNIPPET_LEN);
            }

            String prefix = String.format("[%d] (%s | MD5:%s) ", referenceNumber, fileName, fileMd5);
            String suffix = "\n";
            int remaining = budget - context.length();
            if (remaining <= prefix.length() + suffix.length()) {
                break;
            }

            int allowedSnippetLength = remaining - prefix.length() - suffix.length();
            String finalSnippet = snippet;
            if (finalSnippet.length() > allowedSnippetLength) {
                finalSnippet = finalSnippet.substring(0, Math.max(0, allowedSnippetLength));
            }

            context.append(prefix).append(finalSnippet).append(suffix);
            includedEvidence.add(result);
            if (!fileMd5.isBlank()) {
                referenceMapping.put(referenceNumber, fileMd5);
            }
            referenceNumber++;
        }

        return new ContextBuildResult(context.toString(), includedEvidence, referenceMapping, rawEvidenceCount, dedupedEvidenceCount);
    }

    private <T> JsonStageResult<T> callWithTimeout(JsonCall<T> call,
                                                   int timeoutMs,
                                                   String stage,
                                                   AgenticRagRequest request,
                                                   List<AgentTraceStep> trace,
                                                   String inputSummary) {
        long start = System.nanoTime();
        try {
            Optional<T> result = CompletableFuture.supplyAsync(call::execute)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.isEmpty()) {
                addTrace(trace, request, stage, elapsedMs(start), summarize(inputSummary), "empty", "empty or invalid JSON");
                return new JsonStageResult<>(Optional.empty(), stageFailureReason(stage, "empty_or_invalid"));
            }
            return new JsonStageResult<>(result, "");
        } catch (TimeoutException e) {
            String reason = stageFailureReason(stage, "timeout");
            addTrace(trace, request, stage, elapsedMs(start), summarize(inputSummary), "empty", reason);
            logger.warn("Agentic RAG stage {} timed out", stage, e);
            return new JsonStageResult<>(Optional.empty(), reason);
        } catch (Exception e) {
            String reason = stageFailureReason(stage, "exception");
            addTrace(trace, request, stage, elapsedMs(start), summarize(inputSummary), "empty", safeFailureReason(e));
            logger.warn("Agentic RAG stage {} failed", stage, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new JsonStageResult<>(Optional.empty(), reason);
        }
    }

    private String stageFailureReason(String stage, String suffix) {
        if ("PLAN_QUERY".equals(stage)) {
            return "planner_" + suffix;
        }
        if ("EVALUATE_EVIDENCE".equals(stage)) {
            return "evaluator_" + suffix;
        }
        return stage.toLowerCase(Locale.ROOT) + "_" + suffix;
    }

    private String buildPlannerSystemPrompt() {
        return """
                You are the query planner for PaiSmart Agentic RAG.
                Return only a JSON object matching this schema:
                {"intent":"string","subQueries":["string"],"entities":{"components":["string"],"versions":["string"],"cves":["string"],"projects":["string"]},"answerStrategy":"string","needsClarification":false,"decisionReason":"string"}
                Split complex questions into at most four focused retrieval queries.
                Extract SCA entities when present, including component names, versions, CVE ids, and project names.
                decisionReason must be concise and auditable. Do not reveal hidden chain-of-thought.
                Do not answer the user.
                """;
    }

    private String buildPlannerUserPrompt(AgenticRagRequest request) {
        return "User question:\n" + request.getMessage();
    }

    private String buildEvaluatorSystemPrompt() {
        return """
                You are the evidence evaluator for PaiSmart Agentic RAG.
                Return only a JSON object matching this schema:
                {"sufficient":true,"missingAspects":[],"selectedChunkIds":["fileMd5:chunkId"],"rewriteQueries":[],"decisionReason":"string"}
                Mark sufficient=false when the evidence cannot answer the question.
                Use GRAPH facts for component, CVE, version, dependency, and fix-version relationships.
                Use DOC excerpts for original source support, surrounding context, constraints, and explanations.
                If GRAPH facts and DOC excerpts conflict, or either layer is insufficient for the requested answer, set sufficient=false or provide rewrite queries instead of forcing a conclusion.
                selectedChunkIds may include both GRAPH facts and DOC excerpts, always using fileMd5:chunkId.
                Rewrite queries must be retrieval queries only.
                decisionReason must be concise and auditable. Do not reveal hidden chain-of-thought.
                """;
    }

    private String buildEvaluatorUserPrompt(AgenticRagRequest request,
                                            AgentPlan plan,
                                            List<SearchResult> evidence,
                                            int maxPromptChars) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(request.getMessage()).append("\n\n");
        builder.append("Intent:\n").append(plan.getIntent()).append("\n\n");
        List<SearchResult> graphEvidence = evidence.stream()
                .filter(this::isGraphEvidence)
                .toList();
        List<SearchResult> docEvidence = evidence.stream()
                .filter(result -> !isGraphEvidence(result))
                .toList();
        appendEvidenceSection(builder, "GRAPH facts", graphEvidence, maxPromptChars);
        builder.append("\n");
        appendEvidenceSection(builder, "DOC excerpts", docEvidence, maxPromptChars);
        return truncate(builder.toString(), maxPromptChars);
    }

    private void appendEvidenceSection(StringBuilder builder, String title, List<SearchResult> evidence, int maxPromptChars) {
        builder.append(title).append(":\n");
        if (evidence.isEmpty()) {
            appendWithinBudget(builder, "- none\n", maxPromptChars);
            return;
        }
        for (SearchResult result : evidence) {
            String line = "- id=" + chunkKey(result)
                    + ", score=" + result.getScore()
                    + ", file=" + result.getFileName()
                    + ", text=" + summarize(result.getTextContent())
                    + "\n";
            if (!appendWithinBudget(builder, line, maxPromptChars)) {
                return;
            }
        }
    }

    private boolean appendWithinBudget(StringBuilder builder, String text, int maxChars) {
        if (builder.length() >= maxChars) {
            return false;
        }
        int remaining = maxChars - builder.length();
        if (text.length() <= remaining) {
            builder.append(text);
            return true;
        }
        builder.append(text, 0, Math.max(0, remaining));
        return false;
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars));
    }

    private boolean isGraphEvidence(SearchResult result) {
        return result != null
                && result.getTextContent() != null
                && result.getTextContent().trim().startsWith("[GRAPH#");
    }

    private List<String> normalizeQueries(List<String> queries, String fallback, int maxQueries) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (queries != null) {
            for (String query : queries) {
                if (query != null && !query.isBlank()) {
                    normalized.add(query.trim());
                }
                if (normalized.size() >= maxQueries) {
                    break;
                }
            }
        }
        if (normalized.isEmpty() && fallback != null && !fallback.isBlank()) {
            normalized.add(fallback.trim());
        }
        return new ArrayList<>(normalized);
    }

    private List<String> filterNewQueries(List<String> candidates, Set<String> searchedQueryKeys) {
        List<String> newQueries = new ArrayList<>();
        for (String query : candidates) {
            String key = normalizeQueryKey(query);
            if (!key.isBlank() && searchedQueryKeys.add(key)) {
                newQueries.add(query);
            }
        }
        return newQueries;
    }

    private AgentEntities extractRuleEntities(AgenticRagRequest request) {
        if (scaEntityExtractionService == null) {
            return new AgentEntities();
        }
        try {
            AgentEntities entities = scaEntityExtractionService.extract(request.getMessage());
            return entities != null ? entities : new AgentEntities();
        } catch (Exception e) {
            logger.warn("SCA rule entity extraction failed", e);
            return new AgentEntities();
        }
    }

    private AgentEntities mergeEntities(AgentEntities ruleEntities, AgentEntities plannerEntities) {
        AgentEntities merged = new AgentEntities();
        merged.setComponents(mergeEntityList(values(ruleEntities, EntityKind.COMPONENT), values(plannerEntities, EntityKind.COMPONENT)));
        merged.setVersions(mergeEntityList(values(ruleEntities, EntityKind.VERSION), values(plannerEntities, EntityKind.VERSION)));
        merged.setCves(mergeEntityList(values(ruleEntities, EntityKind.CVE), values(plannerEntities, EntityKind.CVE)));
        merged.setProjects(mergeEntityList(values(ruleEntities, EntityKind.PROJECT), values(plannerEntities, EntityKind.PROJECT)));
        return merged;
    }

    private List<String> values(AgentEntities entities, EntityKind kind) {
        if (entities == null) {
            return List.of();
        }
        return switch (kind) {
            case COMPONENT -> entities.getComponents();
            case VERSION -> entities.getVersions();
            case CVE -> entities.getCves();
            case PROJECT -> entities.getProjects();
        };
    }

    private List<String> mergeEntityList(List<String> ruleValues, List<String> plannerValues) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        addEntityValues(merged, ruleValues);
        addEntityValues(merged, plannerValues);
        return new ArrayList<>(merged.values());
    }

    private void addEntityValues(Map<String, String> merged, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().replaceAll("\\s+", " ");
            String key = normalized.toLowerCase(Locale.ROOT);
            merged.putIfAbsent(key, normalized);
        }
    }

    private String normalizeQueryKey(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String chunkKey(SearchResult result) {
        return (result.getFileMd5() != null ? result.getFileMd5() : "")
                + ":"
                + (result.getChunkId() != null ? result.getChunkId() : "");
    }

    private double score(SearchResult result) {
        return result.getScore() != null ? result.getScore() : 0.0d;
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private void addTrace(List<AgentTraceStep> trace,
                          AgenticRagRequest request,
                          String stage,
                          long durationMs,
                          String inputSummary,
                          String outputSummary,
                          String failureReason) {
        String safeInput = summarize(inputSummary);
        String safeOutput = summarize(outputSummary);
        String safeFailure = summarize(failureReason);
        trace.add(new AgentTraceStep(stage, durationMs, safeInput, safeOutput, safeFailure));
        logger.info(
                "agentic_rag_trace traceId={} chatTraceId={} sessionId={} userId={} stage={} durationMs={} inputSummary=\"{}\" outputSummary=\"{}\" failureReason=\"{}\"",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                stage,
                durationMs,
                safeInput,
                safeOutput,
                safeFailure
        );
    }

    private void auditLlmDecision(AgenticRagRequest request, String stage, String promptPreview, Object decision) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        logger.info(
                "agentic_rag_llm_decision traceId={} chatTraceId={} sessionId={} userId={} stage={} promptPreview=\"{}\" decisionJson={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                stage,
                preview(promptPreview, aiProperties.getAgentic().getLogPromptPreviewChars()),
                preview(toJson(decision), aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private void auditSearch(AgenticRagRequest request, String stage, String query, List<SearchResult> results) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        List<Map<String, Object>> hits = results.stream()
                .limit(10)
                .map(result -> {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("chunkId", chunkKey(result));
                    hit.put("fileName", result.getFileName());
                    hit.put("score", result.getScore());
                    hit.put("fileMd5", result.getFileMd5());
                    hit.put("ingestionTraceId", result.getIngestionTraceId());
                    return hit;
                })
                .toList();
        logger.info(
                "agentic_rag_search_audit traceId={} chatTraceId={} sessionId={} userId={} stage={} query=\"{}\" resultCount={} hitsJson={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                stage,
                preview(query, aiProperties.getAgentic().getLogPromptPreviewChars()),
                results.size(),
                preview(toJson(hits), aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private void auditGraphSearch(AgenticRagRequest request, AgentPlan plan, List<GraphSearchResult> results) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        List<Map<String, Object>> paths = results.stream()
                .limit(10)
                .map(result -> {
                    Map<String, Object> path = new LinkedHashMap<>();
                    path.put("path", result.getPathText());
                    path.put("fileMd5", result.getFileMd5());
                    path.put("chunkId", result.getChunkId());
                    path.put("score", result.getScore());
                    path.put("ingestionTraceId", result.getIngestionTraceId());
                    return path;
                })
                .toList();
        logger.info(
                "agentic_rag_graph_search traceId={} chatTraceId={} sessionId={} userId={} entities={} resultCount={} pathsJson={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                preview(String.valueOf(plan.getEntities()), aiProperties.getAgentic().getLogPromptPreviewChars()),
                results.size(),
                preview(toJson(paths), aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private void auditRewriteSkipped(AgenticRagRequest request, List<String> skippedQueries) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        logger.info(
                "agentic_rag_rewrite_skipped traceId={} chatTraceId={} sessionId={} userId={} reason=duplicate_rewrite_queries skippedQueries={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                preview(skippedQueries.toString(), aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private void auditRuleEntities(AgenticRagRequest request, AgentEntities ruleEntities, AgentEntities mergedEntities) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        logger.info(
                "agentic_rag_rule_entities traceId={} chatTraceId={} sessionId={} userId={} ruleEntities={} mergedEntities={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                preview(String.valueOf(ruleEntities), aiProperties.getAgentic().getLogOutputPreviewChars()),
                preview(String.valueOf(mergedEntities), aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private void auditFallback(AgenticRagRequest request, String stage, String reason, String action, int evidenceCount) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        logger.info(
                "agentic_rag_fallback traceId={} chatTraceId={} sessionId={} userId={} stage={} reason={} action={} evidenceCount={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                stage,
                preview(reason, aiProperties.getAgentic().getLogOutputPreviewChars()),
                action,
                evidenceCount
        );
    }

    private void auditEvaluatorPrompt(AgenticRagRequest request, int evaluatorEvidenceCount, int promptChars) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        logger.info(
                "agentic_rag_evaluator_audit traceId={} chatTraceId={} sessionId={} userId={} evaluatorEvidenceCount={} evaluatorPromptChars={}",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                evaluatorEvidenceCount,
                promptChars
        );
    }

    private void auditFinalContext(AgenticRagRequest request, ContextBuildResult context) {
        if (!aiProperties.getAgentic().isLogLlmDecisions()) {
            return;
        }
        Set<String> relatedIngestionTraceIds = context.includedEvidence.stream()
                .map(SearchResult::getIngestionTraceId)
                .filter(traceId -> traceId != null && !traceId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        logger.info(
                "agentic_rag_final_audit traceId={} chatTraceId={} sessionId={} userId={} contextChars={} evidenceCount={} rawEvidenceCount={} dedupedEvidenceCount={} finalEvidenceCount={} relatedIngestionTraceIds={} referenceMapping={} contextPreview=\"{}\"",
                traceId(request),
                traceId(request),
                request.getSessionId(),
                request.getUserId(),
                context.context.length(),
                context.includedEvidence.size(),
                context.rawEvidenceCount,
                context.dedupedEvidenceCount,
                context.includedEvidence.size(),
                relatedIngestionTraceIds,
                context.referenceMapping,
                preview(context.context, aiProperties.getAgentic().getLogOutputPreviewChars())
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize agent audit value", e);
            return "{}";
        }
    }

    private String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int safeMax = Math.max(0, maxChars);
        return normalized.length() <= safeMax ? normalized : normalized.substring(0, safeMax);
    }

    private String traceId(AgenticRagRequest request) {
        if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
            return request.getTraceId();
        }
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return request.getSessionId();
        }
        return "unknown";
    }

    private String summarizePlan(AgentPlan plan) {
        return "intent=" + plan.getIntent() + ", subQueries=" + plan.getSubQueries() + ", entities=" + plan.getEntities();
    }

    private String summarizeAssessment(EvidenceAssessment assessment) {
        return "sufficient=" + assessment.isSufficient()
                + ", selected=" + assessment.getSelectedChunkIds()
                + ", rewrites=" + assessment.getRewriteQueries();
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private int evidenceCount(List<SearchResult> evidence) {
        return evidence != null ? evidence.size() : 0;
    }

    private int safePositive(int configuredValue, int fallbackValue) {
        return configuredValue > 0 ? configuredValue : Math.max(1, fallbackValue);
    }

    private String safeFailureReason(Exception e) {
        if (e == null) {
            return "";
        }
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    @FunctionalInterface
    private interface JsonCall<T> {
        Optional<T> execute();
    }

    private record JsonStageResult<T>(Optional<T> value, String failureReason) {
    }

    private record SearchExecution(int queryCount, int failureCount, int resultCount) {
        private boolean allQueriesFailed() {
            return queryCount > 0 && failureCount == queryCount;
        }
    }

    private record ContextBuildResult(String context,
                                      List<SearchResult> includedEvidence,
                                      Map<Integer, String> referenceMapping,
                                      int rawEvidenceCount,
                                      int dedupedEvidenceCount) {
    }

    private enum EntityKind {
        COMPONENT,
        VERSION,
        CVE,
        PROJECT
    }
}
