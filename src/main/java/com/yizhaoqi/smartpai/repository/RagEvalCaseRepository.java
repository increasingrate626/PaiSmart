package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEvalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagEvalCaseRepository extends JpaRepository<RagEvalCase, Long> {
    List<RagEvalCase> findByEnabledTrue();

    Optional<RagEvalCase> findByQuestion(String question);
}
