package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.eval.RagEvalCaseDefinition;
import com.yizhaoqi.smartpai.model.RagEvalCase;
import com.yizhaoqi.smartpai.repository.RagEvalCaseRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Service
public class RagEvalCaseImportService {

    private static final String DEFAULT_CASE_RESOURCE = "eval/sca_eval_cases.json";
    private static final TypeReference<List<RagEvalCaseDefinition>> CASE_LIST_TYPE = new TypeReference<>() {
    };

    private final RagEvalCaseRepository caseRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagEvalCaseImportService(RagEvalCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Transactional
    public RagEvalCaseImportResult importDefaultCases() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_CASE_RESOURCE);
        try {
            List<RagEvalCaseDefinition> definitions = objectMapper.readValue(resource.getInputStream(), CASE_LIST_TYPE);
            RagEvalCaseImportResult result = importCases(definitions);
            return new RagEvalCaseImportResult(
                    "classpath:" + DEFAULT_CASE_RESOURCE,
                    result.totalCases(),
                    result.insertedCases(),
                    result.updatedCases(),
                    result.skippedCases()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default SCA eval cases from " + DEFAULT_CASE_RESOURCE, e);
        }
    }

    @Transactional
    public RagEvalCaseImportResult importCases(List<RagEvalCaseDefinition> definitions) {
        List<RagEvalCaseDefinition> safeDefinitions = definitions != null ? definitions : List.of();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (RagEvalCaseDefinition definition : safeDefinitions) {
            if (definition == null || definition.question() == null || definition.question().isBlank()) {
                skipped++;
                continue;
            }
            String question = definition.question().trim();
            RagEvalCase existing = caseRepository.findByQuestion(question).orElse(null);
            if (existing != null) {
                boolean changed = applyDefinition(existing, definition, question);
                if (changed) {
                    caseRepository.save(existing);
                    updated++;
                } else {
                    skipped++;
                }
                continue;
            }

            RagEvalCase evalCase = new RagEvalCase();
            applyDefinition(evalCase, definition, question);
            caseRepository.save(evalCase);
            inserted++;
        }
        return new RagEvalCaseImportResult("", safeDefinitions.size(), inserted, updated, skipped);
    }

    private boolean applyDefinition(RagEvalCase evalCase, RagEvalCaseDefinition definition, String question) {
        boolean changed = false;
        changed |= setIfDifferent(evalCase::getQuestion, evalCase::setQuestion, question);
        changed |= setIfDifferent(evalCase::getExpectedComponentsJson, evalCase::setExpectedComponentsJson, toJson(definition.expectedComponents()));
        changed |= setIfDifferent(evalCase::getExpectedVersionsJson, evalCase::setExpectedVersionsJson, toJson(definition.expectedVersions()));
        changed |= setIfDifferent(evalCase::getExpectedCvesJson, evalCase::setExpectedCvesJson, toJson(definition.expectedCves()));
        changed |= setIfDifferent(evalCase::getExpectedProjectsJson, evalCase::setExpectedProjectsJson, toJson(definition.expectedProjects()));
        changed |= setIfDifferent(evalCase::getExpectedFixedVersionsJson, evalCase::setExpectedFixedVersionsJson, toJson(definition.expectedFixedVersions()));
        changed |= setIfDifferent(evalCase::getExpectedEvidenceJson, evalCase::setExpectedEvidenceJson, toJson(definition.expectedEvidence()));
        changed |= setIfDifferent(evalCase::getExpectedKeyPointsJson, evalCase::setExpectedKeyPointsJson, toJson(definition.expectedKeyPoints()));
        changed |= setIfDifferent(evalCase::getTagsJson, evalCase::setTagsJson, toJson(definition.tags()));
        if (evalCase.isEnabled() != definition.enabled()) {
            evalCase.setEnabled(definition.enabled());
            changed = true;
        }
        return changed;
    }

    private boolean setIfDifferent(java.util.function.Supplier<String> getter,
                                   java.util.function.Consumer<String> setter,
                                   String value) {
        if (!java.util.Objects.equals(getter.get(), value)) {
            setter.accept(value);
            return true;
        }
        return false;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values != null ? values : List.of());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize eval case list", e);
        }
    }
}
