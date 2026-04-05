package com.ics.dispatcher.orchestration.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.dispatcher.grpc.client.LlmGrpcClient;
import com.ics.dispatcher.grpc.client.SmallModelGrpcClient;
import com.ics.dispatcher.machine.TaskStateMachine;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.model.dto.TaskContext;
import com.ics.dispatcher.model.entity.TaskStatus;
import com.ics.dispatcher.orchestration.TaskOrchestrator;
import com.ics.dispatcher.repository.mapper.DispatchTaskMapper;
import com.ics.dispatcher.repository.mapper.MessageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * ============================================================================
 * 语料生成流程编排器
 * ============================================================================
 * 职责: 编排「语料生成 → 语料入库 → 小模型训练」的完整异步流程
 *
 * 状态流转:
 * PENDING → CORPUS_GENERATING → CORPUS_STORED → TRAINING → COMPLETED
 *
 * 设计要点:
 * - 此编排器仅处理 INTENT_FAILED 和 USER_FEEDBACK 两种触发类型
 * - 编排逻辑从 DispatchTaskService 和 AsyncDispatchService 中抽取
 * - 后续扩展新的触发类型时，只需新增编排器实现，无需修改已有代码
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorpusGenOrchestrator implements TaskOrchestrator {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final LlmGrpcClient llmGrpcClient;
    private final SmallModelGrpcClient smallModelGrpcClient;
    private final TaskStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    @Value("${dispatch.retry.max-retries}")
    private int maxRetries;

    @Value("${dispatch.retry.initial-delay-seconds}")
    private int initialDelaySeconds;

    @Value("${dispatch.retry.backoff-multiplier}")
    private int backoffMultiplier;

    @Override
    public String supportsType() {
        return "CORPUS_GEN";
    }

    /**
     * 执行语料生成编排
     * 在事务提交后异步执行:
     * ① 更新状态为 CORPUS_GENERATING
     * ② gRPC 调用大模型语料生成服务
     * ③ (语料生成完成后由 handleCorpusReady 继续)
     */
    @Override
    public void orchestrate(DispatchTask task) {
        log.info("[语料编排] 开始编排语料生成流程, taskId={}, sessionId={}",
                task.getTaskId(), task.getSessionId());

        try {
            // 重新从数据库加载最新数据（避免事务缓存问题）
            DispatchTask freshTask = taskMapper.selectByTaskId(task.getTaskId());
            if (freshTask == null) {
                log.warn("[语料编排] 任务不存在, taskId={}", task.getTaskId());
                return;
            }

            if (stateMachine.isTerminal(freshTask)) {
                log.info("[语料编排] 任务已处于终态, taskId={}, status={}",
                        task.getTaskId(), freshTask.getStatus());
                return;
            }

            // ① 状态转换: PENDING → CORPUS_GENERATING
            boolean transitioned = stateMachine.transition(
                    freshTask, TaskStatus.PENDING, TaskStatus.CORPUS_GENERATING);
            if (!transitioned) {
                log.warn("[语料编排] 状态转换失败, taskId={}, currentStatus={}",
                        task.getTaskId(), freshTask.getStatus());
                return;
            }
            freshTask.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(freshTask);

            // ② gRPC 调用大模型语料生成服务
            llmGrpcClient.callCorpusGeneration(
                    freshTask.getTaskId(),
                    freshTask.getSessionId(),
                    freshTask.getUserId(),
                    freshTask.getDialogContext(),
                    freshTask.getTriggerType()
            );

            // ③ 更新消息表状态为已发送
            updateOutboxStatus(freshTask.getTaskId(), "llm-service", MessageOutbox.STATUS_SENT);

            log.info("[语料编排] 语料生成请求已发送, taskId={}", freshTask.getTaskId());

        } catch (Exception e) {
            log.error("[语料编排] 语料生成编排失败, taskId={}, error={}",
                    task.getTaskId(), e.getMessage(), e);
            scheduleRetry(task.getTaskId(), e.getMessage());
        }
    }

    /**
     * 处理语料就绪回调
     * 当大模型服务层完成语料生成后回调此方法:
     * ① 更新状态为 CORPUS_STORED
     * ② 触发小模型训练
     */
    public void handleCorpusReady(String taskId, String filePath, int rowCount) {
        log.info("[语料编排] 处理语料就绪, taskId={}, path={}, rows={}", taskId, filePath, rowCount);

        try {
            DispatchTask task = taskMapper.selectByTaskId(taskId);
            if (task == null) {
                log.warn("[语料编排] 任务不存在, taskId={}", taskId);
                return;
            }

            if (stateMachine.isTerminal(task)) {
                log.info("[语料编排] 任务已处于终态, taskId={}, status={}", taskId, task.getStatus());
                return;
            }

            // ① 状态转换: CORPUS_GENERATING → CORPUS_STORED
            boolean transitioned = stateMachine.transition(
                    task, TaskStatus.CORPUS_GENERATING, TaskStatus.CORPUS_STORED);
            if (!transitioned) {
                log.warn("[语料编排] 状态转换失败, taskId={}, currentStatus={}",
                        taskId, task.getStatus());
                return;
            }

            task.setCorpusCount(rowCount);
            task.setCorpusFilePath(filePath);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // ② 写入消息表
            try {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "taskId", taskId,
                        "filePath", filePath != null ? filePath : "",
                        "rowCount", rowCount
                ));
                createOutboxMessage(taskId, "small-model-service", "StartTraining", payload);
            } catch (Exception e) {
                log.error("[语料编排] 序列化训练消息失败, taskId={}", taskId, e);
            }

            log.info("[语料编排] 语料已入库, 开始触发训练, taskId={}, path={}", taskId, filePath);

            // ③ 触发小模型训练编排
            triggerTraining(taskId, filePath, rowCount);

        } catch (Exception e) {
            log.error("[语料编排] 处理语料就绪失败, taskId={}, error={}", taskId, e.getMessage(), e);
            scheduleRetry(taskId, e.getMessage());
        }
    }

    /**
     * 触发小模型训练
     * ① 更新状态为 TRAINING
     * ② gRPC 调用小模型训练服务
     * ③ 更新状态为 COMPLETED
     */
    private void triggerTraining(String taskId, String filePath, int rowCount) {
        log.info("[语料编排] 触发小模型训练, taskId={}, path={}, rows={}", taskId, filePath, rowCount);

        try {
            DispatchTask task = taskMapper.selectByTaskId(taskId);
            if (task == null) {
                log.warn("[语料编排] 任务不存在, taskId={}", taskId);
                return;
            }

            // ① 状态转换: CORPUS_STORED → TRAINING
            boolean transitioned = stateMachine.transition(
                    task, TaskStatus.CORPUS_STORED, TaskStatus.TRAINING);
            if (!transitioned) {
                log.warn("[语料编排] 状态转换失败, taskId={}, currentStatus={}",
                        taskId, task.getStatus());
                return;
            }

            String trainingTaskId = "train-" + UUID.randomUUID().toString().substring(0, 8);
            task.setTrainingTaskId(trainingTaskId);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // ② gRPC 调用小模型训练服务（透传 OSS 文件路径）
            String datasetUri = "oss://" + filePath;
            smallModelGrpcClient.startTraining(
                    trainingTaskId,
                    task.getTaskId(),
                    datasetUri,
                    rowCount
            );

            // ③ 状态转换: TRAINING → COMPLETED
            stateMachine.transition(task, TaskStatus.TRAINING, TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // 更新消息表状态
            updateOutboxStatus(taskId, "small-model-service", MessageOutbox.STATUS_CONFIRMED);

            log.info("[语料编排] 全流程完成, taskId={}, trainingTaskId={}", taskId, trainingTaskId);

        } catch (Exception e) {
            log.error("[语料编排] 触发训练失败, taskId={}, error={}", taskId, e.getMessage(), e);
            scheduleRetry(taskId, e.getMessage());
        }
    }

    // ============================== 重试与消息表 ==============================

    /**
     * 设置任务重试
     */
    public void scheduleRetry(String taskId, String errorMessage) {
        DispatchTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            log.warn("[语料编排] 设置重试时任务不存在, taskId={}", taskId);
            return;
        }

        int currentRetry = task.getRetryCount() + 1;
        task.setRetryCount(currentRetry);
        task.setErrorMessage(errorMessage);

        if (currentRetry >= task.getMaxRetries()) {
            stateMachine.fail(task, "重试次数耗尽: " + errorMessage);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.error("[语料编排] 任务失败(重试耗尽), taskId={}, retryCount={}/{}",
                    taskId, currentRetry, task.getMaxRetries());
            return;
        }

        // 计算下次重试时间: 指数退避
        long delaySeconds = (long) initialDelaySeconds * (long) Math.pow(backoffMultiplier, currentRetry - 1);
        task.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("[语料编排] 设置任务重试, taskId={}, retryCount={}/{}, nextRetryTime={}",
                taskId, currentRetry, task.getMaxRetries(), task.getNextRetryTime());
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

    private void updateOutboxStatus(String taskId, String targetService, String status) {
        try {
            MessageOutbox update = new MessageOutbox();
            update.setStatus(status);
            update.setUpdatedAt(LocalDateTime.now());
            outboxMapper.update(update, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MessageOutbox>()
                    .eq(MessageOutbox::getTaskId, taskId)
                    .eq(MessageOutbox::getTargetService, targetService));
        } catch (Exception e) {
            log.warn("[语料编排] 更新消息表状态失败, taskId={}, target={}", taskId, targetService, e);
        }
    }
}
