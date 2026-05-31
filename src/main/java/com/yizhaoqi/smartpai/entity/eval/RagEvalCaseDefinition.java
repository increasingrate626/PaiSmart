package com.yizhaoqi.smartpai.entity.eval;

import java.util.List;

public record RagEvalCaseDefinition(
        String question,
        List<String> expectedComponents,
        List<String> expectedVersions,
        List<String> expectedCves,
        List<String> expectedProjects,
        List<String> expectedFixedVersions,
        List<String> expectedEvidence,
        List<String> expectedKeyPoints,
        List<String> tags,
        boolean enabled
) {
}
