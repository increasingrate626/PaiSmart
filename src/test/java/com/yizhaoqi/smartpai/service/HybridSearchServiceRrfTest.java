package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchServiceRrfTest {

    private final HybridSearchService service = new HybridSearchService();

    @Test
    void keepsOneResultForSameChunkAndGivesDualHitHigherRrfScore() {
        SearchResult vectorOnly = result("file-b", 2, 0.99, "vector only");
        SearchResult bothVector = result("file-a", 1, 0.20, "vector copy");
        SearchResult bothText = result("file-a", 1, 0.10, "text copy");

        List<SearchResult> merged = service.mergeWithRrf(
                List.of(bothVector, vectorOnly),
                List.of(bothText),
                10
        );

        assertEquals(2, merged.size());
        assertEquals("file-a", merged.get(0).getFileMd5());
        assertEquals(1, merged.get(0).getChunkId());
        assertTrue(merged.get(0).getScore() > merged.get(1).getScore());
        assertEquals("vector copy", merged.get(0).getTextContent());
    }

    @Test
    void limitsResultsByTopKAfterRrfSorting() {
        List<SearchResult> merged = service.mergeWithRrf(
                List.of(result("file-a", 1, 0.9, "a"), result("file-b", 2, 0.8, "b")),
                List.of(result("file-c", 3, 0.7, "c"), result("file-d", 4, 0.6, "d")),
                2
        );

        assertEquals(2, merged.size());
    }

    @Test
    void prefersDualHitThenHigherOriginalScore() {
        SearchResult singleHigherOriginalScore = result("file-b", 2, 0.99, "single");
        SearchResult dualLowOriginalScore = result("file-a", 1, 0.10, "dual");

        List<SearchResult> merged = service.mergeWithRrf(
                List.of(dualLowOriginalScore, singleHigherOriginalScore),
                List.of(dualLowOriginalScore),
                10
        );

        assertEquals("file-a:1", key(merged.get(0)));
        assertEquals("file-b:2", key(merged.get(1)));
    }

    @Test
    void usesStableChunkKeyWhenRrfAndOriginalScoreTie() {
        SearchResult stableA = result("file-c", 1, 0.50, "stable a");
        SearchResult stableB = result("file-c", 2, 0.50, "stable b");

        List<SearchResult> merged = service.mergeWithRrf(
                List.of(stableB),
                List.of(stableA),
                10
        );

        assertEquals("file-c:1", key(merged.get(0)));
        assertEquals("file-c:2", key(merged.get(1)));
    }

    @Test
    void keepsMetadataFromMergedSearchResult() {
        SearchResult vector = new SearchResult(
                "file-a",
                1,
                "content",
                0.7,
                "owner",
                "org",
                true,
                "a.txt",
                "trc-ingest-file-a"
        );

        List<SearchResult> merged = service.mergeWithRrf(List.of(vector), List.of(), 10);

        SearchResult result = merged.get(0);
        assertEquals("file-a", result.getFileMd5());
        assertEquals(1, result.getChunkId());
        assertEquals("content", result.getTextContent());
        assertEquals("owner", result.getUserId());
        assertEquals("org", result.getOrgTag());
        assertTrue(result.getIsPublic());
        assertEquals("a.txt", result.getFileName());
        assertEquals("trc-ingest-file-a", result.getIngestionTraceId());
    }

    private SearchResult result(String fileMd5, int chunkId, double score, String text) {
        return new SearchResult(fileMd5, chunkId, text, score, "owner", "org", true, fileMd5 + ".txt", "trc-" + fileMd5);
    }

    private String key(SearchResult result) {
        return result.getFileMd5() + ":" + result.getChunkId();
    }
}
