package com.ics.dispatcher.integration;

import com.ics.dispatcher.grpc.proto.*;
import com.ics.llm.grpc.proto.*;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度服务 集成测试
 *
 * 前置条件:
 * - dispatcher-service 已启动 (gRPC 端口 50052)
 * - llm-service 已启动 (gRPC 端口 50051) (端到端测试依赖)
 * - MySQL、Redis 可达
 *
 * 测试覆盖的 gRPC 接口 (dispatcher-service):
 * - OnIntentFailed: 意图识别失败事件 → 创建调度任务
 * - OnUserFeedback: 用户反馈不佳事件 → 创建调度任务
 * - OnCorpusReady: 语料入库完成通知 → 触发后续流程
 * - QueryTaskStatus: 任务状态查询
 *
 * 测试分层:
 * - 事件处理与幂等性: 不依赖外部 LLM API
 * - 参数校验: 纯服务端逻辑
 * - 任务状态查询: 依赖数据库
 * - 端到端流程: 意图失败 → 调度创建任务 → LLM 兜底回答 → 语料生成 → 状态追踪
 *
 * 注意: LLM 服务直连测试 (Chat/BatchChat/StreamChat/GenerateCorpus)
 *       已移至 LlmServiceIntegrationTest，本类专注调度层逻辑。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
class DispatcherServiceIntegrationTest {

    private static ManagedChannel dispatcherChannel;
    private static ManagedChannel llmChannel;
    private static DispatcherServiceGrpc.DispatcherServiceBlockingStub dispatcherStub;
    private static LlmServiceGrpc.LlmServiceBlockingStub llmStub;

    private static final String DISPATCHER_HOST = "localhost";
    private static final int DISPATCHER_PORT = 50052;
    private static final String LLM_HOST = "localhost";
    private static final int LLM_PORT = 50051;

    /** 外部 LLM API 是否可达（仅端到端测试依赖） */
    private static boolean llmApiAvailable = true;
    private static String llmApiSkipReason = null;

