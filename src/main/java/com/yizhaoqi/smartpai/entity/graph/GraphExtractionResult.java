package com.yizhaoqi.smartpai.entity.graph;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GraphExtractionResult {
    private List<ExtractedNode> nodes = new ArrayList<>();
    private List<ExtractedEdge> edges = new ArrayList<>();

    @Data
    public static class ExtractedNode {
        private String type;
        private String name;
        private Integer chunkId;
        private String propertiesJson;
    }

    @Data
    public static class ExtractedEdge {
        private String sourceName;
        private String targetName;
        private String relationType;
        private Integer chunkId;
        private double confidence = 0.5d;
        private String propertiesJson;
    }
}
