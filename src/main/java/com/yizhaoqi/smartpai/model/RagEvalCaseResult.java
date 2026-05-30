package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "rag_eval_case_result", indexes = {
        @Index(name = "idx_rag_eval_case_result_run", columnList = "run_id"),
        @Index(name = "idx_rag_eval_case_result_case", columnList = "case_id")
})
public class RagEvalCaseResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private boolean passed;

    @Column(name = "evidence_matched", nullable = false)
    private boolean evidenceMatched;

    @Column(name = "key_points_matched", nullable = false)
    private boolean keyPointsMatched;

    @Column(name = "entity_coverage_matched", nullable = false)
    private boolean entityCoverageMatched;

    @Column(name = "evidence_hit_rate", nullable = false)
    private double evidenceHitRate;

    @Column(name = "failure_reason", length = 100)
    private String failureReason = "";

    @Column(name = "final_context", columnDefinition = "TEXT")
    private String finalContext;

    @Column(name = "selected_evidence_json", columnDefinition = "TEXT")
    private String selectedEvidenceJson;

    @Column(name = "trace_json", columnDefinition = "TEXT")
    private String traceJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
