package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgentPlan;
import com.yizhaoqi.smartpai.entity.agent.AgentTraceStep;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import com.yizhaoqi.smartpai.entity.agent.EvidenceAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AgenticRagService {

    private static final Logger logger = LoggerFactory.getLogger(AgenticRagService.class);
    private static final int MAX_SNIPPET_LEN = 300;

    private final HybridSearchService searchService;
    private final DeepSeekClient deepSeekClient;
    private final AiProperties aiProperties;

    public AgenticRagService(HybridSearchService searchService,
                             DeepSeekClient deepSeekClient,
                             AiProperties aiProperties) {
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
        this.aiProperties = aiProperties;
    }

    public AgenticRagResult run(AgenticRagRequest request) {
        List<AgentTraceStep> trace = new ArrayList<>();
        AiProperties.Agentic cfg = aiProperties.getAgentic();

        if (!cfg.isEnabled()) {
            return fallbackSearch(request, trace, "agentic disabled");
        }

        try {
            Optional<AgentPlan> planOptional = callWithTimeout(
                    () -> deepSeekClient.completeJson(buildPlannerSystemPrompt(), buildPlannerUserPrompt(request), AgentPlan.class),
                    cfg.getPlannerTimeoutMs(),
                    "PLAN_QUERY",
                    request,
                    trace,
                    request.getMessage()
            );

            if (planOptional.isEmpty()) {
                return fallbackSearch(request, trace, "planner failed");
            }

            AgentPlan plan = planOptional.get();
            addTrace(trace, request, "PLAN_QUERY", 0, summarize(request.getMessage()), summarizePlan(plan), null);

            List<SearchResult> evidence = new ArrayList<>();
            List<String> firstRoundQueries = normalizeQueries(plan.getSubQueries(), request.getMessage(), cfg.getMaxSubQueries());
            searchQueries(request, firstRoundQueries, cfg.getFirstRoundTopK(), evidence, trace, "SEARCH");

            Optional<EvidenceAssessment> assessmentOptional = assessEvidence(request, plan, evidence, trace, cfg);
            EvidenceAssessment assessment = assessmentOptional.orElse(null);

            boolean canRewrite = assessment != null
                    && !assessment.isSufficient()
                    && cfg.getMaxSearchRounds() > 1
                    && assessment.getRewriteQueries() != null
                    && !assessment.getRewriteQueries().isEmpty();

            if (canRewrite) {
                List<String> rewriteQueries = normalizeQueries(assessment.getRewriteQueries(), request.getMessage(), cfg.getMaxSubQueries());
                searchQueries(request, rewriteQueries, cfg.getRewriteRoundTopK(), evidence, trace, "REWRITE_SEARCH");
                assessment = assessEvidence(request, plan, evidence, trace, cfg).orElse(assessment);
            }

            return buildResult(evidence, assessment, trace, cfg.getMaxContextChars());
        } catch (Exception e) {
            logger.error("Agentic RAG failed, falling back to normal RAG", e);
            return fallbackSearch(request, trace, e.getMessage());
        }
    }

    private Optional<EvidenceAssessment> assessEvidence(AgenticRagRequest request,
                                                        AgentPlan plan,
                                                        List<SearchResult> evidence,
                                                        List<AgentTraceStep> trace,
                                                        AiProperties.Agentic cfg) {
        String userPrompt = buildEvaluatorUserPrompt(request, plan, deduplicateAndSort(evidence));
        Optional<EvidenceAssessment> result = callWithTimeout(
                () -> deepSeekClient.completeJson(buildEvaluatorSystemPrompt(), userPrompt, EvidenceAssessment.class),
                cfg.getEvaluatorTimeoutMs(),
                "EVALUATE_EVIDENCE",
                request,
                trace,
                summarize(request.getMessage())
        );
        result.ifPresent(assessment ->
                addTrace(trace, request, "EVALUATE_EVIDENCE", 0, summarize(request.getMessage()), summarizeAssessment(assessment), null));
        return result;
    }

    private void searchQueries(AgenticRagRequest request,
                               List<String> queries,
                               int topK,
                               List<SearchResult> evidence,
                               List<AgentTraceStep> trace,
                               String stage) {
        for (String query : queries) {
            long start = System.nanoTime();
            try {
                List<SearchResult> results = searchService.searchWithPermission(query, request.getUserId(), topK);
                evidence.addAll(results);
                addTrace(trace, request, stage, elapsedMs(start), summarize(query), "results=" + results.size(), null);
            } catch (Exception e) {
                addTrace(trace, request, stage, elapsedMs(start), summarize(query), "results=0", e.getMessage());
                logger.warn("Agentic RAG search failed for query: {}", query, e);
            }
        }
    }

    private AgenticRagResult fallbackSearch(AgenticRagRequest request, List<AgentTraceStep> trace, String reason) {
        long start = System.nanoTime();
        try {
            List<SearchResult> results = searchService.searchWithPermission(
                    request.getMessage(),
                    request.getUserId(),
                    aiProperties.getAgentic().getFirstRoundTopK()
            );
            addTrace(trace, request, "FALLBACK_SEARCH", elapsedMs(start), summarize(request.getMessage()), "results=" + results.size(), reason);
            return buildResult(results, null, trace, aiProperties.getAgentic().getMaxContextChars());
        } catch (Exception e) {
            addTrace(trace, request, "FALLBACK_SEARCH", elapsedMs(start), summarize(request.getMessage()), "results=0", e.getMessage());
            logger.error("Agentic RAG fallback search failed", e);
            return buildResult(List.of(), null, trace, aiProperties.getAgentic().getMaxContextChars());
        }
    }

    private AgenticRagResult buildResult(List<SearchResult> rawEvidence,
                                         EvidenceAssessment assessment,
                                         List<AgentTraceStep> trace,
                                         int budget) {
        List<SearchResult> selected = selectEvidence(rawEvidence, assessment);
        ContextBuildResult context = buildContext(selected, budget);
        return new AgenticRagResult(context.context, context.includedEvidence, trace, context.referenceMapping);
    }

    private List<SearchResult> selectEvidence(List<SearchResult> rawEvidence, EvidenceAssessment assessment) {
        List<SearchResult> deduped = deduplicateAndSort(rawEvidence);
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

    private ContextBuildResult buildContext(List<SearchResult> selected, int budget) {
        StringBuilder context = new StringBuilder();
        List<SearchResult> includedEvidence = new ArrayList<>();
        Map<Integer, String> referenceMapping = new LinkedHashMap<>();

        int referenceNumber = 1;
        for (SearchResult result : selected) {
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

        return new ContextBuildResult(context.toString(), includedEvidence, referenceMapping);
    }

    private <T> Optional<T> callWithTimeout(JsonCall<T> call,
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
            }
            return result;
        } catch (Exception e) {
            addTrace(trace, request, stage, elapsedMs(start), summarize(inputSummary), "empty", e.getMessage());
            logger.warn("Agentic RAG stage {} failed", stage, e);
            return Optional.empty();
        }
    }

    private String buildPlannerSystemPrompt() {
        return """
                You are the query planner for PaiSmart Agentic RAG.
                Return only a JSON object matching this schema:
                {"intent":"string","subQueries":["string"],"answerStrategy":"string","needsClarification":false}
                Split complex questions into at most four focused retrieval queries.
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
                {"sufficient":true,"missingAspects":[],"selectedChunkIds":["fileMd5:chunkId"],"rewriteQueries":[]}
                Mark sufficient=false when the evidence cannot answer the question.
                Rewrite queries must be retrieval queries only.
                """;
    }

    private String buildEvaluatorUserPrompt(AgenticRagRequest request, AgentPlan plan, List<SearchResult> evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(request.getMessage()).append("\n\n");
        builder.append("Intent:\n").append(plan.getIntent()).append("\n\n");
        builder.append("Evidence:\n");
        for (SearchResult result : evidence) {
            builder.append("- id=").append(chunkKey(result))
                    .append(", score=").append(result.getScore())
                    .append(", file=").append(result.getFileName())
                    .append(", text=").append(summarize(result.getTextContent()))
                    .append("\n");
        }
        return builder.toString();
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
                "agentic_rag_trace traceId={} sessionId={} userId={} stage={} durationMs={} inputSummary=\"{}\" outputSummary=\"{}\" failureReason=\"{}\"",
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
        return "intent=" + plan.getIntent() + ", subQueries=" + plan.getSubQueries();
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

    @FunctionalInterface
    private interface JsonCall<T> {
        Optional<T> execute();
    }

    private record ContextBuildResult(String context,
                                      List<SearchResult> includedEvidence,
                                      Map<Integer, String> referenceMapping) {
    }
}