    @BeforeAll
    static void setUp() {
        dispatcherChannel = ManagedChannelBuilder.forAddress(DISPATCHER_HOST, DISPATCHER_PORT)
                .usePlaintext().build();
        llmChannel = ManagedChannelBuilder.forAddress(LLM_HOST, LLM_PORT)
                .usePlaintext().build();

        dispatcherStub = DispatcherServiceGrpc.newBlockingStub(dispatcherChannel);
        llmStub = LlmServiceGrpc.newBlockingStub(llmChannel);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        if (dispatcherChannel != null) {
            dispatcherChannel.shutdown();
            dispatcherChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (llmChannel != null) {
            llmChannel.shutdown();
            llmChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ======================== LLM API 可用性检查 ========================

    private void assumeLlmApiAvailable(String testName) {
        Assumptions.assumeTrue(llmApiAvailable,
                "[跳过] " + testName + " - LLM API 不可用: " + llmApiSkipReason);
    }

    // ======================== 1. 意图识别失败事件 ========================

    @Test
    @Order(1)
    @DisplayName("意图失败事件 - 创建调度任务")
    void testIntentFailedEvent_createsTask() {
        String eventId = "integ-intent-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionId = "session-integ-001";
        String userId = "user-integ-001";

        IntentFailedEvent request = IntentFailedEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId(sessionId)
                .setUserId(userId)
                .setUserInput("如何办理退款")
                .setSmallModelResponse("抱歉，我不理解您的问题")
                .setIntentConfidence(0.2f)
                .setEventTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .addDialogHistory(
                        DialogMessage.newBuilder()
                                .setRole("user").setContent("我想退款").setTimestamp(System.currentTimeMillis()).build()
                )
                .addDialogHistory(
                        DialogMessage.newBuilder()
                                .setRole("assistant").setContent("请问您要退哪个订单？").setTimestamp(System.currentTimeMillis()).build()
                )
                .addDialogHistory(
                        DialogMessage.newBuilder()
                                .setRole("user").setContent("如何办理退款").setTimestamp(System.currentTimeMillis()).build()
                )
                .build();

        EventResponse response = dispatcherStub.onIntentFailed(request);

        assertEquals(0, response.getCode(), "事件应被成功接收, code=0");
        assertNotNull(response.getTaskId(), "应返回非空 taskId");
        assertTrue(response.getTaskId().startsWith("task-"), "taskId 应以 task- 开头");
        assertEquals(eventId, response.getEventId());
        assertTrue(response.getMessage().contains("事件已接收"));

        System.out.println("[1-1] 意图识别失败事件已处理, taskId=" + response.getTaskId());
    }

    @Test
    @Order(2)
    @DisplayName("意图失败事件 - 幂等性: 重复 eventId 返回相同 taskId")
    void testIntentFailedEvent_idempotent() {
        String eventId = "integ-idempotent-" + UUID.randomUUID().toString().substring(0, 8);

        IntentFailedEvent request = IntentFailedEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-idempotent")
                .setUserId("user-idempotent")
                .setUserInput("测试幂等性")
                .setIntentConfidence(0.1f)
                .build();

        EventResponse response1 = dispatcherStub.onIntentFailed(request);
        EventResponse response2 = dispatcherStub.onIntentFailed(request);

        assertNotNull(response1.getTaskId());
        assertNotNull(response2.getTaskId());
        assertEquals(response1.getTaskId(), response2.getTaskId(), "重复事件应返回相同 taskId");
        assertEquals(0, response2.getCode());

        System.out.println("[1-2] 幂等性验证通过, taskId=" + response1.getTaskId());
    }

    @Test
    @Order(3)
    @DisplayName("意图失败事件 - 参数校验: 空 eventId")
    void testIntentFailedEvent_emptyEventId() {
        IntentFailedEvent request = IntentFailedEvent.newBuilder()
                .setEventId("")
                .setSessionId("session-test")
                .setUserInput("test")
                .build();

        EventResponse response = dispatcherStub.onIntentFailed(request);

        assertEquals(2, response.getCode(), "空 eventId 应返回参数错误 code=2");
        System.out.println("[1-3] 参数校验通过: " + response.getMessage());
    }

    // ======================== 2. 用户反馈不佳事件 ========================

    @Test
    @Order(4)
    @DisplayName("用户反馈事件 - 创建调度任务")
    void testUserFeedbackEvent_createsTask() {
        String eventId = "integ-feedback-" + UUID.randomUUID().toString().substring(0, 8);

        UserFeedbackEvent request = UserFeedbackEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-feedback-001")
                .setUserId("user-feedback-001")
                .setFeedbackContent("回答与我的问题完全无关")
                .setRating(1)
                .setEventTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .addDialogHistory(
                        DialogMessage.newBuilder()
                                .setRole("user").setContent("你们支持货到付款吗").build()
                )
                .addDialogHistory(
                        DialogMessage.newBuilder()
                                .setRole("assistant").setContent("今天天气不错").build()
                )
                .build();

        EventResponse response = dispatcherStub.onUserFeedback(request);

        assertEquals(0, response.getCode(), "反馈事件应被成功接收");
        assertNotNull(response.getTaskId());
        assertTrue(response.getMessage().contains("反馈已接收"));

        System.out.println("[2-1] 用户反馈事件已处理, taskId=" + response.getTaskId());
    }

    @Test
    @Order(5)
    @DisplayName("用户反馈事件 - 参数校验: 空 eventId")
    void testUserFeedbackEvent_emptyEventId() {
        UserFeedbackEvent request = UserFeedbackEvent.newBuilder()
                .setEventId("")
                .setSessionId("session-test")
                .setFeedbackContent("不好")
                .build();

        EventResponse response = dispatcherStub.onUserFeedback(request);

        assertEquals(2, response.getCode(), "空 eventId 应返回参数错误");
        System.out.println("[2-2] 用户反馈参数校验通过: " + response.getMessage());
    }

    // ======================== 3. 语料就绪回调 ========================

    @Test
    @Order(6)
    @DisplayName("语料就绪通知 - 不存在的任务应正常返回")
    void testCorpusReadyNotification_taskNotFound() {
        String taskId = "integ-corpus-ready-" + UUID.randomUUID().toString().substring(0, 8);

        CorpusReadyNotification notification = CorpusReadyNotification.newBuilder()
                .setTaskId(taskId)
                .setSessionId("session-corpus-ready-001")
                .setFilePath("corpus/raw/test_batch.jsonl")
                .setFileMd5("abc123def456")
                .setRowCount(3)
                .setSuccess(true)
                .setMessage("语料入库完成")
                .setCompleteTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .build();

        EventResponse response = dispatcherStub.onCorpusReady(notification);

        // 对于不存在的任务, dispatcher 也会返回 code=0 (内部忽略)
        assertEquals(0, response.getCode());
        System.out.println("[3-1] 语料就绪通知已处理(任务不存在): " + response.getMessage());
    }

    @Test
    @Order(7)
    @DisplayName("语料就绪通知 - 参数校验: 空 taskId")
    void testCorpusReadyNotification_emptyTaskId() {
        CorpusReadyNotification notification = CorpusReadyNotification.newBuilder()
                .setTaskId("")
                .setSessionId("session-test")
                .setSuccess(true)
                .build();

        EventResponse response = dispatcherStub.onCorpusReady(notification);

        assertEquals(2, response.getCode(), "空 taskId 应返回参数错误");
        System.out.println("[3-2] 语料就绪参数校验通过: " + response.getMessage());
    }

    @Test
    @Order(8)
    @DisplayName("语料就绪通知 - 已创建任务后回调 (完整流程)")
    void testCorpusReadyNotification_withRealTask() {
        // Step 1: 创建任务
        String eventId = "integ-corpus-cb-" + UUID.randomUUID().toString().substring(0, 8);
        IntentFailedEvent event = IntentFailedEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-cb-test")
                .setUserId("user-cb-test")
                .setUserInput("测试语料就绪回调")
                .setIntentConfidence(0.1f)
                .build();

        EventResponse createResponse = dispatcherStub.onIntentFailed(event);
        String taskId = createResponse.getTaskId();
        assertNotNull(taskId);

        // Step 2: 等待异步编排器处理（PENDING → CORPUS_GENERATING）
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // Step 3: 发送语料就绪通知
        CorpusReadyNotification notification = CorpusReadyNotification.newBuilder()
                .setTaskId(taskId)
                .setSessionId("session-cb-test")
                .setFilePath("corpus/raw/integ_cb_test.jsonl")
                .setFileMd5("testmd5" + UUID.randomUUID().toString().substring(0, 8))
                .setRowCount(5)
                .setSuccess(true)
                .setMessage("语料入库完成")
                .setCompleteTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .build();

        EventResponse readyResponse = dispatcherStub.onCorpusReady(notification);
        assertEquals(0, readyResponse.getCode(), "语料就绪通知应被接受");

        // Step 4: 短暂等待后查询任务状态（应为 CORPUS_STORED 或 TRAINING）
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        TaskStatusResponse statusResponse = dispatcherStub.queryTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId(taskId).build());

        assertNotNull(statusResponse);
        assertEquals(taskId, statusResponse.getTaskId());
        // 语料就绪后，编排器会尝试触发小模型训练（可能失败，但不影响语料存储状态）
        assertTrue(
                statusResponse.getStatus() == TaskStatus.TASK_CORPUS_STORED
                        || statusResponse.getStatus() == TaskStatus.TASK_TRAINING
                        || statusResponse.getStatus() == TaskStatus.TASK_FAILED,
                "语料就绪后状态应为 CORPUS_STORED/TRAINING/FAILED, 实际: " + statusResponse.getStatus());

        System.out.println("[3-3] 语料就绪回调完整流程通过, taskId=" + taskId
                + ", 最终状态=" + statusResponse.getStatus());
    }

    // ======================== 4. 任务状态查询 ========================

    @Test
    @Order(9)
    @DisplayName("任务查询 - 不存在的任务返回 UNKNOWN")
    void testQueryTaskStatus_notFound() {
        TaskStatusRequest request = TaskStatusRequest.newBuilder()
                .setTaskId("task-nonexistent-" + UUID.randomUUID())
                .build();

        TaskStatusResponse response = dispatcherStub.queryTaskStatus(request);

        assertEquals(TaskStatus.TASK_STATUS_UNKNOWN, response.getStatus(), "不存在的任务应返回 UNKNOWN");
        assertTrue(response.getMessage().contains("不存在") || response.getMessage().isEmpty());
        System.out.println("[4-1] 查询不存在的任务: " + response.getMessage());
    }

    @Test
    @Order(10)
    @DisplayName("任务查询 - 创建任务后立即查询")
    void testQueryTaskStatus_afterCreate() {
        // 先创建任务
        String eventId = "integ-query-" + UUID.randomUUID().toString().substring(0, 8);

        IntentFailedEvent event = IntentFailedEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-query-001")
                .setUserId("user-query-001")
                .setUserInput("测试查询")
                .setIntentConfidence(0.15f)
                .build();

        EventResponse createResponse = dispatcherStub.onIntentFailed(event);
        String taskId = createResponse.getTaskId();
        assertNotNull(taskId);

        // 短暂等待异步编排开始
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        // 查询任务状态
        TaskStatusResponse statusResponse = dispatcherStub.queryTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId(taskId).build());

        assertNotNull(statusResponse);
        assertEquals(taskId, statusResponse.getTaskId());
        assertEquals("session-query-001", statusResponse.getSessionId());
        assertEquals("INTENT_FAILED", statusResponse.getTriggerType());
        assertTrue(statusResponse.getStatus() == TaskStatus.TASK_PENDING
                        || statusResponse.getStatus() == TaskStatus.TASK_CORPUS_GENERATING,
                "任务状态应为 PENDING 或 CORPUS_GENERATING");

        System.out.println("[4-2] 任务查询成功, taskId=" + taskId
                + ", status=" + statusResponse.getStatus() + ", retryCount=" + statusResponse.getRetryCount());
    }

    @Test
    @Order(11)
    @DisplayName("任务查询 - 用户反馈触发类型的任务")
    void testQueryTaskStatus_userFeedbackTask() {
        String eventId = "integ-query-uf-" + UUID.randomUUID().toString().substring(0, 8);

        UserFeedbackEvent event = UserFeedbackEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-query-uf")
                .setUserId("user-query-uf")
                .setFeedbackContent("回答完全不相关")
                .setRating(1)
                .build();

        EventResponse createResponse = dispatcherStub.onUserFeedback(event);
        String taskId = createResponse.getTaskId();
        assertNotNull(taskId);

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        TaskStatusResponse statusResponse = dispatcherStub.queryTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId(taskId).build());

        assertEquals(taskId, statusResponse.getTaskId());
        assertEquals("USER_FEEDBACK", statusResponse.getTriggerType());

        System.out.println("[4-3] 用户反馈任务查询成功, taskId=" + taskId
                + ", triggerType=" + statusResponse.getTriggerType());
    }

