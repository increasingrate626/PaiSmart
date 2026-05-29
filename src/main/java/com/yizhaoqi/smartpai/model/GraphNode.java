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
@Table(name = "graph_node", indexes = {
        @Index(name = "idx_graph_node_type_name", columnList = "type, normalized_name"),
        @Index(name = "idx_graph_node_file_chunk", columnList = "file_md5, chunk_id"),
        @Index(name = "idx_graph_node_permission", columnList = "user_id, org_tag, is_public")
})
public class GraphNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Lob
    @Column(name = "properties_json")
    private String propertiesJson;

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
