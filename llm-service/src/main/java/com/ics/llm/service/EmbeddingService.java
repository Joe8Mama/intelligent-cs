package com.ics.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.llm.service.provider.DashScopeEmbeddingProvider;
import com.ics.llm.service.provider.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================================================
 * 向量化服务 (重构: 委托 EmbeddingProvider)
 * ============================================================================
 * 职责: 对外提供文本向量化的统一入口
 *
 * 重构说明:
 * - 原实现直接调用 DashScope Embedding API
 * - 重构后委托给 EmbeddingProvider，支持切换不同提供商
 * - 保持原有方法签名不变，确保上层调用方无需修改
 *
 * 具体的 HTTP 调用逻辑已迁移到 DashScopeEmbeddingProvider
 */
@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    @Autowired
    public EmbeddingService(ObjectMapper objectMapper,
                            @Value("${embedding.api-key}") String apiKey,
                            @Value("${embedding.url}") String url,
                            @Value("${embedding.model}") String model,
                            @Value("${embedding.dimensions}") int dimensions) {
        // 初始化 DashScope Embedding Provider
        this.embeddingProvider = new DashScopeEmbeddingProvider(
                objectMapper, apiKey, url, model, dimensions);
        log.info("[Embedding] 初始化完成, provider={}, model={}, dimensions={}",
                embeddingProvider.getProviderName(), model, dimensions);
    }

    /**
     * 将单条文本转换为浮点向量
     */
    public List<Float> embedText(String text) {
        return embeddingProvider.embed(text);
    }

    /**
     * 批量向量化文本列表
     */
    public List<List<Float>> embedTexts(List<String> texts) {
        return embeddingProvider.embedBatch(texts);
    }
}
