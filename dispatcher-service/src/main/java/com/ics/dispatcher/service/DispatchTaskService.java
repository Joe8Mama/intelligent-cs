package com.ics.dispatcher.service;

import com.ics.dispatcher.machine.TaskStateMachine;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.model.entity.TaskStatus;
import com.ics.dispatcher.orchestration.OrchestratorFactory;
import com.ics.dispatcher.orchestration.TaskOrchestrator;
import com.ics.dispatcher.orchestration.impl.CorpusGenOrchestrator;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 调度模块 - 核心调度服务 (重构: 委托编排器执行业务逻辑)
 * ============================================================================
 * 职责:
 * 1. 事件接收与幂等性校验
 * 2. 任务创建（PENDING 状态）
 * 3. 编排委托 — 将业务逻辑路由给对应的 Orchestrator
 * 4. 回调处理 — 接收上游服务的异步通知
 *
 * 设计要点:
 * - 此服务仅负责「接单」和「创建任务」，不包含具体地编排逻辑
 * - 具体的异步编排由 CorpusGenOrchestrator 等实现类承担
 * - 后续新增事件类型只需添加新的 Orchestrator，此服务零修改
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchTaskService {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final OrchestratorFactory orchestratorFactory;
    private final CorpusGenOrchestrator corpusGenOrchestrator;
    private final TaskStateMachine stateMachine;

    @Value("${dispatch.retry.max-retries}")
    private int maxRetries;

    /** 幂等性缓存前缀 */
    private static final String EVENT_IDEMPOTENT_KEY = "dispatch:event:";

    // ============================== 事件处理入口 ==============================

    /**
     * 处理「意图识别失败」事件
     *
     * 流程:
     * ① 幂等性校验
     * ② 创建调度任务 (PENDING)
     * ③ 写入本地消息表
     * ④ 事务提交后委托 Orchestrator 执行编排
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

        // 3. 写入本地消息表
        createOutboxMessage(taskId, "llm-service", "GenerateCorpus", dialogContext);

        // 4. 标记事件已处理
        markEventProcessed(eventId, taskId);

        log.info("[调度] 任务已创建, taskId={}, eventId={}", taskId, eventId);

        // 5. 事务提交后，委托编排器异步执行
        registerAfterCommit(() -> {
            TaskOrchestrator orchestrator = orchestratorFactory.getOrchestrator("CORPUS_GEN");
            orchestrator.orchestrate(task);
        });

        return taskId;
    }

    /**
     * 处理「用户反馈不佳」事件
     *
     * 与意图识别失败事件走相同地编排流程 (CORPUS_GEN)
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleUserFeedbackEvent(String eventId,
                                          String sessionId,
                                          String userId,
                                          String feedbackContent,
                                          String dialogContext) {

        log.info("[调度] 处理用户反馈不佳事件, eventId={}, sessionId={}", eventId, sessionId);

        String existingTaskId = checkEventIdempotent(eventId);
        if (existingTaskId != null) {
            return existingTaskId;
        }

        String taskId = generateTaskId();
        String enrichedContext = dialogContext + "\n[用户反馈]: " + feedbackContent;
        DispatchTask task = createTask(taskId, eventId, sessionId, userId,
                "USER_FEEDBACK", enrichedContext);

        createOutboxMessage(taskId, "llm-service", "GenerateCorpus", enrichedContext);
        markEventProcessed(eventId, taskId);

        log.info("[调度] 反馈任务已创建, taskId={}", taskId);

        registerAfterCommit(() -> {
            TaskOrchestrator orchestrator = orchestratorFactory.getOrchestrator("CORPUS_GEN");
            orchestrator.orchestrate(task);
        });

        return taskId;
    }

    /**
     * 处理「语料入库完成」通知
     *
     * 委托给 CorpusGenOrchestrator 处理后续流程（触发训练）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleCorpusReadyNotification(String taskId,
                                              String sessionId,
                                              String filePath,
                                              String fileMd5,
                                              int rowCount,
                                              boolean success,
                                              String message) {

        log.info("[调度] 收到语料就绪通知, taskId={}, path={}, rows={}, success={}",
                taskId, filePath, rowCount, success);

        DispatchTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            log.warn("[调度] 任务不存在, taskId={}", taskId);
            return;
        }

        if (!success) {
            stateMachine.fail(task, "语料生成失败: " + message);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            return;
        }

        // 委托编排器处理后续流程
        registerAfterCommit(() -> corpusGenOrchestrator.handleCorpusReady(taskId, filePath, rowCount));
    }

    // ============================== 任务查询 ==============================

    public DispatchTask queryTask(String taskId) {
        return taskMapper.selectByTaskId(taskId);
    }

    // ============================== 内部方法 ==============================

    private DispatchTask createTask(String taskId, String eventId, String sessionId,
                                    String userId, String triggerType, String dialogContext) {
        DispatchTask task = new DispatchTask();
        task.setTaskId(taskId);
        task.setEventId(eventId);
        task.setSessionId(sessionId);
        task.setUserId(userId);
        task.setTriggerType(triggerType);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setDialogContext(dialogContext);
        task.setCorpusCount(0);
        task.setRetryCount(0);
        task.setMaxRetries(maxRetries);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private void createOutboxMessage(String taskId, String targetService, String methodName, String payload) {
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

    private String generateTaskId() {
        return "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ============================== 幂等性 ==============================

    private String checkEventIdempotent(String eventId) {
        String cacheKey = EVENT_IDEMPOTENT_KEY + eventId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.toString();
        }

        DispatchTask existing = taskMapper.selectByEventId(eventId);
        if (existing != null) {
            redisTemplate.opsForValue().set(cacheKey, existing.getTaskId(), 24, TimeUnit.HOURS);
            return existing.getTaskId();
        }

        return null;
    }

    private void markEventProcessed(String eventId, String taskId) {
        String cacheKey = EVENT_IDEMPOTENT_KEY + eventId;
        redisTemplate.opsForValue().set(cacheKey, taskId, 24, TimeUnit.HOURS);
    }

    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
