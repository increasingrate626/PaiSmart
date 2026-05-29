package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.entity.graph.GraphExtractionResult;
import com.yizhaoqi.smartpai.model.GraphEdge;
import com.yizhaoqi.smartpai.model.GraphNode;
import com.yizhaoqi.smartpai.repository.GraphEdgeRepository;
import com.yizhaoqi.smartpai.repository.GraphNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GraphExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(GraphExtractionService.class);
    private static final Pattern CVE_PATTERN = Pattern.compile("\\bCVE-\\d{4}-\\d{4,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][A-Za-z0-9_.-]+)?\\b");
    private static final Pattern COMPONENT_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_.-]*(?:-[A-Za-z0-9_.-]+)+\\b");

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final DeepSeekClient deepSeekClient;

    public GraphExtractionService(GraphNodeRepository nodeRepository,
                                  GraphEdgeRepository edgeRepository,
                                  DeepSeekClient deepSeekClient) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.deepSeekClient = deepSeekClient;
    }

    public void extract(String fileMd5,
                        List<TextChunk> chunks,
                        String userId,
                        String orgTag,
                        boolean isPublic,
                        String ingestionTraceId) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        try {
            edgeRepository.deleteByFileMd5(fileMd5);
            nodeRepository.deleteByFileMd5(fileMd5);

            Map<String, GraphNode> nodesByName = new LinkedHashMap<>();
            persistRuleCandidates(fileMd5, chunks, userId, orgTag, isPublic, ingestionTraceId, nodesByName);

            Optional<GraphExtractionResult> llmResult = deepSeekClient.completeJson(
                    buildSystemPrompt(),
                    buildUserPrompt(chunks),
                    GraphExtractionResult.class
            );
            if (llmResult.isPresent()) {
                persistLlmResult(fileMd5, llmResult.get(), userId, orgTag, isPublic, ingestionTraceId, nodesByName);
            }

            logger.info("graph_extraction_audit ingestionTraceId={} fileMd5={} nodeCount={} edgeCount={}",
                    ingestionTraceId, fileMd5, nodesByName.size(), llmResult.map(result -> result.getEdges().size()).orElse(0));
        } catch (Exception e) {
            logger.warn("graph_extraction_failed ingestionTraceId={} fileMd5={} reason={}",
                    ingestionTraceId, fileMd5, e.getMessage(), e);
        }
    }

    private void persistRuleCandidates(String fileMd5,
                                       List<TextChunk> chunks,
                                       String userId,
                                       String orgTag,
                                       boolean isPublic,
                                       String ingestionTraceId,
                                       Map<String, GraphNode> nodesByName) {
        for (TextChunk chunk : chunks) {
            extractPattern(CVE_PATTERN, chunk.getContent(), "CVE", fileMd5, chunk.getChunkId(), userId, orgTag, isPublic, ingestionTraceId, nodesByName);
            extractPattern(VERSION_PATTERN, chunk.getContent(), "VERSION", fileMd5, chunk.getChunkId(), userId, orgTag, isPublic, ingestionTraceId, nodesByName);
            extractPattern(COMPONENT_PATTERN, chunk.getContent(), "COMPONENT", fileMd5, chunk.getChunkId(), userId, orgTag, isPublic, ingestionTraceId, nodesByName);
        }
    }

    private void extractPattern(Pattern pattern,
                                String text,
                                String type,
                                String fileMd5,
                                int chunkId,
                                String userId,
                                String orgTag,
                                boolean isPublic,
                                String ingestionTraceId,
                                Map<String, GraphNode> nodesByName) {
        if (text == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            persistNode(type, matcher.group(), fileMd5, chunkId, userId, orgTag, isPublic, ingestionTraceId, null, nodesByName);
        }
    }

    private void persistLlmResult(String fileMd5,
                                  GraphExtractionResult result,
                                  String userId,
                                  String orgTag,
                                  boolean isPublic,
                                  String ingestionTraceId,
                                  Map<String, GraphNode> nodesByName) {
        List<GraphExtractionResult.ExtractedNode> nodes = result.getNodes() != null ? result.getNodes() : List.of();
        for (GraphExtractionResult.ExtractedNode node : nodes) {
            persistNode(node.getType(), node.getName(), fileMd5, safeChunkId(node.getChunkId()), userId, orgTag, isPublic, ingestionTraceId, node.getPropertiesJson(), nodesByName);
        }

        List<GraphExtractionResult.ExtractedEdge> edges = result.getEdges() != null ? result.getEdges() : List.of();
        for (GraphExtractionResult.ExtractedEdge extracted : edges) {
            GraphNode source = nodesByName.get(normalize(extracted.getSourceName()));
            GraphNode target = nodesByName.get(normalize(extracted.getTargetName()));
            if (source == null || target == null || extracted.getRelationType() == null || extracted.getRelationType().isBlank()) {
                continue;
            }

            GraphEdge edge = new GraphEdge();
            edge.setSourceNodeId(source.getId());
            edge.setTargetNodeId(target.getId());
            edge.setRelationType(extracted.getRelationType().trim().toUpperCase(Locale.ROOT));
            edge.setPropertiesJson(extracted.getPropertiesJson());
            edge.setConfidence(extracted.getConfidence());
            edge.setFileMd5(fileMd5);
            edge.setChunkId(safeChunkId(extracted.getChunkId()));
            edge.setUserId(userId);
            edge.setOrgTag(orgTag);
            edge.setPublic(isPublic);
            edge.setIngestionTraceId(ingestionTraceId);
            edgeRepository.save(edge);
        }
    }

    private GraphNode persistNode(String type,
                                  String name,
                                  String fileMd5,
                                  int chunkId,
                                  String userId,
                                  String orgTag,
                                  boolean isPublic,
                                  String ingestionTraceId,
                                  String propertiesJson,
                                  Map<String, GraphNode> nodesByName) {
        String normalized = normalize(name);
        if (type == null || type.isBlank() || normalized.isBlank()) {
            return null;
        }
        GraphNode cached = nodesByName.get(normalized);
        if (cached != null) {
            return cached;
        }

        String safeType = type.trim().toUpperCase(Locale.ROOT);
        GraphNode node = nodeRepository.findFirstByTypeAndNormalizedNameAndFileMd5AndChunkIdAndUserIdAndOrgTagAndIsPublic(
                safeType, normalized, fileMd5, chunkId, userId, orgTag, isPublic
        ).orElseGet(GraphNode::new);
        node.setType(safeType);
        node.setName(name.trim());
        node.setNormalizedName(normalized);
        node.setPropertiesJson(propertiesJson);
        node.setFileMd5(fileMd5);
        node.setChunkId(chunkId);
        node.setUserId(userId);
        node.setOrgTag(orgTag);
        node.setPublic(isPublic);
        node.setIngestionTraceId(ingestionTraceId);
        GraphNode saved = nodeRepository.save(node);
        nodesByName.put(normalized, saved);
        return saved;
    }

    private String buildSystemPrompt() {
        return """
                You extract SCA graph facts for PaiSmart.
                Return only JSON: {"nodes":[{"type":"COMPONENT|VERSION|CVE|PROJECT|DEPENDENCY_PATH|ADVISORY|FIX_VERSION|SCAN_FINDING","name":"string","chunkId":1,"propertiesJson":"{}"}],"edges":[{"sourceName":"string","targetName":"string","relationType":"HAS_VERSION|AFFECTED_BY|FIXED_IN|DEPENDS_ON|INTRODUCES|DESCRIBES|REPORTS|MITIGATES","chunkId":1,"confidence":0.0,"propertiesJson":"{}"}]}.
                Do not include hidden chain-of-thought.
                """;
    }

    private String buildUserPrompt(List<TextChunk> chunks) {
        List<String> previews = new ArrayList<>();
        for (TextChunk chunk : chunks.stream().limit(8).toList()) {
            String text = chunk.getContent() != null ? chunk.getContent().replaceAll("\\s+", " ").trim() : "";
            if (text.length() > 500) {
                text = text.substring(0, 500);
            }
            previews.add("chunkId=" + chunk.getChunkId() + " text=" + text);
        }
        return String.join("\n", previews);
    }

    private int safeChunkId(Integer chunkId) {
        return chunkId != null ? chunkId : 1;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
