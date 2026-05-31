package com.yizhaoqi.smartpai.service;

public record RagEvalCorpusImportResult(
        String source,
        int totalDocuments,
        int indexedChunks,
        int upsertedFiles,
        int skippedDocuments
) {
}
