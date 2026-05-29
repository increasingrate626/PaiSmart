package com.yizhaoqi.smartpai.entity.graph;

import lombok.Data;

@Data
public class GraphSearchResult {
    private String pathText;
    private String fileMd5;
    private Integer chunkId;
    private Double score;
    private String fileName;
    private String ingestionTraceId;
}
