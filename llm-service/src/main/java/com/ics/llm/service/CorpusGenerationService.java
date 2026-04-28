package com.ics.llm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.llm.infra.DispatcherNotifyService;
import com.ics.llm.infra.OssService;
import com.ics.llm.model.dto.CorpusItem;
import com.ics.llm.model.dto.FaqSearchResult;
import com.ics.llm.model.entity.SmallModelCorpus;
import com.ics.llm.repository.mapper.SmallModelCorpusMapper;
import com.ics.llm.util.IdGenerator;
import com.ics.llm.util.PromptTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 语料生成模块 - 核心服务 (重构: OSS 存储 + MySQL 元数据)
 * ============================================================================
 * 职责:
 * 1. 接收调度模块传递的对话数据
 * 2. 调用 FAQ 知识管理模块获取 RAG 检索结果
 * 3. 结合对话上下文 + RAG 结果，调用 LLM 生成多条问答语料
 * 4. 将生成的语料转为 JSONL 上传到 OSS，MySQL 仅保存元数据
 * 5. 通过 gRPC 通知调度模块语料已入库（携带文件路径）
 *
 * 核心流程:
 * 对话数据 → 提取关键词 → FAQ RAG检索 → 构建Prompt → LLM生成 → 解析语料 → JSONL上传OSS → 元数据入库 → 通知调度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusGenerationService {

    private final LlmApiService llmApiService;
    private final FaqKnowledgeService faqKnowledgeService;
    private final SmallModelCorpusMapper corpusMapper;
    private final DispatcherNotifyService dispatcherNotifyService;
    private final OssService ossService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;

    @Value("${corpus.generation.default-count}")
    private int defaultCorpusCount;

    @Value("${corpus.generation.max-count}")
    private int maxCorpusCount;

    @Value("${corpus.generation.min-confidence}")
    private double minConfidence;

    /** 幂等性检查 Redis Key 前缀 */
    private static final String IDEMPOTENT_KEY_PREFIX = "corpus:task:";
    /** 幂等性标记过期时间（小时） */
    private static final long IDEMPOTENT_TTL_HOURS = 24;

    /**
     * 【核心方法】异步生成语料
     *
     * 此方法被 gRPC 服务端调用，使用 @Async 在独立线程池中异步执行。
     * 整个流程包括: RAG检索 → LLM生成 → JSONL上传OSS → 元数据入库 → 通知调度
     *
     * @param taskId        任务唯一标识（幂等键）
     * @param sessionId     原始会话ID
     * @param userId        用户ID
     * @param dialogContext 对话上下文文本
     * @param triggerType   触发类型
     * @param corpusCount   期望生成的语料条数
     * @param callbackAddr  调度服务回调地址
     */
    @Async("corpusGenerationExecutor")
    public void generateCorpusAsync(String taskId,
                                    String sessionId,
                                    String userId,
                                    String dialogContext,
                                    String triggerType,
                                    int corpusCount,
                                    String callbackAddr) {

        log.info("[语料生成] 开始异步生成, taskId={}, sessionId={}, triggerType={}, corpusCount={}",
                taskId, sessionId, triggerType, corpusCount);

        try {
            // ===== 第1步: 幂等性校验 =====
            if (!checkAndMarkIdempotent(taskId)) {
                log.warn("[语料生成] 任务已处理过，跳过, taskId={}", taskId);
                return;
            }

            // ===== 第2步: 参数校验和规范化 =====
            int actualCount = Math.min(
                    corpusCount > 0 ? corpusCount : defaultCorpusCount,
                    maxCorpusCount
            );

            // ===== 第3步: RAG 检索 - 从 FAQ 知识库获取相关知识 =====
            String searchQuery = extractSearchQuery(dialogContext);
            List<FaqSearchResult> ragResults = faqKnowledgeService.searchRelevantFaq(searchQuery);
            log.info("[语料生成] RAG检索完成, taskId={}, 匹配FAQ数={}", taskId, ragResults.size());

            // ===== 第4步: 构建 Prompt 并调用 LLM 生成语料 =====
            String userPrompt = promptTemplates.buildCorpusGenerationPrompt(
                    dialogContext, ragResults, actualCount);

            String llmOutput = llmApiService.chatSimple(
                    promptTemplates.getCorpusGenerationSystemPrompt(),
                    userPrompt);

            log.info("[语料生成] LLM生成完成, taskId={}, 输出长度={}", taskId, llmOutput.length());

            // ===== 第5步: 解析 LLM 输出为结构化语料 =====
            List<CorpusItem> corpusList = parseLlmOutput(llmOutput, taskId);
            log.info("[语料生成] 解析完成, taskId={}, 有效语料数={}", taskId, corpusList.size());

            if (corpusList.isEmpty()) {
                log.warn("[语料生成] 无有效语料生成, taskId={}", taskId);
                dispatcherNotifyService.notifyCorpusReady(taskId, sessionId, "", "", 0, true, "无有效语料生成");
                return;
            }

            // ===== 第6步: 语料文件化处理 → 上传OSS → 元数据入库 =====
            CorpusStoreResult result = storeCorpusToFile(corpusList, taskId, sessionId, triggerType);
            log.info("[语料生成] 语料文件入库完成, taskId={}, path={}, rows={}", taskId, result.ossPath, result.rowCount);

            // ===== 第7步: 通知调度模块语料已入库（携带文件路径） =====
            dispatcherNotifyService.notifyCorpusReady(
                    taskId, sessionId, result.ossPath, result.fileMd5, result.rowCount,
                    true, "语料生成并入库完成");
            log.info("[语料生成] 已通知调度模块, taskId={}", taskId);

        } catch (Exception e) {
            log.error("[语料生成] 生成失败, taskId={}, error={}", taskId, e.getMessage(), e);
            clearIdempotentMark(taskId);
            dispatcherNotifyService.notifyCorpusReady(taskId, sessionId, "", "", 0, false, "语料生成失败: " + e.getMessage());
        }
    }

    /**
     * 语料文件入库结果
     */
    private static class CorpusStoreResult {
        String ossPath;
        String fileMd5;
        int rowCount;
    }

    /**
     * 将语料列表转为 JSONL 字节流
     * 每行一条对话记录，格式: {"intent": "...", "messages": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
     */
    private byte[] buildJsonlBytes(List<CorpusItem> corpusList) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (CorpusItem item : corpusList) {
            Map<String, Object> record = new java.util.HashMap<>();
            record.put("intent", item.getIntent() != null ? item.getIntent() : "");
            record.put("messages", List.of(
                    Map.of("role", "user", "content", item.getQuestion()),
                    Map.of("role", "assistant", "content", item.getAnswer())
            ));
            sb.append(objectMapper.writeValueAsString(record)).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 计算字节数组的 MD5
     */
    private String computeMd5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        return HexFormat.of().formatHex(digest);
    }

    /**
     * 语料入库逻辑 (重构: JSONL → OSS → 元数据)
     *
     * 流程: 构造JSONL → 计算MD5 → 上传OSS → 保存MySQL元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public CorpusStoreResult storeCorpusToFile(List<CorpusItem> corpusList,
                                                String taskId,
                                                String sessionId,
                                                String triggerType) {
        try {
            // 1. 构造 JSONL 内容
            byte[] jsonlBytes = buildJsonlBytes(corpusList);
            String fileName = "batch_" + taskId + "_" + IdGenerator.generateId("f").substring(0, 8) + ".jsonl";

            // 2. 计算 MD5
            String md5 = computeMd5(jsonlBytes);

            // 3. 上传到 OSS
            String ossKey = ossService.uploadCorpusJsonl(fileName, jsonlBytes);

            // 4. 计算平均置信度
            double avgConf = corpusList.stream()
                    .mapToDouble(item -> item.getConfidence() != null ? item.getConfidence().doubleValue() : 0.8)
                    .average()
                    .orElse(0.8);

            // 5. 写入 MySQL 元数据（轻量级，不再存具体问答内容）
            SmallModelCorpus meta = new SmallModelCorpus();
            meta.setTaskId(taskId);
            meta.setCorpusId(taskId);
            meta.setFilePath(ossKey);
            meta.setFileMd5(md5);
            meta.setRowCount(corpusList.size());
            meta.setAvgConfidence(BigDecimal.valueOf(avgConf));
            meta.setTriggerType(triggerType);
            meta.setStatus(0); // 0=待训练

            corpusMapper.insert(meta);

            log.info("[语料入库] 文件生成并落库, taskId={}, path={}, rows={}, md5={}",
                    taskId, ossKey, corpusList.size(), md5);

            CorpusStoreResult result = new CorpusStoreResult();
            result.ossPath = ossKey;
            result.fileMd5 = md5;
            result.rowCount = corpusList.size();
            return result;

        } catch (Exception e) {
            log.error("[语料入库] 语料文件处理失败, taskId={}", taskId, e);
            throw new RuntimeException("语料文件处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 幂等性校验：检查任务是否已处理，如未处理则标记
     */
    private boolean checkAndMarkIdempotent(String taskId) {
        String key = IDEMPOTENT_KEY_PREFIX + taskId;
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, "processing", IDEMPOTENT_TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 清除幂等标记（任务失败时调用，允许重试）
     */
    private void clearIdempotentMark(String taskId) {
        String key = IDEMPOTENT_KEY_PREFIX + taskId;
        redisTemplate.delete(key);
    }

    /**
     * 从对话上下文中提取检索关键词
     */
    private String extractSearchQuery(String dialogContext) {
        if (dialogContext == null || dialogContext.isEmpty()) {
            return "";
        }
        String trimmed = dialogContext.trim();
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(trimmed.length() - 200);
        }
        return trimmed;
    }

    /**
     * 解析 LLM 输出的 JSON 语料数组
     */
    private List<CorpusItem> parseLlmOutput(String llmOutput, String taskId) {
        try {
            String cleaned = llmOutput.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            List<Map<String, Object>> rawList = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            List<CorpusItem> result = new ArrayList<>();
            for (Map<String, Object> item : rawList) {
                String question = (String) item.get("question");
                String answer = (String) item.get("answer");
                String category = (String) item.getOrDefault("category", "通用");
                String intent = (String) item.getOrDefault("intent", "");
                double confidence = item.containsKey("confidence")
                        ? ((Number) item.get("confidence")).doubleValue() : 0.8;

                if (question == null || question.isEmpty() || answer == null || answer.isEmpty()) {
                    log.warn("[语料生成] 跳过空语料, taskId={}", taskId);
                    continue;
                }
                if (confidence < minConfidence) {
                    log.warn("[语料生成] 跳过低置信度语料, taskId={}, confidence={}", taskId, confidence);
                    continue;
                }

                result.add(CorpusItem.builder()
                        .corpusId(IdGenerator.corpusId())
                        .question(question)
                        .answer(answer)
                        .category(category)
                        .intent(intent)
                        .confidence(BigDecimal.valueOf(confidence))
                        .build());
            }

            return result;

        } catch (Exception e) {
            log.error("[语料生成] 解析LLM输出失败, taskId={}, output={}", taskId, llmOutput, e);
            throw new RuntimeException("解析LLM生成的语料失败: " + e.getMessage(), e);
        }
    }
}
