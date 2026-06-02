package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScaEntityExtractionServiceTest {

    private final ScaEntityExtractionService service = new ScaEntityExtractionService();

    @Test
    void extractsComponentVersionAndCveFromQuestion() {
        AgentEntities entities = service.extract("log4j-core 2.14.1 affected by CVE-2021-44228");

        assertEquals(List.of("log4j-core"), entities.getComponents());
        assertEquals(List.of("2.14.1"), entities.getVersions());
        assertEquals(List.of("CVE-2021-44228"), entities.getCves());
        assertFalse(entities.getProjects().contains("affected"));
    }

    @Test
    void extractsMavenCoordinatePurlAndProjectSignals() {
        AgentEntities entities = service.extract(
                "project: billing-api service: checkout pkg:maven/org.apache.logging.log4j/log4j-core and org.springframework:spring-core");

        assertEquals(List.of("log4j-core", "org.springframework:spring-core", "spring-core"), entities.getComponents());
        assertEquals(List.of("billing-api", "checkout"), entities.getProjects());
    }

    @Test
    void normalizesCaseWhitespaceDuplicatesAndNoise() {
        AgentEntities entities = service.extract("  cve-2021-44228 CVE-2021-44228 log4j-core LOG4J-CORE 12345 2.14.1 2.14.1 ");

        assertEquals(List.of("log4j-core"), entities.getComponents());
        assertEquals(List.of("2.14.1"), entities.getVersions());
        assertEquals(List.of("CVE-2021-44228"), entities.getCves());
    }

    @Test
    void extractsLooseCveComponentAliasesAndShortVersions() {
        AgentEntities entities = service.extract("commons text cve 2022 42889 commons-text 1.9");

        assertEquals(List.of("commons-text"), entities.getComponents());
        assertEquals(List.of("1.9"), entities.getVersions());
        assertEquals(List.of("CVE-2022-42889"), entities.getCves());
    }

    @Test
    void extractsGoModuleAliasesAndVPrefixedVersions() {
        AgentEntities entities = service.extract("golang org x crypto cve 2023 48795 golang.org/x/crypto v0.16.0");

        assertEquals(List.of("golang.org/x/crypto"), entities.getComponents());
        assertEquals(List.of("v0.16.0"), entities.getVersions());
        assertEquals(List.of("CVE-2023-48795"), entities.getCves());
    }

    @Test
    void extractsSingleTokenComponentsWhenQuestionHasScaSignals() {
        AgentEntities entities = service.extract("guava curl axios django CVE-2023-2976");

        assertEquals(List.of("guava", "curl", "axios", "django"), entities.getComponents());
        assertEquals(List.of("CVE-2023-2976"), entities.getCves());
    }

    @Test
    void returnsEmptyEntitiesForOrdinaryQuestion() {
        AgentEntities entities = service.extract("today meeting notes and action items");

        assertFalse(entities.hasAny());
    }
}
