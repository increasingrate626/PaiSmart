package com.yizhaoqi.smartpai.entity.eval;

import java.util.List;

public record RagEvalCorpusDefinition(
        String fileMd5,
        String fileName,
        String userId,
        String orgTag,
        boolean isPublic,
        String ingestionTraceId,
        List<ChunkDefinition> chunks
) {
    public record ChunkDefinition(
            int chunkId,
            String textContent
    ) {
    }
}
