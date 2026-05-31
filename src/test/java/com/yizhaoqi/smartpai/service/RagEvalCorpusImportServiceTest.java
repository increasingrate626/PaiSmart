package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.eval.RagEvalCorpusDefinition;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalCorpusImportServiceTest {

    @Mock
    private ElasticsearchService elasticsearchService;

    @Mock
    private FileUploadRepository fileUploadRepository;

    private RagEvalCorpusImportService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagEvalCorpusImportService(elasticsearchService, fileUploadRepository);
    }

    @Test
    void importsCorpusDefinitionsIntoSearchIndexAndFileMetadata() {
        when(fileUploadRepository.findByFileMd5("11111111111111111111111111111111"))
                .thenReturn(Optional.empty());
        RagEvalCorpusDefinition definition = new RagEvalCorpusDefinition(
                "11111111111111111111111111111111",
                "eval-log4j-remediation.md",
                "1",
                "eval",
                true,
                "eval-sca-log4j",
                List.of(new RagEvalCorpusDefinition.ChunkDefinition(
                        1,
                        "log4j-core 2.14.1 is affected by CVE-2021-44228. Fixed version: 2.15.0."
                ))
        );

        RagEvalCorpusImportResult result = service.importCorpus(List.of(definition));

        assertEquals(1, result.totalDocuments());
        assertEquals(1, result.upsertedFiles());
        assertEquals(1, result.indexedChunks());
        assertEquals(0, result.skippedDocuments());
        verify(elasticsearchService).deleteByFileMd5("11111111111111111111111111111111");

        ArgumentCaptor<FileUpload> fileCaptor = ArgumentCaptor.forClass(FileUpload.class);
        verify(fileUploadRepository).save(fileCaptor.capture());
        FileUpload savedFile = fileCaptor.getValue();
        assertEquals("11111111111111111111111111111111", savedFile.getFileMd5());
        assertEquals("eval-log4j-remediation.md", savedFile.getFileName());
        assertEquals("1", savedFile.getUserId());
        assertEquals("eval", savedFile.getOrgTag());
        assertTrue(savedFile.isPublic());
        assertEquals("eval-sca-log4j", savedFile.getIngestionTraceId());

        ArgumentCaptor<List<EsDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(docsCaptor.capture());
        List<EsDocument> indexedDocs = docsCaptor.getValue();
        assertEquals(1, indexedDocs.size());
        EsDocument indexed = indexedDocs.get(0);
        assertEquals("11111111111111111111111111111111:1", indexed.getId());
        assertEquals(1, indexed.getChunkId());
        assertEquals("11111111111111111111111111111111", indexed.getFileMd5());
        assertEquals("1", indexed.getUserId());
        assertTrue(indexed.isPublic());
        assertEquals("eval-sca-log4j", indexed.getIngestionTraceId());
        assertNotNull(indexed.getVector());
        assertEquals(2048, indexed.getVector().length);
        assertTrue(hasNonZeroMagnitude(indexed.getVector()));
    }

    @Test
    void updatesExistingFileMetadataBeforeReindexingCorpus() {
        FileUpload existing = new FileUpload();
        existing.setFileMd5("22222222222222222222222222222222");
        when(fileUploadRepository.findByFileMd5("22222222222222222222222222222222"))
                .thenReturn(Optional.of(existing));
        RagEvalCorpusDefinition definition = new RagEvalCorpusDefinition(
                "22222222222222222222222222222222",
                "eval-spring-remediation.md",
                "1",
                "eval",
                true,
                "eval-sca-spring",
                List.of(new RagEvalCorpusDefinition.ChunkDefinition(
                        1,
                        "Spring Framework 5.3.17 is affected by CVE-2022-22965. Fixed version: 5.3.18."
                ))
        );

        RagEvalCorpusImportResult result = service.importCorpus(List.of(definition));

        assertEquals(1, result.upsertedFiles());
        verify(fileUploadRepository).save(existing);
        assertEquals("eval-spring-remediation.md", existing.getFileName());
        assertEquals("1", existing.getUserId());
        assertEquals("eval", existing.getOrgTag());
        assertTrue(existing.isPublic());
    }

    @Test
    void loadsDefaultCorpusFromClasspath() {
        when(fileUploadRepository.findByFileMd5(any())).thenReturn(Optional.empty());

        RagEvalCorpusImportResult result = service.importDefaultCorpus();

        assertTrue(result.totalDocuments() >= 5);
        assertTrue(result.indexedChunks() >= 5);
        assertFalse(result.source().isBlank());
    }

    private boolean hasNonZeroMagnitude(float[] vector) {
        for (float value : vector) {
            if (value != 0.0f) {
                return true;
            }
        }
        return false;
    }
}
