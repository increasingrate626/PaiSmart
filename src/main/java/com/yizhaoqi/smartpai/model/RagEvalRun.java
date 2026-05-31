package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "rag_eval_run", indexes = {
        @Index(name = "idx_rag_eval_run_started_at", columnList = "started_at")
})
public class RagEvalRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "total_cases", nullable = false)
    private int totalCases;

    @Column(name = "passed_cases", nullable = false)
    private int passedCases;

    @Column(name = "failed_cases", nullable = false)
    private int failedCases;

    @Column(name = "average_evidence_hit_rate", nullable = false)
    private double averageEvidenceHitRate;

    @Column(name = "average_faithfulness")
    private Double averageFaithfulness;

    @Column(name = "average_citation_accuracy")
    private Double averageCitationAccuracy;

    @Column(name = "average_fixed_version_accuracy")
    private Double averageFixedVersionAccuracy;

    @Column(name = "quality_gate_passed", nullable = false)
    private boolean qualityGatePassed;

    @Column(columnDefinition = "TEXT")
    private String note;
}
