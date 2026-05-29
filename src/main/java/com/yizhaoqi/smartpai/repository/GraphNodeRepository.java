package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.GraphNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface GraphNodeRepository extends JpaRepository<GraphNode, Long> {

    @Transactional
    @Modifying
    void deleteByFileMd5(String fileMd5);

    @Query("""
            select n from GraphNode n
            where n.type = :type
              and n.normalizedName = :normalizedName
              and n.fileMd5 = :fileMd5
              and n.chunkId = :chunkId
              and n.userId = :userId
              and n.orgTag = :orgTag
              and n.isPublic = :isPublic
            """)
    Optional<GraphNode> findFirstByTypeAndNormalizedNameAndFileMd5AndChunkIdAndUserIdAndOrgTagAndIsPublic(
            @Param("type") String type,
            @Param("normalizedName") String normalizedName,
            @Param("fileMd5") String fileMd5,
            @Param("chunkId") Integer chunkId,
            @Param("userId") String userId,
            @Param("orgTag") String orgTag,
            @Param("isPublic") boolean isPublic
    );

    @Query("""
            select n from GraphNode n
            where n.type in :types
              and n.normalizedName in :normalizedNames
              and (n.userId = :userId or n.isPublic = true or n.orgTag in :orgTags)
            """)
    List<GraphNode> findAccessibleSeeds(
            @Param("types") List<String> types,
            @Param("normalizedNames") List<String> normalizedNames,
            @Param("userId") String userId,
            @Param("orgTags") List<String> orgTags
    );
}
