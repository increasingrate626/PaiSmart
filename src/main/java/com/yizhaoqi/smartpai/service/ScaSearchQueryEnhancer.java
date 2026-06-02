package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ScaSearchQueryEnhancer {

    private final ScaEntityExtractionService entityExtractionService;

    public ScaSearchQueryEnhancer() {
        this(new ScaEntityExtractionService());
    }

    @Autowired
    public ScaSearchQueryEnhancer(ScaEntityExtractionService entityExtractionService) {
        this.entityExtractionService = entityExtractionService;
    }

    public EnhancedQuery enhance(String query) {
        String baseQuery = query == null ? "" : query.trim();
        AgentEntities entities = entityExtractionService.extract(baseQuery);
        LinkedHashSet<String> normalizedParts = new LinkedHashSet<>();
        if (!baseQuery.isBlank()) {
            normalizedParts.add(baseQuery);
        }

        LinkedHashSet<String> mustTerms = new LinkedHashSet<>();
        LinkedHashSet<BoostPhrase> boostPhrases = new LinkedHashSet<>();

        addEntities(entities.getCves(), normalizedParts, mustTerms, boostPhrases, 10.0f);
        addEntities(entities.getComponents(), normalizedParts, mustTerms, boostPhrases, 6.0f);
        addEntities(entities.getVersions(), normalizedParts, mustTerms, boostPhrases, 3.0f);
        addComponentCveCombinations(entities, normalizedParts, boostPhrases);

        String normalizedQuery = String.join(" ", normalizedParts);
        return new EnhancedQuery(normalizedQuery.isBlank() ? baseQuery : normalizedQuery,
                List.copyOf(mustTerms),
                List.copyOf(boostPhrases));
    }

    private void addEntities(List<String> values,
                             Set<String> normalizedParts,
                             Set<String> mustTerms,
                             Set<BoostPhrase> boostPhrases,
                             float boost) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.isBlank()) {
                continue;
            }
            normalizedParts.add(normalized);
            mustTerms.add(normalized);
            boostPhrases.add(new BoostPhrase(normalized, boost));
        }
    }

    private void addComponentCveCombinations(AgentEntities entities,
                                             Set<String> normalizedParts,
                                             Set<BoostPhrase> boostPhrases) {
        List<String> components = safeValues(entities.getComponents());
        List<String> cves = safeValues(entities.getCves());
        for (String component : components) {
            for (String cve : cves) {
                String phrase = component + " " + cve;
                normalizedParts.add(phrase);
                boostPhrases.add(new BoostPhrase(phrase, 8.0f));
            }
        }
    }

    private List<String> safeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String next = normalize(value);
            if (!next.isBlank()) {
                normalized.add(next);
            }
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public record EnhancedQuery(String normalizedQuery, List<String> mustTerms, List<BoostPhrase> boostPhrases) {
        public boolean hasBoosts() {
            return boostPhrases != null && !boostPhrases.isEmpty();
        }
    }

    public record BoostPhrase(String phrase, float boost) {
    }
}
