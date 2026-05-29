package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchRequest;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchResult;
import com.yizhaoqi.smartpai.model.GraphEdge;
import com.yizhaoqi.smartpai.model.GraphNode;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.GraphEdgeRepository;
import com.yizhaoqi.smartpai.repository.GraphNodeRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class GraphSearchServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    private GraphSearchService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GraphSearchService(nodeRepository, edgeRepository, userRepository, orgTagCacheService);
    }

    @Test
    void searchesAccessibleGraphPathsFromCveEntity() {
        User user = new User();
        user.setId(42L);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("SEC"));

        GraphNode cve = node(1L, "CVE", "CVE-2021-44228", "cve-2021-44228");
        GraphNode component = node(2L, "COMPONENT", "log4j-core", "log4j-core");
        GraphEdge edge = edge(1L, 2L, "AFFECTED_BY");

        when(nodeRepository.findAccessibleSeeds(anyList(), anyList(), eq("42"), eq(List.of("SEC")))).thenReturn(List.of(cve));
        when(edgeRepository.findAccessibleBySourceNodeIdIn(eq(List.of(1L)), eq("42"), eq(List.of("SEC")))).thenReturn(List.of(edge));
        when(nodeRepository.findAllById(eq(List.of(2L)))).thenReturn(List.of(component));

        AgentEntities entities = new AgentEntities();
        entities.setCves(List.of("CVE-2021-44228"));

        List<GraphSearchResult> results = service.searchWithPermission(new GraphSearchRequest(entities, 2), "alice", 8);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getPathText().contains("CVE-2021-44228 --AFFECTED_BY--> log4j-core"));
        assertEquals("file-a", results.get(0).getFileMd5());
        assertEquals("trc-ingest-file-a", results.get(0).getIngestionTraceId());
    }

    @Test
    void returnsEmptyWhenNoScaEntitiesArePresent() {
        List<GraphSearchResult> results = service.searchWithPermission(new GraphSearchRequest(new AgentEntities(), 2), "alice", 8);

        assertTrue(results.isEmpty());
    }

    private GraphNode node(Long id, String type, String name, String normalizedName) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(type);
        node.setName(name);
        node.setNormalizedName(normalizedName);
        node.setFileMd5("file-a");
        node.setChunkId(1);
        node.setUserId("42");
        node.setOrgTag("SEC");
        node.setPublic(false);
        node.setIngestionTraceId("trc-ingest-file-a");
        return node;
    }

    private GraphEdge edge(Long sourceId, Long targetId, String relation) {
        GraphEdge edge = new GraphEdge();
        edge.setSourceNodeId(sourceId);
        edge.setTargetNodeId(targetId);
        edge.setRelationType(relation);
        edge.setConfidence(0.9d);
        edge.setFileMd5("file-a");
        edge.setChunkId(1);
        edge.setUserId("42");
        edge.setOrgTag("SEC");
        edge.setPublic(false);
        edge.setIngestionTraceId("trc-ingest-file-a");
        return edge;
    }
}
