package com.yizhaoqi.smartpai.entity.agent;

import com.yizhaoqi.smartpai.entity.SearchResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgenticRagResult {
    private String finalContext;
    private List<SearchResult> selectedEvidence;
    private List<AgentTraceStep> trace;
    private Map<Integer, String> referenceMapping;
}
