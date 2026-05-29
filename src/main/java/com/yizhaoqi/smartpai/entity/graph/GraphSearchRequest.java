package com.yizhaoqi.smartpai.entity.graph;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphSearchRequest {
    private AgentEntities entities;
    private int maxDepth = 2;
}
