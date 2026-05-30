package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEvalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagEvalCaseRepository extends JpaRepository<RagEvalCase, Long> {
    List<RagEvalCase> findByEnabledTrue();
}
