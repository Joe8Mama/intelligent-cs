package com.ics.llm.service.provider;

import java.util.List;

/**
 * ============================================================================
 * 向量化能力接口 - 模型无关的统一抽象
 * ============================================================================
 * 职责: 定义文本向量化的标准接口，屏蔽不同 Embedding 提供商的差异
 *
 * 实现类:
 * - DashScopeEmbeddingProvider: 阿里云 DashScope Embedding
 * - 后续可扩展: OpenAIEmbeddingProvider, CohereEmbeddingProvider 等
 */
public interface EmbeddingProvider {

    /**
     * 获取提供商唯一标识
     */
    String getProviderName();

    /**
     * 单条文本向量化
     *
     * @param text 输入文本
     * @return 浮点向量
     */
    List<Float> embed(String text);

    /**
     * 批量文本向量化
     *
     * @param texts 文本列表
     * @return 向量列表（与输入顺序一一对应）
     */
    List<List<Float>> embedBatch(List<String> texts);
}
