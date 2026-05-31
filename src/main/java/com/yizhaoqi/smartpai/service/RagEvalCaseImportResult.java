package com.yizhaoqi.smartpai.service;

public record RagEvalCaseImportResult(
        String source,
        int totalCases,
        int insertedCases,
        int updatedCases,
        int skippedCases
) {
}
