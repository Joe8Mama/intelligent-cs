package com.ics.dispatcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.repository.mapper.DispatchTaskMapper;
import com.ics.dispatcher.repository.mapper.MessageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 调度模块 - 核心调度服务
 * ============================================================================
 * 这是整个调度服务层的核心，承担以下职责:
 *
 * 1. 事件接收与去重
 *    - 接收意图识别失败事件、用户反馈不佳事件
 *    - 基于 event_id 的幂等性校验，防止重复处理
 *
 * 2. 任务编排与生命周期管理
 *    - 创建调度任务，记录完整生命周期
 *    - 状态机: PENDING → CORPUS_GENERATING → CORPUS_STORED → TRAINING → COMPLETED
 *
 * 3. 异步调用协调
 *    - 异步调用大模型语料生成服务
 *    - 接收语料入库完成通知
 *    - 异步触发小模型训练
 *
 * 4. 可靠性保证
 *    - 本地消息表保证消息不丢失
 *    - 定时重试机制处理失败的调用
 *    - 幂等性设计支持安全重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchTaskService {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final AsyncDispatchService asyncDispatchService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${dispatch.retry.max-retries:3}")
    private int maxRetries;

    /** 幂等性缓存前缀 */
    private static final String EVENT_IDEMPOTENT_KEY = "dispatch:event:";

    // ============================== 事件处理入口 ==============================

    /**
     * 处理「意图识别失败」事件
     *
     * 触发后执行:
     * ① 创建调度任务
     * ② 异步调用大模型语料生成
     * ③ (可选)调用大模型直接回答用户
     *
     * @param eventId       事件唯一标识
     * @param sessionId     会话ID
     * @param userId        用户ID
     * @param dialogContext 对话上下文（JSON）
     * @return 分配的任务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleIntentFailedEvent(String eventId,
                                          String sessionId,
                                          String userId,
                                          String dialogContext) {

        log.info("[调度] 处理意图识别失败事件, eventId={}, sessionId={}", eventId, sessionId);

        // 1. 幂等性检查
        String existingTaskId = checkEventIdempotent(eventId);
        if (existingTaskId != null) {
            log.info("[调度] 事件已处理过, eventId={}, existingTaskId={}", eventId, existingTaskId);
            return existingTaskId;
        }

        // 2. 创建调度任务
        String taskId = generateTaskId();
        DispatchTask task = createTask(taskId, eventId, sessionId, userId,
                "INTENT_FAILED", dialogContext);

        // 3. 写入本地消息表（保证消息可靠投递）
        createOutboxMessage(taskId, "llm-service", "GenerateCorpus", dialogContext);

        // 4. 标记事件已处理
        markEventProcessed(eventId, taskId);

        log.info("[调度] 任务已创建, taskId={}, eventId={}", taskId, eventId);

        // 5. 事务提交后，异步调用大模型语料生成
        registerAfterCommit(() -> asyncDispatchService.asyncCallCorpusGeneration(task));

        return taskId;
    }

    /**
     * 处理「用户反馈不佳」事件
     *
     * 处理流程与意图识别失败类似，但触发类型不同
     *
     * @param eventId         事件唯一标识
     * @param sessionId       会话ID
     * @param userId          用户ID
     * @param feedbackContent 反馈内容
     * @param dialogContext   对话上下文（JSON）
     * @return 分配的任务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleUserFeedbackEvent(String eventId,
                                          String sessionId,
                                          String userId,
                                          String feedbackContent,
                                          String dialogContext) {

        log.info("[调度] 处理用户反馈不佳事件, eventId={}, sessionId={}, feedback={}",
                eventId, sessionId, feedbackContent);

        // 1. 幂等性检查
        String existingTaskId = checkEventIdempotent(eventId);
        if (existingTaskId != null) {
            log.info("[调度] 事件已处理过, eventId={}, existingTaskId={}", eventId, existingTaskId);
            return existingTaskId;
        }

        // 2. 创建调度任务（携带反馈内容）
        String taskId = generateTaskId();
        String enrichedContext = dialogContext + "\n[用户反馈]: " + feedbackContent;
        DispatchTask task = createTask(taskId, eventId, sessionId, userId,
                "USER_FEEDBACK", enrichedContext);

        // 3. 写入本地消息表（保证消息可靠投递）
        createOutboxMessage(taskId, "llm-service", "GenerateCorpus", enrichedContext);

        // 4. 标记事件已处理
        markEventProcessed(eventId, taskId);

        log.info("[调度] 反馈任务已创建, taskId={}, eventId={}", taskId, eventId);

        // 5. 事务提交后，异步调用大模型语料生成
        registerAfterCommit(() -> asyncDispatchService.asyncCallCorpusGeneration(task));

        return taskId;
    }

    /**
     * 处理「语料入库完成」通知 (重构: 接收文件路径)
     *
     * 收到大模型服务层的语料入库完成回调后:
     * ① 更新任务状态为 CORPUS_STORED
     * ② 异步调用小模型训练模块（透传 OSS 文件路径）
     *
     * @param taskId    任务ID
     * @param sessionId 会话ID
     * @param filePath  OSS 文件路径
     * @param fileMd5   文件 MD5
     * @param rowCount  语料行数
     * @param success   是否成功
     * @param message   描述信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleCorpusReadyNotification(String taskId,
                                              String sessionId,
                                              String filePath,
                                              String fileMd5,
                                              int rowCount,
                                              boolean success,
                                              String message) {

        log.info("[调度] 收到语料就绪通知, taskId={}, path={}, rows={}, success={}", taskId, filePath, rowCount, success);

        // 1. 查询任务
        DispatchTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            log.warn("[调度] 任务不存在, taskId={}", taskId);
            return;
        }

        if (!success) {
            updateTaskFailed(task, "语料生成失败: " + message);
            return;
        }

        // 2. 更新任务状态: CORPUS_GENERATING → CORPUS_STORED
        task.setStatus(DispatchTask.STATUS_CORPUS_STORED);
        task.setCorpusCount(rowCount);
        task.setCorpusFilePath(filePath);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 3. 写入本地消息表（触发训练的消息，携带文件路径）
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "taskId", taskId,
                    "filePath", filePath != null ? filePath : "",
                    "fileMd5", fileMd5 != null ? fileMd5 : "",
                    "rowCount", rowCount
            ));
            createOutboxMessage(taskId, "small-model-service", "StartTraining", payload);
        } catch (Exception e) {
            log.error("[调度] 序列化训练消息失败, taskId={}", taskId, e);
        }

        log.info("[调度] 语料已入库, 开始触发训练, taskId={}, path={}, rowCount={}", taskId, filePath, rowCount);

        // 4. 事务提交后，异步触发小模型训练（透传文件路径）
        registerAfterCommit(() -> asyncDispatchService.asyncCallSmallModelTraining(taskId, filePath, rowCount));
    }

    // ============================== 任务管理内部方法 ==============================

    /**
     * 创建调度任务记录
     */
    private DispatchTask createTask(String taskId, String eventId, String sessionId,
                                     String userId, String triggerType, String dialogContext) {
        DispatchTask task = new DispatchTask();
        task.setTaskId(taskId);
        task.setEventId(eventId);
        task.setSessionId(sessionId);
        task.setUserId(userId);
        task.setTriggerType(triggerType);
        task.setStatus(DispatchTask.STATUS_PENDING);
        task.setDialogContext(dialogContext);
        task.setCorpusCount(0);
        task.setRetryCount(0);
        task.setMaxRetries(maxRetries);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.insert(task);
        return task;
    }

    /**
     * 创建本地消息表记录
     * 将待发送的 gRPC 调用记录到数据库，保证消息不丢失
     */
    private void createOutboxMessage(String taskId, String targetService,
                                      String methodName, String payload) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setMessageId("msg-" + UUID.randomUUID().toString().substring(0, 12));
        outbox.setTaskId(taskId);
        outbox.setTargetService(targetService);
        outbox.setMethodName(methodName);
        outbox.setPayload(payload);
        outbox.setStatus(MessageOutbox.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setMaxRetries(maxRetries);
        outbox.setCreatedAt(LocalDateTime.now());
        outbox.setUpdatedAt(LocalDateTime.now());

        outboxMapper.insert(outbox);
    }

    /**
     * 更新消息表状态
     */
    private void updateOutboxStatus(String taskId, String targetService, String status) {
        try {
            // 使用 MyBatis-Plus 条件更新
            MessageOutbox update = new MessageOutbox();
            update.setStatus(status);
            update.setUpdatedAt(LocalDateTime.now());

            outboxMapper.update(update, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MessageOutbox>()
                    .eq(MessageOutbox::getTaskId, taskId)
                    .eq(MessageOutbox::getTargetService, targetService));
        } catch (Exception e) {
            log.warn("[调度] 更新消息表状态失败, taskId={}, target={}", taskId, targetService, e);
        }
    }

    /**
     * 生成任务ID
     */
    private String generateTaskId() {
        return "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 查询任务（供 gRPC 查询接口使用）
     */
    public DispatchTask queryTask(String taskId) {
        return taskMapper.selectByTaskId(taskId);
    }

    // ============================== 幂等性 ==============================

    /**
     * 检查事件幂等性
     * 先查 Redis 缓存，再查数据库
     */
    private String checkEventIdempotent(String eventId) {
        // 1. 查 Redis
        String cacheKey = EVENT_IDEMPOTENT_KEY + eventId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.toString();
        }

        // 2. 查数据库
        DispatchTask existing = taskMapper.selectByEventId(eventId);
        if (existing != null) {
            // 回填缓存
            redisTemplate.opsForValue().set(cacheKey, existing.getTaskId(), 24, TimeUnit.HOURS);
            return existing.getTaskId();
        }

        return null;
    }

    /**
     * 标记事件已处理
     */
    private void markEventProcessed(String eventId, String taskId) {
        String cacheKey = EVENT_IDEMPOTENT_KEY + eventId;
        redisTemplate.opsForValue().set(cacheKey, taskId, 24, TimeUnit.HOURS);
    }

    /**
     * 标记任务失败
     */
    private void updateTaskFailed(DispatchTask task, String errorMessage) {
        task.setStatus(DispatchTask.STATUS_FAILED);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.error("[调度] 任务失败, taskId={}, error={}", task.getTaskId(), errorMessage);
    }

    /**
     * 注册事务提交后的回调
     * 确保异步方法在数据库事务成功提交后才执行，避免读到未提交的数据
     */
    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // 无事务上下文时直接执行
            action.run();
        }
    }
}
