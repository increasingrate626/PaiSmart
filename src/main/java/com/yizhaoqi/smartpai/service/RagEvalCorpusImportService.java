package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.eval.RagEvalCorpusDefinition;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class RagEvalCorpusImportService {

    private static final String DEFAULT_CORPUS_RESOURCE = "eval/sca_eval_corpus.json";
    private static final int VECTOR_DIMENSIONS = 2048;
    private static final TypeReference<List<RagEvalCorpusDefinition>> CORPUS_LIST_TYPE = new TypeReference<>() {
    };

    private final ElasticsearchService elasticsearchService;
    private final FileUploadRepository fileUploadRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagEvalCorpusImportService(ElasticsearchService elasticsearchService,
                                      FileUploadRepository fileUploadRepository) {
        this.elasticsearchService = elasticsearchService;
        this.fileUploadRepository = fileUploadRepository;
    }

    @Transactional
    public RagEvalCorpusImportResult importDefaultCorpus() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_CORPUS_RESOURCE);
        try {
            List<RagEvalCorpusDefinition> definitions = objectMapper.readValue(resource.getInputStream(), CORPUS_LIST_TYPE);
            RagEvalCorpusImportResult result = importCorpus(definitions);
            return new RagEvalCorpusImportResult(
                    "classpath:" + DEFAULT_CORPUS_RESOURCE,
                    result.totalDocuments(),
                    result.indexedChunks(),
                    result.upsertedFiles(),
                    result.skippedDocuments()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default SCA eval corpus from " + DEFAULT_CORPUS_RESOURCE, e);
        }
    }

    @Transactional
    public RagEvalCorpusImportResult importCorpus(List<RagEvalCorpusDefinition> definitions) {
        List<RagEvalCorpusDefinition> safeDefinitions = definitions != null ? definitions : List.of();
        int indexedChunks = 0;
        int upsertedFiles = 0;
        int skippedDocuments = 0;

        for (RagEvalCorpusDefinition definition : safeDefinitions) {
            if (!isValidDocument(definition)) {
                skippedDocuments++;
                continue;
            }

            FileUpload fileUpload = fileUploadRepository.findByFileMd5(definition.fileMd5().trim())
                    .orElseGet(FileUpload::new);
            applyFileMetadata(fileUpload, definition);
            fileUploadRepository.save(fileUpload);
            upsertedFiles++;

            elasticsearchService.deleteByFileMd5(definition.fileMd5().trim());
            List<EsDocument> docs = toEsDocuments(definition);
            if (!docs.isEmpty()) {
                elasticsearchService.bulkIndex(docs);
                indexedChunks += docs.size();
            }
        }

        return new RagEvalCorpusImportResult("", safeDefinitions.size(), indexedChunks, upsertedFiles, skippedDocuments);
    }

    private boolean isValidDocument(RagEvalCorpusDefinition definition) {
        return definition != null
                && definition.fileMd5() != null
                && !definition.fileMd5().isBlank()
                && definition.fileName() != null
                && !definition.fileName().isBlank()
                && definition.chunks() != null
                && definition.chunks().stream().anyMatch(this::isValidChunk);
    }

    private boolean isValidChunk(RagEvalCorpusDefinition.ChunkDefinition chunk) {
        return chunk != null && chunk.chunkId() > 0 && chunk.textContent() != null && !chunk.textContent().isBlank();
    }

    private void applyFileMetadata(FileUpload fileUpload, RagEvalCorpusDefinition definition) {
        String fullText = definition.chunks().stream()
                .filter(this::isValidChunk)
                .map(RagEvalCorpusDefinition.ChunkDefinition::textContent)
                .reduce("", (left, right) -> left + right);
        fileUpload.setFileMd5(definition.fileMd5().trim());
        fileUpload.setFileName(definition.fileName().trim());
        fileUpload.setTotalSize(fullText.getBytes(StandardCharsets.UTF_8).length);
        fileUpload.setStatus(1);
        fileUpload.setUserId(defaultString(definition.userId(), "1"));
        fileUpload.setOrgTag(defaultString(definition.orgTag(), "eval"));
        fileUpload.setPublic(definition.isPublic());
        fileUpload.setIngestionTraceId(defaultString(definition.ingestionTraceId(), "eval-sca-corpus"));
    }

    private List<EsDocument> toEsDocuments(RagEvalCorpusDefinition definition) {
        List<EsDocument> documents = new ArrayList<>();
        for (RagEvalCorpusDefinition.ChunkDefinition chunk : definition.chunks()) {
            if (!isValidChunk(chunk)) {
                continue;
            }
            String fileMd5 = definition.fileMd5().trim();
            documents.add(new EsDocument(
                    fileMd5 + ":" + chunk.chunkId(),
                    fileMd5,
                    chunk.chunkId(),
                    chunk.textContent().trim(),
                    placeholderVector(fileMd5, chunk.chunkId()),
                    "eval-corpus-v1",
                    defaultString(definition.userId(), "1"),
                    defaultString(definition.orgTag(), "eval"),
                    definition.isPublic(),
                    defaultString(definition.ingestionTraceId(), "eval-sca-corpus")
            ));
        }
        return documents;
    }

    private float[] placeholderVector(String fileMd5, int chunkId) {
        float[] vector = new float[VECTOR_DIMENSIONS];
        int slot = Math.floorMod((fileMd5 + ":" + chunkId).hashCode(), VECTOR_DIMENSIONS);
        vector[slot] = 1.0f;
        return vector;
    }

    private String defaultString(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value.trim() : defaultValue;
    }
}
