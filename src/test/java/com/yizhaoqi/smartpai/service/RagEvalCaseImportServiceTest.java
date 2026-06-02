package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.eval.RagEvalCaseDefinition;
import com.yizhaoqi.smartpai.model.RagEvalCase;
import com.yizhaoqi.smartpai.repository.RagEvalCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalCaseImportServiceTest {

    @Mock
    private RagEvalCaseRepository caseRepository;

    private RagEvalCaseImportService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagEvalCaseImportService(caseRepository);
    }

    @Test
    void importsNewCaseDefinitionsAsEnabledDatabaseCases() {
        when(caseRepository.findByQuestion("Is log4j-core 2.14.1 affected by CVE-2021-44228?"))
                .thenReturn(Optional.empty());
        RagEvalCaseDefinition definition = new RagEvalCaseDefinition(
                "Is log4j-core 2.14.1 affected by CVE-2021-44228?",
                List.of("log4j-core"),
                List.of("2.14.1"),
                List.of("CVE-2021-44228"),
                List.of(),
                List.of("2.15.0"),
                List.of("file-a:7"),
                List.of("CVE-2021-44228", "2.15.0"),
                List.of("sca", "log4j"),
                true
        );

        RagEvalCaseImportResult result = service.importCases(List.of(definition));

        assertEquals(1, result.totalCases());
        assertEquals(1, result.insertedCases());
        assertEquals(0, result.updatedCases());
        assertEquals(0, result.skippedCases());

        ArgumentCaptor<RagEvalCase> captor = ArgumentCaptor.forClass(RagEvalCase.class);
        verify(caseRepository).save(captor.capture());
        RagEvalCase saved = captor.getValue();
        assertEquals(definition.question(), saved.getQuestion());
        assertTrue(saved.isEnabled());
        assertEquals("[\"log4j-core\"]", saved.getExpectedComponentsJson());
        assertEquals("[\"2.14.1\"]", saved.getExpectedVersionsJson());
        assertEquals("[\"CVE-2021-44228\"]", saved.getExpectedCvesJson());
        assertEquals("[\"2.15.0\"]", saved.getExpectedFixedVersionsJson());
        assertEquals("[\"file-a:7\"]", saved.getExpectedEvidenceJson());
        assertEquals("[\"CVE-2021-44228\",\"2.15.0\"]", saved.getExpectedKeyPointsJson());
        assertEquals("[\"sca\",\"log4j\"]", saved.getTagsJson());
    }

    @Test
    void skipsDefinitionsWhoseQuestionAlreadyExistsUnchanged() {
        RagEvalCase existing = new RagEvalCase();
        existing.setQuestion("duplicate question");
        existing.setExpectedComponentsJson("[]");
        existing.setExpectedVersionsJson("[]");
        existing.setExpectedCvesJson("[]");
        existing.setExpectedProjectsJson("[]");
        existing.setExpectedFixedVersionsJson("[]");
        existing.setExpectedEvidenceJson("[]");
        existing.setExpectedKeyPointsJson("[]");
        existing.setTagsJson("[\"sca\"]");
        existing.setEnabled(true);
        when(caseRepository.findByQuestion("duplicate question")).thenReturn(Optional.of(existing));

        RagEvalCaseImportResult result = service.importCases(List.of(new RagEvalCaseDefinition(
                "duplicate question",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("sca"),
                true
        )));

        assertEquals(1, result.totalCases());
        assertEquals(0, result.insertedCases());
        assertEquals(0, result.updatedCases());
        assertEquals(1, result.skippedCases());
        verify(caseRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updatesDefinitionsWhoseQuestionAlreadyExistsButExpectedEvidenceChanged() {
        RagEvalCase existing = new RagEvalCase();
        existing.setQuestion("log4j question");
        existing.setExpectedComponentsJson("[\"log4j-core\"]");
        existing.setExpectedVersionsJson("[\"2.14.1\"]");
        existing.setExpectedCvesJson("[\"CVE-2021-44228\"]");
        existing.setExpectedProjectsJson("[]");
        existing.setExpectedFixedVersionsJson("[\"2.15.0\"]");
        existing.setExpectedEvidenceJson("[]");
        existing.setExpectedKeyPointsJson("[\"CVE-2021-44228\"]");
        existing.setTagsJson("[\"sca\"]");
        existing.setEnabled(true);
        when(caseRepository.findByQuestion("log4j question")).thenReturn(Optional.of(existing));

        RagEvalCaseImportResult result = service.importCases(List.of(new RagEvalCaseDefinition(
                "log4j question",
                List.of("log4j-core"),
                List.of("2.14.1"),
                List.of("CVE-2021-44228"),
                List.of(),
                List.of("2.15.0"),
                List.of("11111111111111111111111111111111:1"),
                List.of("CVE-2021-44228"),
                List.of("sca"),
                true
        )));

        assertEquals(1, result.totalCases());
        assertEquals(0, result.insertedCases());
        assertEquals(1, result.updatedCases());
        assertEquals(0, result.skippedCases());
        verify(caseRepository).save(existing);
        assertEquals("[\"11111111111111111111111111111111:1\"]", existing.getExpectedEvidenceJson());
    }

    @Test
    void loadsDefaultScaEvalCasesFromClasspath() {
        when(caseRepository.findByQuestion(anyString())).thenReturn(Optional.empty());

        RagEvalCaseImportResult result = service.importDefaultCases();

        assertEquals(100, result.totalCases());
        assertEquals(result.totalCases(), result.insertedCases());
        assertEquals(0, result.updatedCases());
        assertEquals(0, result.skippedCases());
        assertFalse(result.source().isBlank());
    }
}
