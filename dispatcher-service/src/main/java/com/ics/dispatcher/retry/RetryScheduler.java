package com.ics.dispatcher.retry;

import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.repository.mapper.DispatchTaskMapper;
import com.ics.dispatcher.repository.mapper.MessageOutboxMapper;
import com.ics.dispatcher.service.AsyncDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 定时重试调度器
 * ============================================================================
 * 保证异步调用的可靠性，定期扫描并重试失败的任务和消息
 *
 * 两层补偿机制:
 * 1. 任务级重试: 扫描 dispatch_task 表中需要重试的任务
 * 2. 消息级重试: 扫描 message_outbox 表中未成功投递的消息
 *
 * 通过定时任务 + 指数退避策略，实现最终一致性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final AsyncDispatchService asyncDispatchService;

    /**
     * 任务级重试
     * 每分钟执行一次，扫描需要重试的调度任务
     *
     * 处理场景:
     * - gRPC 调用大模型服务超时/失败
     * - gRPC 调用小模型训练超时/失败
     */
    @Scheduled(cron = "${dispatch.scheduler.retry-cron:0 */1 * * * ?}")
    public void retryFailedTasks() {
        log.debug("[重试调度] 开始扫描需要重试的任务...");

        List<DispatchTask> retryableTasks = taskMapper.selectRetryableTasks(LocalDateTime.now());

        if (retryableTasks.isEmpty()) {
            log.debug("[重试调度] 没有需要重试的任务");
            return;
        }

        log.info("[重试调度] 发现 {} 个需要重试的任务", retryableTasks.size());

        for (DispatchTask task : retryableTasks) {
            try {
                log.info("[重试调度] 重试任务, taskId={}, status={}, retryCount={}/{}",
                        task.getTaskId(), task.getStatus(), task.getRetryCount(), task.getMaxRetries());

                switch (task.getStatus()) {
                    case DispatchTask.STATUS_CORPUS_GENERATING:
                        // 语料生成阶段失败，重新调用语料生成
                        asyncDispatchService.asyncCallCorpusGeneration(task);
                        break;

                    case DispatchTask.STATUS_TRAINING:
                        // 训练阶段失败，重新触发训练（从任务中获取文件路径）
                        log.info("[重试调度] 训练阶段重试, taskId={}", task.getTaskId());
                        asyncDispatchService.asyncCallSmallModelTraining(
                                task.getTaskId(),
                                task.getCorpusFilePath() != null ? task.getCorpusFilePath() : "",
                                task.getCorpusCount() != null ? task.getCorpusCount() : 0);
                        break;

                    default:
                        log.warn("[重试调度] 未知状态, taskId={}, status={}", task.getTaskId(), task.getStatus());
                }

            } catch (Exception e) {
                log.error("[重试调度] 重试执行异常, taskId={}, error={}",
                        task.getTaskId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 超时任务检查
     * 每5分钟执行一次，检查长时间未完成的任务
     */
    @Scheduled(cron = "${dispatch.scheduler.timeout-check-cron:0 */5 * * * ?}")
    public void checkTimeoutTasks() {
        log.debug("[超时检查] 开始检查超时任务...");

        // 查找超过30分钟仍在 CORPUS_GENERATING 状态的任务
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(30);

        // 这里可以添加超时任务的处理逻辑
        // 例如: 将超时任务标记为需要重试，或者直接标记为失败
        log.debug("[超时检查] 检查完成");
    }

    /**
     * 消息补偿投递
     * 每2分钟执行一次，扫描本地消息表中未成功投递的消息
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void compensateUndeliveredMessages() {
        log.debug("[消息补偿] 开始扫描未投递消息...");

        List<MessageOutbox> pendingMessages = outboxMapper.selectPendingMessages(LocalDateTime.now());

        if (pendingMessages.isEmpty()) {
            log.debug("[消息补偿] 没有待补偿的消息");
            return;
        }

        log.info("[消息补偿] 发现 {} 条待补偿消息", pendingMessages.size());

        for (MessageOutbox msg : pendingMessages) {
            try {
                log.info("[消息补偿] 补偿投递消息, messageId={}, target={}, method={}",
                        msg.getMessageId(), msg.getTargetService(), msg.getMethodName());

                // 根据目标服务和方法，重新投递消息
                // 实际实现中应根据 targetService 和 methodName 路由到对应的 gRPC Client
                // 这里为框架示例，具体路由逻辑需要根据业务补充

                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setUpdatedAt(LocalDateTime.now());

                // 计算下次重试时间
                long delay = 10L * (long) Math.pow(2, msg.getRetryCount());
                msg.setNextRetryTime(LocalDateTime.now().plusSeconds(delay));

                outboxMapper.updateById(msg);

            } catch (Exception e) {
                log.error("[消息补偿] 补偿投递失败, messageId={}, error={}",
                        msg.getMessageId(), e.getMessage(), e);
            }
        }
    }
}
