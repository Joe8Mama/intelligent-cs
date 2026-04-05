package com.ics.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ics.llm.model.dto.FaqSearchResult;
import com.ics.llm.model.entity.FaqKnowledge;
import com.ics.llm.repository.mapper.FaqKnowledgeMapper;
import io.qdrant.client.grpc.Points.ScoredPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * FAQ 知识管理模块 - 核心服务 (混合检索架构)
 * ============================================================================
 * 职责: 对接 FAQ 知识库，实现 RAG（检索增强生成）能力
 *
 * RAG 检索策略 (混合架构):
 * 1. 主路径: 向量语义检索 (Qdrant) - 通过 Embedding 将查询转为向量，在向量库中搜索
 * 2. 降级路径: MySQL 全文检索 (FULLTEXT INDEX) - 向量库不可用时的兜底方案
 * 3. 最终降级: MySQL 模糊检索 (LIKE) - 全文检索无结果时的兜底
 *
 * 写入策略 (双写):
 * 1. FAQ 新增/修改时同步写入 MySQL + Qdrant
 * 2. 支持批量同步已有 FAQ 到向量库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqKnowledgeService {

    private final FaqKnowledgeMapper faqKnowledgeMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    @Value("${faq.search.max-results}")
    private int maxResults;

    @Value("${faq.search.min-score}")
    private double minScore;

    @Value("${faq.search.vector-enabled}")
    private boolean vectorEnabled;

    /** Redis 缓存键前缀 */
    private static final String CACHE_KEY_PREFIX = "faq:search:";
    /** 缓存过期时间（分钟） */
    private static final long CACHE_TTL_MINUTES = 30;

    /**
     * RAG 检索 - 根据查询文本检索相关 FAQ 知识
     * 这是 FAQ 知识管理模块的核心方法，为语料生成和直接回答提供知识库上下文
     *
     * 检索策略:
     * 1. 先查 Redis 缓存
     * 2. 缓存未命中 -> 向量语义检索 (如果启用且 Qdrant 可用)
     * 3. 向量检索失败/无结果 -> 降级为 MySQL 全文检索
     * 4. 全文检索无结果 -> 降级为 MySQL 模糊检索
     *
     * @param query 查询文本（通常为用户输入或对话摘要）
     * @return 匹配的 FAQ 结果列表（按相关性降序排列）
     */
    public List<FaqSearchResult> searchRelevantFaq(String query) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("[FAQ检索] 查询文本为空");
            return Collections.emptyList();
        }

        String trimmedQuery = query.trim();
        log.info("[FAQ检索] 开始检索, query='{}', maxResults={}, vectorEnabled={}",
                trimmedQuery, maxResults, vectorEnabled);

        // 1. 先查 Redis 缓存
        String cacheKey = CACHE_KEY_PREFIX + trimmedQuery.hashCode();
        try {
            @SuppressWarnings("unchecked")
            List<FaqSearchResult> cached = (List<FaqSearchResult>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("[FAQ检索] 命中缓存, query='{}', 结果数={}", trimmedQuery, cached.size());
                return cached;
            }
        } catch (Exception e) {
            log.warn("[FAQ检索] 缓存读取异常: {}", e.getMessage());
        }

        // 2. 执行检索 (向量优先，MySQL 降级)
        List<FaqSearchResult> results;
        try {
            if (vectorEnabled && vectorStoreService.isInitialized()) {
                results = vectorSearch(trimmedQuery);
            } else {
                log.info("[FAQ检索] 向量检索未启用或 Qdrant 不可用，使用 MySQL 检索");
                results = mysqlFallbackSearch(trimmedQuery);
            }
        } catch (Exception e) {
            log.warn("[FAQ检索] 检索异常，降级为 MySQL: {}", e.getMessage());
            results = mysqlFallbackSearch(trimmedQuery);
        }

        log.info("[FAQ检索] 检索完成, query='{}', 结果数={}", trimmedQuery, results.size());

        // 3. 写入缓存
        try {
            if (!results.isEmpty()) {
                redisTemplate.opsForValue().set(cacheKey, results, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.warn("[FAQ检索] 缓存写入异常: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 向量语义检索 (主检索路径)
     *
     * 流程:
     * 1. 调用 Embedding API 将查询文本转为向量
     * 2. 在 Qdrant 中搜索最相似的 Top-K FAQ
     * 3. 从 Qdrant 结果中提取 MySQL ID
     * 4. 回 MySQL 批量查询完整的 Q&A 文本
     * 5. 保留 Qdrant 的余弦相似度分数
     */
    private List<FaqSearchResult> vectorSearch(String query) {
        log.info("[FAQ检索] 执行向量语义检索, query='{}'", query);

        // 1. 将查询文本转为向量
        List<Float> queryVector = embeddingService.embedText(query);

        // 2. 在 Qdrant 中搜索最相似的 FAQ
        List<ScoredPoint> scoredPoints = vectorStoreService.searchSimilar(queryVector, maxResults);

        if (scoredPoints.isEmpty()) {
            log.info("[FAQ检索] Qdrant 无结果，降级为 MySQL 检索");
            return mysqlFallbackSearch(query);
        }

        // 3. 提取 MySQL ID (按 Qdrant 返回顺序)
        List<Long> faqIds = scoredPoints.stream()
                .map(p -> p.getId().getNum())
                .collect(Collectors.toList());

        // 4. 回 MySQL 批量查询详细 Q&A 文本
        List<FaqKnowledge> faqs = faqKnowledgeMapper.selectBatchIds(faqIds);
        Map<Long, FaqKnowledge> faqMap = faqs.stream()
                .collect(Collectors.toMap(FaqKnowledge::getId, f -> f));

        // 5. 保留 Qdrant 的相似度分数
        Map<Long, Float> scoreMap = scoredPoints.stream()
                .collect(Collectors.toMap(
                        p -> p.getId().getNum(),
                        ScoredPoint::getScore
                ));

        // 6. 按 Qdrant 返回顺序组装结果
        return faqIds.stream()
                .filter(faqMap::containsKey)
                .map(id -> {
                    FaqKnowledge faq = faqMap.get(id);
                    return FaqSearchResult.builder()
                            .faqId(faq.getId())
                            .question(faq.getQuestion())
                            .answer(faq.getAnswer())
                            .category(faq.getCategory())
                            .score((double) scoreMap.getOrDefault(id, 0.0f))
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * MySQL 降级检索 (兜底方案)
     * 当向量库不可用或无结果时使用
     */
    private List<FaqSearchResult> mysqlFallbackSearch(String query) {
        log.info("[FAQ检索] 执行 MySQL 全文检索, query='{}'", query);

        try {
            List<FaqKnowledge> faqList = faqKnowledgeMapper.fullTextSearch(query, maxResults);

            if (faqList.isEmpty()) {
                log.info("[FAQ检索] 全文检索无结果，降级为模糊检索");
                faqList = faqKnowledgeMapper.fuzzySearch(query, maxResults);
            }

            return faqList.stream()
                    .map(faq -> FaqSearchResult.builder()
                            .faqId(faq.getId())
                            .question(faq.getQuestion())
                            .answer(faq.getAnswer())
                            .category(faq.getCategory())
                            .score(1.0)
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[FAQ检索] MySQL 检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 同步单条 FAQ 到向量库
     * 拼接 Question + Answer 作为索引文本，成功后标记 vectorSynced=1
     *
     * @param faq FAQ 实体
     * @return true 同步成功
     */
    public boolean syncFaqToVector(FaqKnowledge faq) {
        try {
            String textToEmbed = faq.getQuestion() + " " + faq.getAnswer();
            List<Float> vector = embeddingService.embedText(textToEmbed);

            vectorStoreService.upsertFaqVector(
                    faq.getId(),
                    vector,
                    Map.of("category", faq.getCategory() != null ? faq.getCategory() : "")
            );

            // 同步成功后更新标记
            FaqKnowledge update = new FaqKnowledge();
            update.setId(faq.getId());
            update.setVectorSynced(1);
            faqKnowledgeMapper.updateById(update);

            log.debug("[RAG同步] FAQ 同步成功, id={}", faq.getId());
            return true;
        } catch (Exception e) {
            log.error("[RAG同步] FAQ 向量同步失败, id={}", faq.getId(), e);
            return false;
        }
    }

    /**
     * 查询所有启用但未同步到向量库的 FAQ
     *
     * @return 未同步的 FAQ 列表
     */
    public List<FaqKnowledge> listUnsyncedFaqs() {
        return faqKnowledgeMapper.selectList(
                new LambdaQueryWrapper<FaqKnowledge>()
                        .eq(FaqKnowledge::getStatus, 1)
                        .and(w -> w.isNull(FaqKnowledge::getVectorSynced)
                                .or().eq(FaqKnowledge::getVectorSynced, 0))
        );
    }

    /**
     * 批量同步所有启用状态的 FAQ 到向量库
     * 适用于首次部署或数据迁移场景
     *
     * @return 成功同步的数量
     */
    public int syncAllFaqToVector() {
        if (!vectorStoreService.isInitialized()) {
            log.warn("[RAG同步] Qdrant 未初始化，跳过批量同步");
            return 0;
        }

        List<FaqKnowledge> allFaqs = faqKnowledgeMapper.selectList(
                new LambdaQueryWrapper<FaqKnowledge>().eq(FaqKnowledge::getStatus, 1)
        );

        int success = 0;
        for (FaqKnowledge faq : allFaqs) {
            try {
                if (syncFaqToVector(faq)) {
                    success++;
                }
            } catch (Exception e) {
                log.error("[RAG同步] FAQ id={} 同步失败: {}", faq.getId(), e.getMessage());
            }
        }

        log.info("[RAG同步] 批量同步完成, 总数={}, 成功={}, 失败={}",
                allFaqs.size(), success, allFaqs.size() - success);
        return success;
    }

    /**
     * 将 FAQ 检索结果格式化为 RAG 上下文文本
     * 用于拼接到 LLM Prompt 中
     *
     * @param results FAQ 检索结果
     * @return 格式化后的知识库上下文
     */
    public String formatAsRagContext(List<FaqSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "未找到相关知识库内容。";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            FaqSearchResult r = results.get(i);
            sb.append(String.format("【知识%d】[分类: %s]\n", i + 1, r.getCategory()));
            sb.append(String.format("  问题: %s\n", r.getQuestion()));
            sb.append(String.format("  答案: %s\n\n", r.getAnswer()));
        }
        return sb.toString();
    }
}
