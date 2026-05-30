package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "rag_eval_case", indexes = {
        @Index(name = "idx_rag_eval_case_enabled", columnList = "enabled")
})
public class RagEvalCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "expected_components_json", columnDefinition = "TEXT")
    private String expectedComponentsJson;

    @Column(name = "expected_versions_json", columnDefinition = "TEXT")
    private String expectedVersionsJson;

    @Column(name = "expected_cves_json", columnDefinition = "TEXT")
    private String expectedCvesJson;

    @Column(name = "expected_projects_json", columnDefinition = "TEXT")
    private String expectedProjectsJson;

    @Column(name = "expected_fixed_versions_json", columnDefinition = "TEXT")
    private String expectedFixedVersionsJson;

    @Column(name = "expected_evidence_json", columnDefinition = "TEXT")
    private String expectedEvidenceJson;

    @Column(name = "expected_key_points_json", columnDefinition = "TEXT")
    private String expectedKeyPointsJson;

    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
