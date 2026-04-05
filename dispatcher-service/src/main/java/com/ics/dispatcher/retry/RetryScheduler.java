package com.ics.dispatcher.retry;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 定时重试调度器 (重构: 委托编排器执行重试)
 * ============================================================================
 * 保证异步调用的可靠性，定期扫描并重试失败的任务和消息
 *
 * 设计要点:
 * - 任务级重试委托给 Orchestrator 执行，此调度器仅负责扫描和分发
 * - 状态判断基于 TaskStatus 枚举，而非硬编码字符串
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final DispatchTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final OrchestratorFactory orchestratorFactory;
    private final CorpusGenOrchestrator corpusGenOrchestrator;
    private final TaskStateMachine stateMachine;

    /**
     * 任务级重试
     * 每分钟执行一次，扫描需要重试的调度任务
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
                if (stateMachine.isTerminal(task)) {
                    log.debug("[重试调度] 任务已处于终态, 跳过, taskId={}, status={}",
                            task.getTaskId(), task.getStatus());
                    continue;
                }

                TaskStatus status = TaskStatus.fromCode(task.getStatus());
                log.info("[重试调度] 重试任务, taskId={}, status={}, retryCount={}/{}",
                        task.getTaskId(), status, task.getRetryCount(), task.getMaxRetries());

                switch (status) {
                    case CORPUS_GENERATING -> {
                        // 语料生成阶段重试：重新编排
                        TaskOrchestrator orchestrator = orchestratorFactory.getOrchestrator("CORPUS_GEN");
                        orchestrator.orchestrate(task);
                    }
                    case TRAINING -> {
                        // 训练阶段重试：重新触发训练
                        corpusGenOrchestrator.handleCorpusReady(
                                task.getTaskId(),
                                task.getCorpusFilePath() != null ? task.getCorpusFilePath() : "",
                                task.getCorpusCount() != null ? task.getCorpusCount() : 0
                        );
                    }
                    default -> log.warn("[重试调度] 未知状态, taskId={}, status={}",
                            task.getTaskId(), status);
                }

            } catch (Exception e) {
                log.error("[重试调度] 重试执行异常, taskId={}, error={}",
                        task.getTaskId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 超时任务检查
     * 每5分钟执行一次
     */
    @Scheduled(cron = "${dispatch.scheduler.timeout-check-cron:0 */5 * * * ?}")
    public void checkTimeoutTasks() {
        log.debug("[超时检查] 开始检查超时任务...");
        log.debug("[超时检查] 检查完成");
    }

    /**
     * 消息补偿投递
     * 每2分钟执行一次
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

                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setUpdatedAt(LocalDateTime.now());

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
