package com.ics.llm.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量数据库服务 - 封装 Qdrant 客户端操作
 *
 * 提供 FAQ 知识向量的存储、检索、删除能力
 * 启动时自动连接 Qdrant 并确保集合存在，连接失败时优雅降级
 */
@Slf4j
@Service
public class VectorStoreService {

    private QdrantClient client;
    private volatile boolean initialized = false;

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int port;

    @Value("${qdrant.collection-name:faq_knowledge_vectors}")
    private String collectionName;

    @Value("${embedding.dimensions:1024}")
    private int vectorDimension;

    @PostConstruct
    public void init() {
        try {
            this.client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(host, port, false).build()
            );
            ensureCollectionExists();
            initialized = true;
            log.info("[VectorStore] Qdrant 连接成功: {}:{}, 集合: {}", host, port, collectionName);
        } catch (Exception e) {
            log.error("[VectorStore] Qdrant 连接失败，向量检索不可用: {}", e.getMessage());
            initialized = false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
                log.info("[VectorStore] Qdrant 客户端已关闭");
            } catch (Exception e) {
                log.warn("[VectorStore] 关闭 Qdrant 客户端异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 确保向量集合存在，不存在则自动创建
     */
    private void ensureCollectionExists() {
        try {
            boolean exists = client.collectionExistsAsync(collectionName).get();
            if (!exists) {
                CreateCollection request = CreateCollection.newBuilder()
                        .setCollectionName(collectionName)
                        .setVectorsConfig(VectorsConfig.newBuilder()
                                .setParams(VectorParams.newBuilder()
                                        .setSize(vectorDimension)
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        .build();
                client.createCollectionAsync(request).get();
                log.info("[VectorStore] 创建集合成功: {}, 维度: {}, 距离: Cosine",
                        collectionName, vectorDimension);
            }
        } catch (Exception e) {
            log.error("[VectorStore] 集合检查/创建失败", e);
            throw new RuntimeException("Qdrant 集合初始化失败", e);
        }
    }

    /**
     * 写入/更新 FAQ 向量
     *
     * @param id       MySQL 中的 FAQ ID (用作 Qdrant point ID)
     * @param vector   向量数据
     * @param metadata 附带元数据 (如 category)
     */
    public void upsertFaqVector(long id, List<Float> vector, Map<String, String> metadata) {
        if (!initialized) {
            log.warn("[VectorStore] 客户端未初始化，跳过向量写入, id={}", id);
            return;
        }
        if (vector == null || vector.isEmpty()) {
            log.warn("[VectorStore] 向量为空，跳过写入, id={}", id);
            return;
        }

        try {
            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            if (metadata != null) {
                metadata.forEach((k, v) -> payload.put(k,
                        JsonWithInt.Value.newBuilder().setStringValue(v != null ? v : "").build()));
            }

            PointStruct point = PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setNum(id).build())
                    .setVectors(Vectors.newBuilder()
                            .setVector(Vector.newBuilder().addAllData(vector)))
                    .putAllPayload(payload)
                    .build();

            client.upsertAsync(collectionName, List.of(point)).get();
            log.debug("[VectorStore] 写入向量成功, id={}, 维度={}", id, vector.size());
        } catch (Exception e) {
            log.error("[VectorStore] 写入向量失败, id={}", id, e);
        }
    }

    /**
     * 删除 FAQ 向量
     *
     * @param id MySQL FAQ ID
     */
    public void deleteFaqVector(long id) {
        if (!initialized) return;
        try {
            client.deleteAsync(collectionName,
                    List.of(PointId.newBuilder().setNum(id).build())).get();
            log.debug("[VectorStore] 删除向量成功, id={}", id);
        } catch (Exception e) {
            log.error("[VectorStore] 删除向量失败, id={}", id, e);
        }
    }

    /**
     * 检索相似向量 (语义搜索)
     *
     * @param vector 用户问题向量
     * @param limit  返回数量 (Top K)
     * @return 按相似度降序排列的匹配结果
     */
    public List<ScoredPoint> searchSimilar(List<Float> vector, int limit) {
        if (!initialized || vector == null || vector.isEmpty()) {
            return List.of();
        }

        try {
            SearchPoints request = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(vector)
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            return client.searchAsync(request).get();
        } catch (Exception e) {
            log.error("[VectorStore] 向量检索失败", e);
            return List.of();
        }
    }

    /**
     * Qdrant 客户端是否已成功初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}
