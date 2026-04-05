package com.ics.llm.scheduler;

import com.ics.llm.model.entity.FaqKnowledge;
import com.ics.llm.service.FaqKnowledgeService;
import com.ics.llm.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ============================================================================
 * FAQ 向量同步调度器 - 定时将未同步的 FAQ 数据同步到 Qdrant 向量库
 * ============================================================================
 *
 * 同步策略:
 * - 定时扫描 faq_knowledge 表中 status=1 且 vector_synced=0/NULL 的记录
 * - 逐条调用 Embedding API 生成向量 → 写入 Qdrant → 更新 vector_synced=1
 * - 支持配置每次同步的最大条数，避免大批量调用导致 API 限流
 *
 * 触发时机:
 * 1. 定时任务: 每 N 分钟自动执行一次
 * 2. 启动延迟: 服务启动后延迟一段时间首次执行，等待基础设施就绪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaqVectorSyncScheduler {

    private final FaqKnowledgeService faqKnowledgeService;
    private final VectorStoreService vectorStoreService;

    /** 每次定时同步的最大条数，避免大批量调用 Embedding API 导致限流 */
    @Value("${faq.sync.batch-size}")
    private int batchSize;

    /**
     * 定时同步未同步的 FAQ 到向量库
     *
     * 执行逻辑:
     * 1. 检查 Qdrant 是否可用，不可用则跳过
     * 2. 查询 status=1 且 vector_synced=0/NULL 的 FAQ
     * 3. 限制本次处理的条数 (batchSize)
     * 4. 逐条同步: Embedding → Qdrant upsert → 更新 vector_synced=1
     */
    @Scheduled(
            initialDelayString = "${faq.sync.initial-delay}",
            fixedDelayString = "${faq.sync.interval}"
    )
    public void syncUnsyncedFaqs() {
        if (!vectorStoreService.isInitialized()) {
            log.debug("[FAQ向量同步] Qdrant 未就绪，跳过本次同步");
            return;
        }

        List<FaqKnowledge> unsyncedFaqs = faqKnowledgeService.listUnsyncedFaqs();
        if (unsyncedFaqs.isEmpty()) {
            log.debug("[FAQ向量同步] 无待同步的 FAQ 数据");
            return;
        }

        // 限制本次处理条数
        List<FaqKnowledge> batch = unsyncedFaqs.size() > batchSize
                ? unsyncedFaqs.subList(0, batchSize) : unsyncedFaqs;

        log.info("[FAQ向量同步] 开始同步, 待同步总数={}, 本次处理={}", unsyncedFaqs.size(), batch.size());

        int success = 0;
        int failed = 0;

        for (FaqKnowledge faq : batch) {
            try {
                if (faqKnowledgeService.syncFaqToVector(faq)) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("[FAQ向量同步] FAQ id={} 同步异常: {}", faq.getId(), e.getMessage());
            }
        }

        log.info("[FAQ向量同步] 本次同步完成, 处理={}, 成功={}, 失败={}, 剩余待同步={}",
                batch.size(), success, failed, Math.max(0, unsyncedFaqs.size() - batch.size()));
    }
}
