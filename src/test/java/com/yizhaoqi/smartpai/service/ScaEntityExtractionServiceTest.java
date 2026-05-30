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
        AgentEntities entities = service.extract("log4j-core 2.14.1 受 CVE-2021-44228 影响吗");

        assertEquals(List.of("log4j-core"), entities.getComponents());
        assertEquals(List.of("2.14.1"), entities.getVersions());
        assertEquals(List.of("CVE-2021-44228"), entities.getCves());
        assertFalse(entities.getProjects().contains("影响吗"));
    }

    @Test
    void extractsMavenCoordinatePurlAndProjectSignals() {
        AgentEntities entities = service.extract(
                "project: billing-api service: checkout pkg:maven/org.apache.logging.log4j/log4j-core 以及 org.springframework:spring-core 是否受影响");

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
    void returnsEmptyEntitiesForOrdinaryQuestion() {
        AgentEntities entities = service.extract("今天的会议纪要有哪些重点");

        assertFalse(entities.hasAny());
    }
}
