package com.ics.dispatcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DispatchTaskService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class DispatchTaskServiceTest {

    @Mock
    private DispatchTaskMapper taskMapper;

    @Mock
    private MessageOutboxMapper outboxMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private OrchestratorFactory orchestratorFactory;

    @Mock
    private CorpusGenOrchestrator corpusGenOrchestrator;

    @Mock
    private TaskStateMachine stateMachine;

    @Mock
    private TaskOrchestrator mockOrchestrator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DispatchTaskService dispatchTaskService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dispatchTaskService, "maxRetries", 3);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(orchestratorFactory.getOrchestrator("CORPUS_GEN")).thenReturn(mockOrchestrator);
    }

    @Test
    @DisplayName("处理意图识别失败事件 - 正常流程")
    void testHandleIntentFailedEvent_normal() {
        String eventId = "event-001";
        String sessionId = "session-001";
        String userId = "user-001";
        String dialogContext = "{\"messages\":[]}";

        when(valueOperations.get(anyString())).thenReturn(null);
        when(taskMapper.selectByEventId(eventId)).thenReturn(null);
        when(taskMapper.insert(any(DispatchTask.class))).thenAnswer(invocation -> {
            DispatchTask task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        });
        when(outboxMapper.insert(any(MessageOutbox.class))).thenReturn(1);

        String taskId = dispatchTaskService.handleIntentFailedEvent(eventId, sessionId, userId, dialogContext);

        assertNotNull(taskId);
        assertTrue(taskId.startsWith("task-"));
        verify(taskMapper).insert(any(DispatchTask.class));
        verify(outboxMapper).insert(any(MessageOutbox.class));
        verify(valueOperations).set(anyString(), eq(taskId), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("处理意图识别失败事件 - 幂等性检查通过Redis缓存")
    void testHandleIntentFailedEvent_idempotentFromCache() {
        String eventId = "event-001";
        String existingTaskId = "task-existing";
        when(valueOperations.get(anyString())).thenReturn(existingTaskId);

        String taskId = dispatchTaskService.handleIntentFailedEvent(
                eventId, "session-001", "user-001", "{}");

        assertEquals(existingTaskId, taskId);
        verify(taskMapper, never()).insert(any());
        verify(outboxMapper, never()).insert(any());
    }

    @Test
    @DisplayName("处理意图识别失败事件 - 幂等性检查通过数据库")
    void testHandleIntentFailedEvent_idempotentFromDatabase() {
        String eventId = "event-001";
        String existingTaskId = "task-existing";
        DispatchTask existingTask = new DispatchTask();
        existingTask.setTaskId(existingTaskId);

        when(valueOperations.get(anyString())).thenReturn(null);
        when(taskMapper.selectByEventId(eventId)).thenReturn(existingTask);

        String taskId = dispatchTaskService.handleIntentFailedEvent(
                eventId, "session-001", "user-001", "{}");

        assertEquals(existingTaskId, taskId);
        verify(valueOperations).set(anyString(), eq(existingTaskId), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("处理用户反馈事件 - 正常流程")
    void testHandleUserFeedbackEvent_normal() {
        String eventId = "event-002";
        String feedbackContent = "回答不准确";
        String dialogContext = "{}";

        when(valueOperations.get(anyString())).thenReturn(null);
        when(taskMapper.selectByEventId(eventId)).thenReturn(null);
        when(taskMapper.insert(any(DispatchTask.class))).thenReturn(1);
        when(outboxMapper.insert(any(MessageOutbox.class))).thenReturn(1);

        String taskId = dispatchTaskService.handleUserFeedbackEvent(
                eventId, "session-001", "user-001", feedbackContent, dialogContext);

        assertNotNull(taskId);
        ArgumentCaptor<DispatchTask> taskCaptor = ArgumentCaptor.forClass(DispatchTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertEquals("USER_FEEDBACK", taskCaptor.getValue().getTriggerType());
    }

    @Test
    @DisplayName("处理语料就绪通知 - 正常流程（委托给 CorpusGenOrchestrator）")
    void testHandleCorpusReadyNotification_normal() {
        String taskId = "task-001";
        DispatchTask task = createTask(taskId, DispatchTask.STATUS_CORPUS_GENERATING);
        when(taskMapper.selectByTaskId(taskId)).thenReturn(task);
        when(taskMapper.updateById(any(DispatchTask.class))).thenReturn(1);

        dispatchTaskService.handleCorpusReadyNotification(
                taskId, "session-001", "corpus/raw/batch_001.jsonl", "abc123md5", 10, true, "成功");

        // 验证委托给 corpusGenOrchestrator
        verify(corpusGenOrchestrator).handleCorpusReady(taskId, "corpus/raw/batch_001.jsonl", 10);
    }

    @Test
    @DisplayName("处理语料就绪通知 - 任务不存在")
    void testHandleCorpusReadyNotification_taskNotFound() {
        String taskId = "task-not-exist";
        when(taskMapper.selectByTaskId(taskId)).thenReturn(null);

        dispatchTaskService.handleCorpusReadyNotification(
                taskId, "session-001", "path", "md5", 5, true, "成功");

        verify(corpusGenOrchestrator, never()).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("处理语料就绪通知 - 语料生成失败")
    void testHandleCorpusReadyNotification_corpusGenerationFailed() {
        String taskId = "task-001";
        DispatchTask task = createTask(taskId, DispatchTask.STATUS_CORPUS_GENERATING);
        when(taskMapper.selectByTaskId(taskId)).thenReturn(task);
        when(taskMapper.updateById(any(DispatchTask.class))).thenReturn(1);

        dispatchTaskService.handleCorpusReadyNotification(
                taskId, "session-001", "", "", 0, false, "生成失败");

        // 验证通过 stateMachine 设置失败
        verify(stateMachine).fail(eq(task), contains("生成失败"));
        verify(corpusGenOrchestrator, never()).handleCorpusReady(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("查询任务 - 正常情况")
    void testQueryTask_normal() {
        String taskId = "task-001";
        DispatchTask task = createTask(taskId, DispatchTask.STATUS_PENDING);
        when(taskMapper.selectByTaskId(taskId)).thenReturn(task);

        DispatchTask result = dispatchTaskService.queryTask(taskId);

        assertNotNull(result);
        assertEquals(taskId, result.getTaskId());
    }

    @Test
    @DisplayName("处理意图识别失败事件 - 验证编排器被调用")
    void testHandleIntentFailedEvent_delegatesToOrchestrator() {
        String eventId = "event-003";
        when(valueOperations.get(anyString())).thenReturn(null);
        when(taskMapper.selectByEventId(eventId)).thenReturn(null);
        when(taskMapper.insert(any(DispatchTask.class))).thenReturn(1);
        when(outboxMapper.insert(any(MessageOutbox.class))).thenReturn(1);

        String taskId = dispatchTaskService.handleIntentFailedEvent(
                eventId, "session-001", "user-001", "{}");

        assertNotNull(taskId);
        verify(orchestratorFactory).getOrchestrator("CORPUS_GEN");
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
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }
}