    // ======================== 5. 端到端流程 ========================

    @Test
    @Order(12)
    @DisplayName("端到端: 意图失败 → 调度创建任务 → 大模型兜底回答 → 状态追踪 (依赖 LLM API)")
    void testEndToEnd_intentFailedAndDirectAnswer() {
        assumeLlmApiAvailable("端到端完整流程");

        // Step 1: 模拟小模型意图识别失败，发送事件到调度服务
        String eventId = "integ-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String userQuestion = "你们的会员有什么权益？";

        IntentFailedEvent event = IntentFailedEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId("session-e2e-001")
                .setUserId("user-e2e-001")
                .setUserInput(userQuestion)
                .setSmallModelResponse("抱歉，我无法理解")
                .setIntentConfidence(0.18f)
                .setEventTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .addDialogHistory(
                        DialogMessage.newBuilder().setRole("user").setContent(userQuestion).build()
                )
                .build();

        EventResponse dispatchResponse = dispatcherStub.onIntentFailed(event);
        assertEquals(0, dispatchResponse.getCode());
        String taskId = dispatchResponse.getTaskId();
        System.out.println("[5-1] 调度任务已创建: " + taskId);

        // Step 2: 同时调用大模型获取兜底回答（模拟前端并发调用）
        ChatRequest chatRequest = ChatRequest.newBuilder()
                .setRequestId("e2e-direct-" + UUID.randomUUID().toString().substring(0, 8))
                .setSessionId("session-e2e-001")
                .setUserId("user-e2e-001")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent(userQuestion).build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.7f).setMaxTokens(512).build())
                .build();

        ChatResponse chatResponse = llmStub.chat(chatRequest);
        if (chatResponse.getCode() == 99) {
            llmApiAvailable = false;
            llmApiSkipReason = chatResponse.getMessage();
            Assumptions.assumeTrue(false, "[跳过] 端到端 - LLM API 调用失败: " + chatResponse.getMessage());
        }
        assertEquals(0, chatResponse.getCode());
        assertNotNull(chatResponse.getAnswer());
        System.out.println("[5-2] 大模型兜底回答: " +
                truncate(chatResponse.getAnswer(), 150));

        // Step 3: 等待异步编排流程，查询任务状态
        // 编排器会异步调用 llm-service 语料生成，任务应从 PENDING 转为 CORPUS_GENERATING
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        TaskStatusResponse statusResponse = dispatcherStub.queryTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId(taskId).build());

        assertNotNull(statusResponse);
        assertEquals(taskId, statusResponse.getTaskId());
        assertTrue(statusResponse.getStatus() != TaskStatus.TASK_STATUS_UNKNOWN,
                "任务应存在并有有效状态");

        System.out.println("[5-3] 任务最终状态: " + statusResponse.getStatus()
                + ", triggerType=" + statusResponse.getTriggerType()
                + ", retryCount=" + statusResponse.getRetryCount());
    }

    @Test
    @Order(13)
    @DisplayName("端到端: 用户反馈 → 调度任务 → 大模型回答 → 语料生成受理 (依赖 LLM API)")
    void testEndToEnd_userFeedbackAndCorpus() {
        assumeLlmApiAvailable("用户反馈端到端");

        String userQuestion = "你们的配送范围是哪些城市？";
        String sessionId = "session-e2e-fb-" + UUID.randomUUID().toString().substring(0, 8);

        // Step 1: 获取大模型回答（模拟已有的对话）
        ChatRequest chatRequest = ChatRequest.newBuilder()
                .setRequestId("e2e-fb-chat-" + UUID.randomUUID().toString().substring(0, 8))
                .setSessionId(sessionId)
                .setUserId("user-e2e-fb")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent(userQuestion).build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.7f).setMaxTokens(256).build())
                .build();

        ChatResponse chatResponse = llmStub.chat(chatRequest);
        if (chatResponse.getCode() == 99) {
            llmApiAvailable = false;
            llmApiSkipReason = chatResponse.getMessage();
            Assumptions.assumeTrue(false, "[跳过] 用户反馈端到端 - LLM API 不可用");
        }
        assertEquals(0, chatResponse.getCode());
        System.out.println("[5-4] 大模型回答: " + truncate(chatResponse.getAnswer(), 100));

        // Step 2: 用户给出负面反馈，触发调度
        String eventId = "integ-e2e-fb-" + UUID.randomUUID().toString().substring(0, 8);
        UserFeedbackEvent feedbackEvent = UserFeedbackEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId(sessionId)
                .setUserId("user-e2e-fb")
                .setFeedbackContent("回答不具体，没有说清楚配送范围")
                .setRating(2)
                .setEventTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .addDialogHistory(DialogMessage.newBuilder().setRole("user").setContent(userQuestion).build())
                .addDialogHistory(DialogMessage.newBuilder().setRole("assistant").setContent(chatResponse.getAnswer()).build())
                .build();

        EventResponse feedbackResponse = dispatcherStub.onUserFeedback(feedbackEvent);
        assertEquals(0, feedbackResponse.getCode());
        String taskId = feedbackResponse.getTaskId();
        System.out.println("[5-5] 用户反馈调度任务已创建: " + taskId);

        // Step 3: 查询任务状态确认
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        TaskStatusResponse statusResponse = dispatcherStub.queryTaskStatus(
                TaskStatusRequest.newBuilder().setTaskId(taskId).build());

        assertNotNull(statusResponse);
        assertEquals("USER_FEEDBACK", statusResponse.getTriggerType());
        assertTrue(statusResponse.getStatus() != TaskStatus.TASK_STATUS_UNKNOWN);

        System.out.println("[5-6] 反馈任务状态: " + statusResponse.getStatus()
                + ", retryCount=" + statusResponse.getRetryCount());
    }

    // ======================== 辅助方法 ========================

    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
