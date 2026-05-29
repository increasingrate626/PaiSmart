package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.entity.graph.GraphExtractionResult;
import com.yizhaoqi.smartpai.repository.GraphEdgeRepository;
import com.yizhaoqi.smartpai.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExtractionServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private DeepSeekClient deepSeekClient;

    private GraphExtractionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GraphExtractionService(nodeRepository, edgeRepository, deepSeekClient);
    }

    @Test
    void extractsScaCandidatesWithRulesAndPersistsNodesWhenLlmIsInvalid() {
        when(deepSeekClient.completeJson(anyString(), anyString(), eq(GraphExtractionResult.class))).thenReturn(Optional.empty());
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.extract(
                "file-a",
                List.of(new TextChunk(1, "log4j-core 2.14.1 is affected by CVE-2021-44228 and fixed in 2.15.0")),
                "alice",
                "SEC",
                false,
                "trc-ingest-file-a"
        );

        verify(nodeRepository).deleteByFileMd5("file-a");
        verify(edgeRepository).deleteByFileMd5("file-a");
        verify(edgeRepository, never()).save(any());
        verify(nodeRepository).save(org.mockito.ArgumentMatchers.argThat(node ->
                "CVE".equals(node.getType()) && "cve-2021-44228".equals(node.getNormalizedName())));
        verify(nodeRepository).save(org.mockito.ArgumentMatchers.argThat(node ->
                "COMPONENT".equals(node.getType()) && "log4j-core".equals(node.getNormalizedName())));
        verify(nodeRepository).save(org.mockito.ArgumentMatchers.argThat(node ->
                "VERSION".equals(node.getType()) && "2.14.1".equals(node.getNormalizedName())));
        verify(nodeRepository).save(org.mockito.ArgumentMatchers.argThat(node ->
                "VERSION".equals(node.getType()) && "2.15.0".equals(node.getNormalizedName())));
    }

    @Test
    void persistsLlmStructuredEdgesAndNodes() {
        GraphExtractionResult result = new GraphExtractionResult();
        result.setNodes(List.of(
                node("COMPONENT", "log4j-core"),
                node("VERSION", "2.14.1"),
                node("CVE", "CVE-2021-44228")
        ));
        result.setEdges(List.of(edge("log4j-core", "CVE-2021-44228", "AFFECTED_BY", 0.94)));

        when(deepSeekClient.completeJson(anyString(), anyString(), eq(GraphExtractionResult.class))).thenReturn(Optional.of(result));
        when(nodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findFirstByTypeAndNormalizedNameAndFileMd5AndChunkIdAndUserIdAndOrgTagAndIsPublic(
                anyString(), anyString(), anyString(), any(), anyString(), anyString(), eq(true)))
                .thenReturn(Optional.empty());

        service.extract(
                "file-a",
                List.of(new TextChunk(1, "log4j-core 2.14.1 affected by CVE-2021-44228")),
                "alice",
                "SEC",
                true,
                "trc-ingest-file-a"
        );

        verify(edgeRepository).save(org.mockito.ArgumentMatchers.argThat(edge ->
                "AFFECTED_BY".equals(edge.getRelationType())
                        && Double.compare(edge.getConfidence(), 0.94d) == 0
                        && "file-a".equals(edge.getFileMd5())
                        && "trc-ingest-file-a".equals(edge.getIngestionTraceId())));
    }

    private GraphExtractionResult.ExtractedNode node(String type, String name) {
        GraphExtractionResult.ExtractedNode node = new GraphExtractionResult.ExtractedNode();
        node.setType(type);
        node.setName(name);
        node.setChunkId(1);
        return node;
    }

    private GraphExtractionResult.ExtractedEdge edge(String source, String target, String relation, double confidence) {
        GraphExtractionResult.ExtractedEdge edge = new GraphExtractionResult.ExtractedEdge();
        edge.setSourceName(source);
        edge.setTargetName(target);
        edge.setRelationType(relation);
        edge.setChunkId(1);
        edge.setConfidence(confidence);
        return edge;
    }
}
