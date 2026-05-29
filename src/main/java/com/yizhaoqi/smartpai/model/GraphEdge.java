package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "graph_edge", indexes = {
        @Index(name = "idx_graph_edge_source", columnList = "source_node_id"),
        @Index(name = "idx_graph_edge_target", columnList = "target_node_id"),
        @Index(name = "idx_graph_edge_relation", columnList = "relation_type"),
        @Index(name = "idx_graph_edge_permission", columnList = "user_id, org_tag, is_public")
})
public class GraphEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_node_id")
    private Long sourceNodeId;

    @Column(name = "target_node_id")
    private Long targetNodeId;

    @Column(name = "relation_type", nullable = false, length = 50)
    private String relationType;

    @Lob
    @Column(name = "properties_json")
    private String propertiesJson;

    @Column(nullable = false)
    private double confidence = 0.5d;

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "ingestion_trace_id", length = 64)
    private String ingestionTraceId;
}
