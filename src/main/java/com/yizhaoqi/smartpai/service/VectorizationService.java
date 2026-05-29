package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired(required = false)
    private GraphExtractionService graphExtractionService;

    @Autowired
    private AiProperties aiProperties;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorize(fileMd5, userId, orgTag, isPublic, null);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String ingestionTraceId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}, ingestionTraceId: {}",
                       fileMd5, userId, orgTag, isPublic, ingestionTraceId);
                       
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return;
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            List<float[]> vectors = embeddingClient.embed(texts);

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            vectors.get(i),
                            "deepseek-embed", // 更新为 DeepSeek 的模型版本
                            userId,
                            orgTag,
                            isPublic,
                            ingestionTraceId
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            extractGraphIfEnabled(fileMd5, chunks, userId, orgTag, isPublic, ingestionTraceId);

            logger.info("向量化完成，fileMd5: {}", fileMd5);
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        }
    }

    private void extractGraphIfEnabled(String fileMd5,
                                       List<TextChunk> chunks,
                                       String userId,
                                       String orgTag,
                                       boolean isPublic,
                                       String ingestionTraceId) {
        if (graphExtractionService == null
                || aiProperties.getAgentic().getGraph() == null
                || !aiProperties.getAgentic().getGraph().isExtractionEnabled()) {
            return;
        }
        try {
            graphExtractionService.extract(fileMd5, chunks, userId, orgTag, isPublic, ingestionTraceId);
        } catch (Exception e) {
            logger.warn("graph_extraction_failed ingestionTraceId={} fileMd5={} reason={}",
                    ingestionTraceId, fileMd5, e.getMessage(), e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent()
                ))
                .toList();
    }
}
