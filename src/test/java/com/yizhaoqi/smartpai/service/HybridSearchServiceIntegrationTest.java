package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceIntegrationTest {

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    @Mock
    private FileUploadRepository fileUploadRepository;

    private HybridSearchService service;
    private List<SearchRequest> capturedRequests;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new HybridSearchService();
        setField("esClient", esClient);
        setField("embeddingClient", embeddingClient);
        setField("userRepository", userRepository);
        setField("orgTagCacheService", orgTagCacheService);
        setField("fileUploadRepository", fileUploadRepository);
        capturedRequests = new ArrayList<>();

        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("security", "backend"));
        when(fileUploadRepository.findByFileMd5In(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> md5s = invocation.getArgument(0);
            return md5s.stream().map(this::upload).toList();
        });
    }

    @Test
    void searchWithPermissionMergesVectorAndTextBranchesWithRrfAndKeepsPermissionFilters() throws Exception {
        when(embeddingClient.embed(List.of("log4j risk"))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            if (isVectorRequest(request)) {
                return response(
                        hit(doc("file-a", 1, "vector and text", "trc-a"), 0.20),
                        hit(doc("file-b", 2, "vector only", "trc-b"), 0.99)
                );
            }
            return response(hit(doc("file-a", 1, "text copy", "trc-a"), 0.10));
        });

        List<SearchResult> results = service.searchWithPermission("log4j risk", "alice", 10);

        assertEquals(2, results.size());
        assertEquals("file-a", results.get(0).getFileMd5());
        assertEquals(1, results.get(0).getChunkId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
        assertEquals("file-a.txt", results.get(0).getFileName());
        assertEquals("trc-a", results.get(0).getIngestionTraceId());
        assertEquals(2, capturedRequests.size());
        assertTrue(isVectorRequest(capturedRequests.get(0)));
        assertFalse(requestText(capturedRequests.get(0)).contains("textContent"));
        assertTrue(requestText(capturedRequests.get(0)).contains("userId"));
        assertTrue(requestText(capturedRequests.get(0)).contains("public"));
        assertTrue(requestText(capturedRequests.get(0)).contains("orgTag"));
        assertTrue(requestText(capturedRequests.get(1)).contains("textContent"));
        assertTrue(requestText(capturedRequests.get(1)).contains("userId"));
        assertTrue(requestText(capturedRequests.get(1)).contains("public"));
        assertTrue(requestText(capturedRequests.get(1)).contains("orgTag"));
    }

    @Test
    void searchWithPermissionAddsScaBoostsToTextBranchAndKeepsPermissionFilters() throws Exception {
        String query = "golang org x crypto cve 2023 48795 v0.16.0";
        when(embeddingClient.embed(List.of(query))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            if (isVectorRequest(request)) {
                return response(hit(doc("file-vector", 1, "vector result", "trc-vector"), 0.6));
            }
            return response(hit(doc("file-text", 1, "text result", "trc-text"), 0.7));
        });

        service.searchWithPermission(query, "alice", 8);

        assertEquals(2, capturedRequests.size());
        String textRequest = requestText(capturedRequests.get(1));
        assertTrue(textRequest.contains("CVE-2023-48795"));
        assertTrue(textRequest.contains("golang.org/x/crypto"));
        assertTrue(textRequest.contains("v0.16.0"));
        assertTrue(textRequest.contains("boost"));
        assertTrue(textRequest.contains("userId"));
        assertTrue(textRequest.contains("public"));
        assertTrue(textRequest.contains("orgTag"));
    }

    @Test
    void searchWithPermissionPrioritizesExactScaTextHitsBeforeRrfMerge() throws Exception {
        String query = "snakeyaml cve 2022 1471 snakeyaml 1.33 CVE-2022-1471";
        when(embeddingClient.embed(List.of(query))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            if (isVectorRequest(request)) {
                return response(hit(doc("file-vector", 1, "unrelated CVE-2022-40152", "trc-vector"), 0.99));
            }
            return response(
                    hit(doc("file-text-other", 1, "SCA unrelated CVE-2022-40152", "trc-other"), 0.9),
                    hit(doc("file-exact", 1, "SCA snakeyaml 1.33 CVE-2022-1471 fixed in 2.0", "trc-exact"), 5.0)
            );
        });

        List<SearchResult> results = service.searchWithPermission(query, "alice", 8);

        assertEquals("file-exact", results.get(0).getFileMd5());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
    }

    @Test
    void searchWithPermissionPrioritizesExactScaHitsOverUnrelatedDualBranchHits() throws Exception {
        String query = "snakeyaml cve 2022 1471 snakeyaml 1.33 CVE-2022-1471";
        when(embeddingClient.embed(List.of(query))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            if (isVectorRequest(request)) {
                return response(hit(doc("file-unrelated", 1, "SCA unrelated CVE-2022-40152", "trc-unrelated"), 0.99));
            }
            return response(
                    hit(doc("file-unrelated", 1, "SCA unrelated CVE-2022-40152", "trc-unrelated"), 0.9),
                    hit(doc("file-exact", 1, "SCA snakeyaml 1.33 CVE-2022-1471 fixed in 2.0", "trc-exact"), 5.0)
            );
        });

        List<SearchResult> results = service.searchWithPermission(query, "alice", 8);

        assertEquals("file-exact", results.get(0).getFileMd5());
    }

    @Test
    void searchWithPermissionFallsBackToTextOnlyWhenEmbeddingReturnsEmpty() throws Exception {
        when(embeddingClient.embed(List.of("plain text"))).thenReturn(List.of());
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            return response(hit(doc("file-t", 9, "text only", "trc-t"), 0.8));
        });

        List<SearchResult> results = service.searchWithPermission("plain text", "alice", 3);

        assertEquals(1, results.size());
        assertEquals("file-t", results.get(0).getFileMd5());
        assertEquals(1, capturedRequests.size());
        assertFalse(isVectorRequest(capturedRequests.get(0)));
        verify(esClient, times(1)).search(anySearchFunction(), eq(EsDocument.class));
    }

    @Test
    void textOnlyFallbackAddsScaBoostsWhenEmbeddingReturnsEmpty() throws Exception {
        String query = "commons text cve 2022 42889 1.9";
        when(embeddingClient.embed(List.of(query))).thenReturn(List.of());
        when(esClient.search(anySearchFunction(), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = buildRequest(invocation.getArgument(0));
            capturedRequests.add(request);
            return response(hit(doc("file-t", 9, "text only", "trc-t"), 0.8));
        });

        service.searchWithPermission(query, "alice", 3);

        assertEquals(1, capturedRequests.size());
        String request = requestText(capturedRequests.get(0));
        assertTrue(request.contains("CVE-2022-42889"));
        assertTrue(request.contains("commons-text"));
        assertTrue(request.contains("1.9"));
        assertTrue(request.contains("boost"));
        assertTrue(request.contains("userId"));
        assertTrue(request.contains("public"));
        assertTrue(request.contains("orgTag"));
    }

    @Test
    void searchWithPermissionReturnsTextResultsWhenVectorBranchFails() throws Exception {
        when(embeddingClient.embed(List.of("query"))).thenReturn(List.of(new float[]{0.1f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class)))
                .thenThrow(new RuntimeException("vector down"))
                .thenAnswer(invocation -> response(hit(doc("file-text", 1, "text result", "trc-text"), 0.7)));

        List<SearchResult> results = service.searchWithPermission("query", "alice", 5);

        assertEquals(1, results.size());
        assertEquals("file-text", results.get(0).getFileMd5());
        verify(esClient, times(2)).search(anySearchFunction(), eq(EsDocument.class));
    }

    @Test
    void searchWithPermissionReturnsVectorResultsWhenTextBranchFails() throws Exception {
        when(embeddingClient.embed(List.of("query"))).thenReturn(List.of(new float[]{0.1f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class)))
                .thenAnswer(invocation -> response(hit(doc("file-vector", 1, "vector result", "trc-vector"), 0.9)))
                .thenThrow(new RuntimeException("text down"));

        List<SearchResult> results = service.searchWithPermission("query", "alice", 5);

        assertEquals(1, results.size());
        assertEquals("file-vector", results.get(0).getFileMd5());
        verify(esClient, times(2)).search(anySearchFunction(), eq(EsDocument.class));
    }

    @Test
    void searchWithPermissionUsesLegacyTextFallbackWhenBothRrfBranchesFail() throws Exception {
        when(embeddingClient.embed(List.of("query"))).thenReturn(List.of(new float[]{0.1f}));
        when(esClient.search(anySearchFunction(), eq(EsDocument.class)))
                .thenThrow(new RuntimeException("vector down"))
                .thenThrow(new RuntimeException("text down"))
                .thenAnswer(invocation -> response(hit(doc("file-fallback", 1, "fallback result", "trc-fallback"), 0.6)));

        List<SearchResult> results = service.searchWithPermission("query", "alice", 5);

        assertEquals(1, results.size());
        assertEquals("file-fallback", results.get(0).getFileMd5());
        verify(esClient, times(3)).search(anySearchFunction(), eq(EsDocument.class));
    }

    @SuppressWarnings("unchecked")
    private Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> anySearchFunction() {
        return any(Function.class);
    }

    private SearchRequest buildRequest(Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn) {
        return fn.apply(new SearchRequest.Builder()).build();
    }

    private boolean isVectorRequest(SearchRequest request) {
        return request.knn() != null && !request.knn().isEmpty();
    }

    private String requestText(SearchRequest request) {
        return request.toString();
    }

    private Hit<EsDocument> hit(EsDocument doc, double score) {
        return Hit.of(h -> h.index("knowledge_base").id(doc.getId()).score(score).source(doc));
    }

    @SafeVarargs
    private SearchResponse<EsDocument> response(Hit<EsDocument>... hits) {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h
                        .total(t -> t.value(hits.length).relation(TotalHitsRelation.Eq))
                        .hits(List.of(hits))
                )
        );
    }

    private EsDocument doc(String fileMd5, int chunkId, String text, String ingestionTraceId) {
        return new EsDocument(
                fileMd5 + "-" + chunkId,
                fileMd5,
                chunkId,
                text,
                new float[]{0.1f},
                "test-model",
                "7",
                "security",
                true,
                ingestionTraceId
        );
    }

    private FileUpload upload(String fileMd5) {
        FileUpload upload = new FileUpload();
        upload.setFileMd5(fileMd5);
        upload.setFileName(fileMd5 + ".txt");
        return upload;
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = HybridSearchService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
