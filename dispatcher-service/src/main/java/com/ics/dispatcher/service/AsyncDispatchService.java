package com.ics.dispatcher.service;

import com.ics.dispatcher.grpc.client.LlmGrpcClient;
import com.ics.dispatcher.grpc.client.SmallModelGrpcClient;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.repository.mapper.DispatchTaskMapper;
import com.ics.dispatcher.repository.mapper.MessageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ============================================================================
 * 异步调度服务
 * ============================================================================
 * 将 @Async 方法从 DispatchTaskService 中抽离，避免 Spring AOP 自调用导致
 * @Async 注解失效的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncDispatchService {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final LlmGrpcClient llmGrpcClient;
    private final SmallModelGrpcClient smallModelGrpcClient;

    @Value("${dispatch.retry.max-retries:3}")
    private int maxRetries;

    @Value("${dispatch.retry.initial-delay-seconds:10}")
    private int initialDelaySeconds;

    @Value("${dispatch.retry.backoff-multiplier:2}")
    private int backoffMultiplier;

    /**
     * 【异步】调用大模型服务层的语料生成接口
     *
     * 通过 gRPC 调用 CorpusGenerationService.GenerateCorpus
     * 使用独立线程池，不阻塞事件处理主线程
     */
    @Async("dispatchExecutor")
    public void asyncCallCorpusGeneration(DispatchTask task) {
        log.info("[调度-异步] 开始调用语料生成, taskId={}", task.getTaskId());

        try {
            // 重新从数据库加载任务（避免使用事务中已缓存的实体）
            DispatchTask freshTask = taskMapper.selectByTaskId(task.getTaskId());
            if (freshTask == null) {
                log.warn("[调度-异步] 任务不存在, taskId={}", task.getTaskId());
                return;
            }

            // 更新任务状态: PENDING → CORPUS_GENERATING
            freshTask.setStatus(DispatchTask.STATUS_CORPUS_GENERATING);
            freshTask.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(freshTask);

            // 通过 gRPC 调用大模型语料生成服务
            llmGrpcClient.callCorpusGeneration(
                    freshTask.getTaskId(),
                    freshTask.getSessionId(),
                    freshTask.getUserId(),
                    freshTask.getDialogContext(),
                    freshTask.getTriggerType()
            );

            log.info("[调度-异步] 语料生成请求已发送, taskId={}", freshTask.getTaskId());

            // 更新消息表状态为已发送
            updateOutboxStatus(freshTask.getTaskId(), "llm-service", MessageOutbox.STATUS_SENT);

        } catch (Exception e) {
            log.error("[调度-异步] 调用语料生成失败, taskId={}, error={}",
                    task.getTaskId(), e.getMessage(), e);

            // 设置重试信息
            scheduleRetry(task.getTaskId(), e.getMessage());
        }
    }

    /**
     * 【异步】调用小模型训练接口 (重构: 透传 OSS 文件路径)
     *
     * 通过 gRPC 调用 SmallModelTrainingService.StartTraining
     * 使用 datasetUri 告诉训练端数据在哪 (oss://...)
     */
    @Async("dispatchExecutor")
    public void asyncCallSmallModelTraining(String taskId, String filePath, int rowCount) {
        log.info("[调度-异步] 开始触发小模型训练, taskId={}, path={}, rows={}", taskId, filePath, rowCount);

        try {
            // 重新从数据库加载任务
            DispatchTask task = taskMapper.selectByTaskId(taskId);
            if (task == null) {
                log.warn("[调度-异步] 任务不存在, taskId={}", taskId);
                return;
            }

            // 更新任务状态: CORPUS_STORED → TRAINING
            task.setStatus(DispatchTask.STATUS_TRAINING);
            String trainingTaskId = "train-" + UUID.randomUUID().toString().substring(0, 8);
            task.setTrainingTaskId(trainingTaskId);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // 通过 gRPC 调用小模型训练服务（透传 OSS 文件路径）
            String datasetUri = "oss://" + filePath;
            smallModelGrpcClient.startTraining(
                    trainingTaskId,
                    task.getTaskId(),
                    datasetUri,
                    rowCount
            );

            // 更新任务状态: TRAINING → COMPLETED
            task.setStatus(DispatchTask.STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // 更新消息表状态
            updateOutboxStatus(task.getTaskId(), "small-model-service", MessageOutbox.STATUS_CONFIRMED);

            log.info("[调度-异步] 小模型训练已触发, taskId={}, trainingTaskId={}, datasetUri={}",
                    task.getTaskId(), trainingTaskId, datasetUri);

        } catch (Exception e) {
            log.error("[调度-异步] 触发小模型训练失败, taskId={}, error={}",
                    taskId, e.getMessage(), e);
            scheduleRetry(taskId, e.getMessage());
        }
    }

    // ============================== 内部方法 ==============================

    private void updateOutboxStatus(String taskId, String targetService, String status) {
        try {
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

    private void scheduleRetry(String taskId, String errorMessage) {
        DispatchTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            log.warn("[调度] 设置重试时任务不存在, taskId={}", taskId);
            return;
        }

        int currentRetry = task.getRetryCount() + 1;
        task.setRetryCount(currentRetry);
        task.setErrorMessage(errorMessage);

        if (currentRetry >= task.getMaxRetries()) {
            // 超过最大重试次数，标记失败
            task.setStatus(DispatchTask.STATUS_FAILED);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.error("[调度] 任务失败, taskId={}, error={}", taskId, errorMessage);
            return;
        }

        // 计算下次重试时间: 指数退避
        long delaySeconds = (long) initialDelaySeconds * (long) Math.pow(backoffMultiplier, currentRetry - 1);
        task.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("[调度] 设置任务重试, taskId={}, retryCount={}/{}, nextRetryTime={}",
                taskId, currentRetry, task.getMaxRetries(), task.getNextRetryTime());
    }
}
