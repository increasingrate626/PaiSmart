package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchRequest;
import com.yizhaoqi.smartpai.entity.graph.GraphSearchResult;
import com.yizhaoqi.smartpai.model.GraphEdge;
import com.yizhaoqi.smartpai.model.GraphNode;
import com.yizhaoqi.smartpai.repository.GraphEdgeRepository;
import com.yizhaoqi.smartpai.repository.GraphNodeRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphSearchService {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;

    public GraphSearchService(GraphNodeRepository nodeRepository,
                              GraphEdgeRepository edgeRepository,
                              UserRepository userRepository,
                              OrgTagCacheService orgTagCacheService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
    }

    public List<GraphSearchResult> searchWithPermission(GraphSearchRequest request, String userId, int topK) {
        AgentEntities entities = request != null ? request.getEntities() : null;
        if (entities == null || !entities.hasAny()) {
            return List.of();
        }

        String userDbId = userRepository.findByUsername(userId)
                .map(user -> String.valueOf(user.getId()))
                .orElse(userId);
        List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(userId);
        if (effectiveTags == null || effectiveTags.isEmpty()) {
            effectiveTags = List.of("DEFAULT");
        }

        List<String> types = entityTypes(entities);
        List<String> names = entityNames(entities);
        if (types.isEmpty() || names.isEmpty()) {
            return List.of();
        }

        List<GraphNode> seeds = nodeRepository.findAccessibleSeeds(types, names, userDbId, effectiveTags);
        if (seeds.isEmpty()) {
            return List.of();
        }

        int maxDepth = request.getMaxDepth() > 0 ? request.getMaxDepth() : 2;
        List<GraphSearchResult> results = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        List<GraphPath> frontier = seeds.stream()
                .filter(node -> node.getId() != null)
                .map(node -> new GraphPath(node, List.of(), node))
                .toList();

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty() && results.size() < topK; depth++) {
            List<Long> sourceIds = frontier.stream()
                    .map(path -> path.tail().getId())
                    .filter(id -> id != null && visited.add(id))
                    .toList();
            if (sourceIds.isEmpty()) {
                break;
            }

            List<GraphEdge> edges = edgeRepository.findAccessibleBySourceNodeIdIn(sourceIds, userDbId, effectiveTags);
            if (edges.isEmpty()) {
                break;
            }

            List<Long> targetIds = edges.stream()
                    .map(GraphEdge::getTargetNodeId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            Map<Long, GraphNode> targetById = nodeRepository.findAllById(targetIds).stream()
                    .collect(Collectors.toMap(GraphNode::getId, node -> node, (left, right) -> left, HashMap::new));

            Map<Long, List<GraphPath>> pathsByTail = frontier.stream()
                    .collect(Collectors.groupingBy(path -> path.tail().getId()));
            List<GraphPath> next = new ArrayList<>();
            for (GraphEdge edge : edges) {
                GraphNode target = targetById.get(edge.getTargetNodeId());
                if (target == null) {
                    continue;
                }
                for (GraphPath path : pathsByTail.getOrDefault(edge.getSourceNodeId(), List.of())) {
                    GraphPath expanded = path.expand(edge, target);
                    results.add(toResult(expanded));
                    next.add(expanded);
                    if (results.size() >= topK) {
                        break;
                    }
                }
                if (results.size() >= topK) {
                    break;
                }
            }
            frontier = next;
        }

        return results.stream().limit(topK).toList();
    }

    private GraphSearchResult toResult(GraphPath path) {
        GraphEdge lastEdge = path.edges().get(path.edges().size() - 1);
        GraphSearchResult result = new GraphSearchResult();
        result.setPathText(pathText(path));
        result.setFileMd5(lastEdge.getFileMd5());
        result.setChunkId(lastEdge.getChunkId());
        result.setScore(lastEdge.getConfidence());
        result.setIngestionTraceId(lastEdge.getIngestionTraceId());
        return result;
    }

    private String pathText(GraphPath path) {
        StringBuilder builder = new StringBuilder(path.start().getName());
        GraphNode current = path.start();
        for (int i = 0; i < path.edges().size(); i++) {
            GraphEdge edge = path.edges().get(i);
            GraphNode target = path.nodes().get(i);
            builder.append(" --").append(edge.getRelationType()).append("--> ").append(target.getName());
            current = target;
        }
        return builder.toString();
    }

    private List<String> entityTypes(AgentEntities entities) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (hasValues(entities.getComponents())) {
            types.add("COMPONENT");
        }
        if (hasValues(entities.getVersions())) {
            types.add("VERSION");
        }
        if (hasValues(entities.getCves())) {
            types.add("CVE");
        }
        if (hasValues(entities.getProjects())) {
            types.add("PROJECT");
        }
        return new ArrayList<>(types);
    }

    private List<String> entityNames(AgentEntities entities) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addNames(names, entities.getComponents());
        addNames(names, entities.getVersions());
        addNames(names, entities.getCves());
        addNames(names, entities.getProjects());
        return new ArrayList<>(names);
    }

    private void addNames(Set<String> names, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .forEach(names::add);
    }

    private boolean hasValues(List<String> values) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record GraphPath(GraphNode start, List<GraphEdge> edges, List<GraphNode> nodes, GraphNode tail) {
        private GraphPath(GraphNode start, List<GraphEdge> edges, GraphNode tail) {
            this(start, edges, List.of(), tail);
        }

        private GraphPath expand(GraphEdge edge, GraphNode target) {
            List<GraphEdge> expandedEdges = new ArrayList<>(edges);
            expandedEdges.add(edge);
            List<GraphNode> expandedNodes = new ArrayList<>(nodes);
            expandedNodes.add(target);
            return new GraphPath(start, expandedEdges, expandedNodes, target);
        }
    }
}
