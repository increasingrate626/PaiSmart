package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RECALL_MULTIPLIER = 30;
    private static final int RRF_K = 60;
    private static final String INDEX_NAME = "knowledge_base";
    private static final String TEXT_FIELD = "textContent";
    private static final double SCA_EXACT_SCORE_WEIGHT = 0.01d;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired(required = false)
    private ScaSearchQueryEnhancer scaSearchQueryEnhancer = new ScaSearchQueryEnhancer();

    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("Start permissioned hybrid search, query={}, userId={}", query, userId);

        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery = scaSearchQueryEnhancer.enhance(query);
            List<Float> queryVector = embedToVectorList(query);

            if (queryVector == null) {
                logger.warn("Embedding generation failed; using text-only permissioned search");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK, enhancedQuery);
            }

            List<SearchResult> vectorResults = List.of();
            List<SearchResult> textResults = List.of();
            boolean vectorFailed = false;
            boolean textFailed = false;

            try {
                vectorResults = executeVectorSearchWithPermission(queryVector, userDbId, userEffectiveTags, topK);
            } catch (Exception e) {
                vectorFailed = true;
                logger.warn("Vector recall branch failed; continuing with text branch", e);
            }

            try {
                textResults = executeTextSearchWithPermission(query, userDbId, userEffectiveTags, topK, enhancedQuery);
            } catch (Exception e) {
                textFailed = true;
                logger.warn("Text recall branch failed; continuing with vector branch", e);
            }

            if (vectorFailed && textFailed) {
                throw new RuntimeException("RRF hybrid search branches failed");
            }

            List<SearchResult> results = prioritizeScaExactMatches(
                    mergeWithRrf(vectorResults, textResults, expandedCandidateLimit(topK)),
                    enhancedQuery
            ).stream().limit(topK).toList();
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("Permissioned hybrid search failed", e);
            try {
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("Permissioned text fallback also failed", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    private List<SearchResult> executeVectorSearchWithPermission(List<Float> queryVector,
                                                                 String userDbId,
                                                                 List<String> userEffectiveTags,
                                                                 int topK) throws Exception {
        int recallK = topK * RECALL_MULTIPLIER;
        SearchResponse<EsDocument> response = esClient.search(s -> {
                    s.index(INDEX_NAME);
                    s.knn(kn -> kn
                            .field("vector")
                            .queryVector(queryVector)
                            .k(recallK)
                            .numCandidates(recallK)
                    );
                    s.query(q -> q.bool(b -> b.filter(f -> permissionFilter(f, userDbId, userEffectiveTags))));
                    s.size(recallK);
                    return s;
                }, EsDocument.class);
        return toSearchResults(response);
    }

    private List<SearchResult> executeTextSearchWithPermission(String query,
                                                               String userDbId,
                                                               List<String> userEffectiveTags,
                                                               int topK,
                                                               ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) throws Exception {
        int recallK = topK * RECALL_MULTIPLIER;
        SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .query(q -> textPermissionQuery(q, enhancedQuery, userDbId, userEffectiveTags))
                        .minScore(0.3d)
                        .size(recallK),
                EsDocument.class
        );
        return prioritizeScaExactMatches(toSearchResults(response), enhancedQuery);
    }

    private List<SearchResult> textOnlySearchWithPermission(String query,
                                                            String userDbId,
                                                            List<String> userEffectiveTags,
                                                            int topK) {
        return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK, scaSearchQueryEnhancer.enhance(query));
    }

    private List<SearchResult> textOnlySearchWithPermission(String query,
                                                            String userDbId,
                                                            List<String> userEffectiveTags,
                                                            int topK,
                                                            ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> textPermissionQuery(q, enhancedQuery, userDbId, userEffectiveTags))
                            .minScore(0.3d)
                            .size(expandedCandidateLimit(topK)),
                    EsDocument.class
            );
            List<SearchResult> results = prioritizeScaExactMatches(toSearchResults(response), enhancedQuery)
                    .stream()
                    .limit(topK)
                    .toList();
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("Permissioned text-only search failed", e);
            return new ArrayList<>();
        }
    }

    private ObjectBuilder<Query> textPermissionQuery(Query.Builder q,
                                                     ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery,
                                                     String userDbId,
                                                     List<String> userEffectiveTags) {
        return q.bool(b -> {
            b.must(m -> m.match(ma -> ma
                    .field(TEXT_FIELD)
                    .query(enhancedQuery.normalizedQuery())
            ));
            addScaBoostQueries(b, enhancedQuery);
            b.filter(f -> permissionFilter(f, userDbId, userEffectiveTags));
            return b;
        });
    }

    private int expandedCandidateLimit(int topK) {
        return Math.max(topK, topK * RECALL_MULTIPLIER);
    }

    private ObjectBuilder<Query> permissionFilter(Query.Builder f, String userDbId, List<String> userEffectiveTags) {
        return f.bool(bf -> bf
                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                .should(s3 -> {
                    if (userEffectiveTags.isEmpty()) {
                        return s3.matchNone(mn -> mn);
                    } else if (userEffectiveTags.size() == 1) {
                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                    } else {
                        return s3.bool(inner -> {
                            userEffectiveTags.forEach(tag -> inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                            return inner;
                        });
                    }
                })
        );
    }

    private void addScaBoostQueries(BoolQuery.Builder boolQuery, ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) {
        if (enhancedQuery == null || !enhancedQuery.hasBoosts()) {
            return;
        }
        for (ScaSearchQueryEnhancer.BoostPhrase boostPhrase : enhancedQuery.boostPhrases()) {
            boolQuery.should(s -> s.matchPhrase(mp -> mp
                    .field(TEXT_FIELD)
                    .query(boostPhrase.phrase())
                    .boost(boostPhrase.boost())
            ));
        }
    }

    private List<SearchResult> toSearchResults(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic(),
                            null,
                            hit.source().getIngestionTraceId()
                    );
                })
                .toList();
    }

    private List<SearchResult> prioritizeScaExactMatches(List<SearchResult> results,
                                                         ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) {
        if (results == null || results.isEmpty() || enhancedQuery == null || !enhancedQuery.hasBoosts()) {
            return results;
        }
        return results.stream()
                .peek(result -> applyExactScaScoreBoost(result, enhancedQuery))
                .sorted(Comparator
                        .comparingDouble((SearchResult result) -> exactScaScore(result, enhancedQuery)).reversed()
                        .thenComparing(Comparator.comparingDouble(this::safeResultScore).reversed()))
                .toList();
    }

    private void applyExactScaScoreBoost(SearchResult result, ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) {
        double exactScore = exactScaScore(result, enhancedQuery);
        if (exactScore <= 0.0d) {
            return;
        }
        result.setScore(safeResultScore(result) + exactScore * SCA_EXACT_SCORE_WEIGHT);
    }

    private double exactScaScore(SearchResult result, ScaSearchQueryEnhancer.EnhancedQuery enhancedQuery) {
        String text = result.getTextContent() == null ? "" : result.getTextContent().toLowerCase();
        double score = 0.0d;
        for (ScaSearchQueryEnhancer.BoostPhrase boostPhrase : enhancedQuery.boostPhrases()) {
            String phrase = boostPhrase.phrase() == null ? "" : boostPhrase.phrase().toLowerCase();
            if (!phrase.isBlank() && text.contains(phrase)) {
                score += boostPhrase.boost();
            }
        }
        return score;
    }

    private double safeResultScore(SearchResult result) {
        return result.getScore() != null ? result.getScore() : 0.0d;
    }

    List<SearchResult> mergeWithRrf(List<SearchResult> vectorResults, List<SearchResult> textResults, int topK) {
        Map<String, RrfCandidate> candidates = new LinkedHashMap<>();
        addRrfCandidates(candidates, vectorResults, true);
        addRrfCandidates(candidates, textResults, false);

        return candidates.values().stream()
                .sorted(Comparator
                        .comparingDouble(RrfCandidate::rrfScore).reversed()
                        .thenComparing(RrfCandidate::dualHit, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingDouble(RrfCandidate::bestOriginalScore).reversed())
                        .thenComparing(RrfCandidate::key))
                .limit(topK)
                .map(RrfCandidate::toSearchResult)
                .toList();
    }

    private void addRrfCandidates(Map<String, RrfCandidate> candidates, List<SearchResult> results, boolean vector) {
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            String key = chunkKey(result);
            RrfCandidate candidate = candidates.computeIfAbsent(key, ignored -> new RrfCandidate(key, result));
            candidate.add(result, i + 1, vector);
        }
    }

    private String chunkKey(SearchResult result) {
        return result.getFileMd5() + ":" + result.getChunkId();
    }

    public List<SearchResult> search(String query, int topK) {
        try {
            List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index(INDEX_NAME);
                        int recallK = topK * RECALL_MULTIPLIER;
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );
                        s.query(q -> q.match(m -> m.field(TEXT_FIELD).query(query)));
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field(TEXT_FIELD)
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return toSearchResults(response);
        } catch (Exception e) {
            logger.error("Legacy hybrid search failed", e);
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("Legacy text fallback also failed", fallbackError);
                throw new RuntimeException("Search failed completely", fallbackError);
            }
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .query(q -> q.match(m -> m.field(TEXT_FIELD).query(query)))
                        .size(topK),
                EsDocument.class
        );
        return toSearchResults(response);
    }

    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("Generated embedding vector is empty");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("Embedding generation failed", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = findUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("Failed to load effective org tags for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        try {
            return String.valueOf(findUser(userId).getId());
        } catch (Exception e) {
            logger.error("Failed to load database user id for userId={}", userId, e);
            throw new RuntimeException("Failed to load database user id", e);
        }
    }

    private User findUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("Failed to attach file names to search results", e);
        }
    }

    private static class RrfCandidate {
        private final String key;
        private final SearchResult result;
        private double rrfScore;
        private double bestOriginalScore;
        private boolean vectorHit;
        private boolean textHit;

        private RrfCandidate(String key, SearchResult result) {
            this.key = key;
            this.result = result;
            this.bestOriginalScore = safeScore(result);
        }

        private void add(SearchResult next, int rank, boolean vector) {
            rrfScore += 1.0d / (RRF_K + rank);
            if (vector) {
                vectorHit = true;
            } else {
                textHit = true;
            }
            if (safeScore(next) > bestOriginalScore) {
                bestOriginalScore = safeScore(next);
            }
        }

        private String key() {
            return key;
        }

        private double rrfScore() {
            return rrfScore;
        }

        private boolean dualHit() {
            return vectorHit && textHit;
        }

        private double bestOriginalScore() {
            return bestOriginalScore;
        }

        private SearchResult toSearchResult() {
            return new SearchResult(
                    result.getFileMd5(),
                    result.getChunkId(),
                    result.getTextContent(),
                    rrfScore,
                    result.getUserId(),
                    result.getOrgTag(),
                    Boolean.TRUE.equals(result.getIsPublic()),
                    result.getFileName(),
                    result.getIngestionTraceId()
            );
        }

        private static double safeScore(SearchResult result) {
            return result.getScore() != null ? result.getScore() : 0.0d;
        }
    }
}
