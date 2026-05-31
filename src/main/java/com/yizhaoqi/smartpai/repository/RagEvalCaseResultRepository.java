package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEvalCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagEvalCaseResultRepository extends JpaRepository<RagEvalCaseResult, Long> {
    List<RagEvalCaseResult> findByRunIdOrderByIdAsc(Long runId);
}
