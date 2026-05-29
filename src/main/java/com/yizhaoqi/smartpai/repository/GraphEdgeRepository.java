package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.GraphEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface GraphEdgeRepository extends JpaRepository<GraphEdge, Long> {

    @Transactional
    @Modifying
    void deleteByFileMd5(String fileMd5);

    @Query("""
            select e from GraphEdge e
            where e.sourceNodeId in :sourceNodeIds
              and (e.userId = :userId or e.isPublic = true or e.orgTag in :orgTags)
            """)
    List<GraphEdge> findAccessibleBySourceNodeIdIn(
            @Param("sourceNodeIds") List<Long> sourceNodeIds,
            @Param("userId") String userId,
            @Param("orgTags") List<String> orgTags
    );
}
