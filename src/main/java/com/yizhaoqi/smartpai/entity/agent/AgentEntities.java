package com.yizhaoqi.smartpai.entity.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentEntities {
    private List<String> components = new ArrayList<>();
    private List<String> versions = new ArrayList<>();
    private List<String> cves = new ArrayList<>();
    private List<String> projects = new ArrayList<>();

    public boolean hasAny() {
        return hasValues(components) || hasValues(versions) || hasValues(cves) || hasValues(projects);
    }

    private boolean hasValues(List<String> values) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank());
    }
}
