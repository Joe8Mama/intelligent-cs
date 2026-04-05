package com.ics.dispatcher.retry;

import com.ics.dispatcher.machine.TaskStateMachine;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.MessageOutbox;
import com.ics.dispatcher.orchestration.OrchestratorFactory;
import com.ics.dispatcher.orchestration.TaskOrchestrator;
import com.ics.dispatcher.orchestration.impl.CorpusGenOrchestrator;
import com.ics.dispatcher.repository.mapper.DispatchTaskMapper;
import com.ics.dispatcher.repository.mapper.MessageOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RetryScheduler 单元测试
 */
@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock
    private DispatchTaskMapper taskMapper;

    @Mock
    private MessageOutboxMapper outboxMapper;

    @Mock
    private OrchestratorFactory orchestratorFactory;

    @Mock
    private CorpusGenOrchestrator corpusGenOrchestrator;

    @Mock
    private TaskStateMachine stateMachine;

    @Mock
    private TaskOrchestrator mockOrchestrator;

    @InjectMocks
    private RetryScheduler retryScheduler;

    @BeforeEach
    void setUp() {
        lenient().when(orchestratorFactory.getOrchestrator("CORPUS_GEN")).thenReturn(mockOrchestrator);
    }

    @Test
    @DisplayName("重试失败任务 - 无需重试的任务")
    void testRetryFailedTasks_noRetryableTasks() {
        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator, never()).orchestrate(any());
        verify(corpusGenOrchestrator, never()).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("重试失败任务 - CORPUS_GENERATING状态重试")
    void testRetryFailedTasks_corpusGeneratingRetry() {
        DispatchTask task = createTask("task-001", DispatchTask.STATUS_CORPUS_GENERATING);
        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        when(stateMachine.isTerminal(any())).thenReturn(false);

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator).orchestrate(task);
    }

    @Test
    @DisplayName("重试失败任务 - TRAINING状态重试")
    void testRetryFailedTasks_trainingRetry() {
        DispatchTask task = createTask("task-001", DispatchTask.STATUS_TRAINING);
        task.setCorpusFilePath("corpus/raw/batch_001.jsonl");
        task.setCorpusCount(10);
        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        when(stateMachine.isTerminal(any())).thenReturn(false);

        retryScheduler.retryFailedTasks();

        verify(corpusGenOrchestrator).handleCorpusReady(eq("task-001"), eq("corpus/raw/batch_001.jsonl"), eq(10));
    }

    @Test
    @DisplayName("重试失败任务 - 多个任务并发重试")
    void testRetryFailedTasks_multipleTasks() {
        DispatchTask task1 = createTask("task-001", DispatchTask.STATUS_CORPUS_GENERATING);
        DispatchTask task2 = createTask("task-002", DispatchTask.STATUS_TRAINING);
        task2.setCorpusFilePath("path");
        task2.setCorpusCount(5);
        DispatchTask task3 = createTask("task-003", DispatchTask.STATUS_CORPUS_GENERATING);

        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task1, task2, task3));
        when(stateMachine.isTerminal(any())).thenReturn(false);

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator, times(2)).orchestrate(any());
        verify(corpusGenOrchestrator).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("重试失败任务 - 重试异常不影响其他任务")
    void testRetryFailedTasks_exceptionDoesNotAffectOthers() {
        DispatchTask task1 = createTask("task-001", DispatchTask.STATUS_CORPUS_GENERATING);
        DispatchTask task2 = createTask("task-002", DispatchTask.STATUS_CORPUS_GENERATING);

        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task1, task2));
        when(stateMachine.isTerminal(any())).thenReturn(false);

        doThrow(new RuntimeException("重试失败"))
                .when(mockOrchestrator).orchestrate(task1);

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator).orchestrate(task1);
        verify(mockOrchestrator).orchestrate(task2);
    }

    @Test
    @DisplayName("重试失败任务 - 终态任务跳过")
    void testRetryFailedTasks_terminalTaskSkipped() {
        DispatchTask task = createTask("task-001", DispatchTask.STATUS_COMPLETED);
        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        when(stateMachine.isTerminal(task)).thenReturn(true);

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator, never()).orchestrate(any());
        verify(corpusGenOrchestrator, never()).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("检查超时任务 - 正常执行")
    void testCheckTimeoutTasks_normal() {
        retryScheduler.checkTimeoutTasks();
    }

    @Test
    @DisplayName("补偿未投递消息 - 无待补偿消息")
    void testCompensateUndeliveredMessages_noPendingMessages() {
        when(outboxMapper.selectPendingMessages(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        retryScheduler.compensateUndeliveredMessages();

        verify(outboxMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("补偿未投递消息 - 有待补偿消息")
    void testCompensateUndeliveredMessages_withPendingMessages() {
        MessageOutbox msg = createMessage("msg-001", "llm-service", "GenerateCorpus");
        when(outboxMapper.selectPendingMessages(any(LocalDateTime.class)))
                .thenReturn(List.of(msg));
        when(outboxMapper.updateById(any(MessageOutbox.class))).thenReturn(1);

        retryScheduler.compensateUndeliveredMessages();

        verify(outboxMapper).updateById(any(MessageOutbox.class));
        assertEquals(1, msg.getRetryCount());
        assertNotNull(msg.getNextRetryTime());
    }

    @Test
    @DisplayName("补偿未投递消息 - 多条消息并发处理")
    void testCompensateUndeliveredMessages_multipleMessages() {
        MessageOutbox msg1 = createMessage("msg-001", "llm-service", "GenerateCorpus");
        MessageOutbox msg2 = createMessage("msg-002", "small-model-service", "StartTraining");

        when(outboxMapper.selectPendingMessages(any(LocalDateTime.class)))
                .thenReturn(List.of(msg1, msg2));
        when(outboxMapper.updateById(any(MessageOutbox.class))).thenReturn(1);

        retryScheduler.compensateUndeliveredMessages();

        verify(outboxMapper, times(2)).updateById(any(MessageOutbox.class));
    }

    @Test
    @DisplayName("补偿未投递消息 - 异常不影响其他消息")
    void testCompensateUndeliveredMessages_exceptionDoesNotAffectOthers() {
        MessageOutbox msg1 = createMessage("msg-001", "llm-service", "GenerateCorpus");
        MessageOutbox msg2 = createMessage("msg-002", "llm-service", "GenerateCorpus");

        when(outboxMapper.selectPendingMessages(any(LocalDateTime.class)))
                .thenReturn(List.of(msg1, msg2));

        when(outboxMapper.updateById(msg1))
                .thenThrow(new RuntimeException("更新失败"));
        when(outboxMapper.updateById(msg2)).thenReturn(1);

        retryScheduler.compensateUndeliveredMessages();

        verify(outboxMapper).updateById(msg1);
        verify(outboxMapper).updateById(msg2);
    }

    @Test
    @DisplayName("重试失败任务 - 未知状态仅记录日志")
    void testRetryFailedTasks_unknownStatus() {
        DispatchTask task = createTask("task-001", "UNKNOWN_STATUS");
        when(taskMapper.selectRetryableTasks(any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        when(stateMachine.isTerminal(any())).thenReturn(false);

        retryScheduler.retryFailedTasks();

        verify(mockOrchestrator, never()).orchestrate(any());
        verify(corpusGenOrchestrator, never()).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    private DispatchTask createTask(String taskId, String status) {
        DispatchTask task = new DispatchTask();
        task.setId(1L);
        task.setTaskId(taskId);
        task.setEventId("event-" + taskId);
        task.setSessionId("session-001");
        task.setUserId("user-001");
        task.setTriggerType("INTENT_FAILED");
        task.setStatus(status);
        task.setDialogContext("{}");
        task.setCorpusCount(0);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        task.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
        task.setCreatedAt(LocalDateTime.now().minusHours(1));
        task.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return task;
    }

    private MessageOutbox createMessage(String messageId, String targetService, String methodName) {
        MessageOutbox msg = new MessageOutbox();
        msg.setId(1L);
        msg.setMessageId(messageId);
        msg.setTaskId("task-001");
        msg.setTargetService(targetService);
        msg.setMethodName(methodName);
        msg.setPayload("{}");
        msg.setStatus(MessageOutbox.STATUS_PENDING);
        msg.setRetryCount(0);
        msg.setMaxRetries(3);
        msg.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        msg.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return msg;
    }
}
