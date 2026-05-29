package com.yizhaoqi.smartpai.entity.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EvidenceAssessment {
    private boolean sufficient;
    private List<String> missingAspects = new ArrayList<>();
    private List<String> selectedChunkIds = new ArrayList<>();
    private List<String> rewriteQueries = new ArrayList<>();
    private String decisionReason;
}
