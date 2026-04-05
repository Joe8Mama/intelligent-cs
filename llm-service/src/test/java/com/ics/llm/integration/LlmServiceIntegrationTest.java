package com.ics.llm.integration;

import com.ics.llm.grpc.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大模型服务层 集成测试
 *
 * 前置条件:
 * - llm-service 已启动 (gRPC 端口 50051)
 * - MySQL、Redis 可达
 * - 外部 LLM API (SiliconFlow) 可达 (标记 @NeedsLlmApi 的测试依赖)
 *
 * 测试覆盖的 gRPC 接口:
 * - LlmService: Chat / BatchChat / StreamChat
 * - CorpusGenerationService: GenerateCorpus
 *
 * 测试分层:
 * - 无外部依赖: 参数校验、异常场景
 * - 依赖 LLM API: 单轮对话、多轮对话、流式、批量对话、Usage 统计
 * - 异步流程: 语料生成（仅验证接口受理）
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
class LlmServiceIntegrationTest {

    private static ManagedChannel channel;
    private static LlmServiceGrpc.LlmServiceBlockingStub llmStub;
    private static CorpusGenerationServiceGrpc.CorpusGenerationServiceBlockingStub corpusStub;

    private static final String HOST = "localhost";
    private static final int PORT = 50051;

    /** 外部 LLM API 是否可达，首测失败后标记，后续自动跳过 */
    private static boolean llmApiAvailable = true;
    private static String llmApiSkipReason = null;

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress(HOST, PORT)
                .usePlaintext().build();
        llmStub = LlmServiceGrpc.newBlockingStub(channel);
        corpusStub = CorpusGenerationServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ======================== LLM API 可用性检查 ========================

    /**
     * 检查外部 LLM API 是否可用，不可用时跳过依赖它的测试
     */
    private void assumeLlmApiAvailable(String testName) {
        Assumptions.assumeTrue(llmApiAvailable,
                "[跳过] " + testName + " - LLM API 不可用: " + llmApiSkipReason);
    }

    /**
     * 处理 Chat 响应: LLM API 不可用时 (code=99) 自动标记并 skip
     */
    private void assertChatSuccess(ChatResponse response, String testName) {
        if (response.getCode() == 99) {
            llmApiAvailable = false;
            llmApiSkipReason = response.getMessage();
            Assumptions.assumeTrue(false,
                    "[跳过] " + testName + " - LLM API 调用失败: " + response.getMessage());
        }
        assertEquals(0, response.getCode(),
                testName + " 应成功, 但返回 code=" + response.getCode()
                        + ", message=" + response.getMessage());
    }

    // ======================== 1. Chat 对话测试 ========================

    @Test
    @Order(1)
    @DisplayName("Chat - 单轮简单问答")
    void testChat_simpleQa() {
        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-1-" + UUID.randomUUID())
                .setSessionId("session-chat-test")
                .setUserId("user-test")
                .addMessages(ChatMessage.newBuilder()
                        .setRole("user").setContent("你好，请问你是什么系统？").build())
                .setConfig(ChatConfig.newBuilder()
                        .setTemperature(0.5f).setMaxTokens(256).build())
                .build();

        ChatResponse response = llmStub.chat(request);
        assertChatSuccess(response, "Chat单轮对话");

        assertNotNull(response.getAnswer(), "回答不应为空");
        assertFalse(response.getAnswer().isEmpty(), "回答不应为空字符串");
        assertEquals(0, response.getCode());
        assertEquals("success", response.getMessage());

        System.out.println("[Chat-1] 单轮对话成功, answer="
                + truncate(response.getAnswer(), 100));
    }

    @Test
    @Order(2)
    @DisplayName("Chat - 多轮对话，携带上下文")
    void testChat_multiTurn() {
        assumeLlmApiAvailable("Chat多轮对话");

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-2-" + UUID.randomUUID())
                .setSessionId("session-multi-turn")
                .setUserId("user-multi-turn")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("我要买一台笔记本电脑").build())
                .addMessages(ChatMessage.newBuilder().setRole("assistant").setContent("好的，请问您的预算是多少？").build())
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("5000元左右，推荐一下").build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.7f).setMaxTokens(512).build())
                .build();

        ChatResponse response = llmStub.chat(request);
        assertChatSuccess(response, "Chat多轮对话");
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().length() > 10, "多轮对话回答应有实质内容");

        System.out.println("[Chat-2] 多轮对话成功, answer=" + truncate(response.getAnswer(), 120));
    }

    @Test
    @Order(3)
    @DisplayName("Chat - 自定义 system prompt")
    void testChat_customSystemPrompt() {
        assumeLlmApiAvailable("Chat自定义system prompt");

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-3-" + UUID.randomUUID())
                .setSessionId("session-sys-prompt")
                .setUserId("user-sys-prompt")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("合同违约有什么法律后果？").build())
                .setConfig(ChatConfig.newBuilder()
                        .setSystemPrompt("你是一个专业的法律顾问，只回答法律相关问题，回答要简洁专业。")
                        .setTemperature(0.3f).setMaxTokens(512).build())
                .build();

        ChatResponse response = llmStub.chat(request);
        assertChatSuccess(response, "Chat自定义system prompt");
        assertNotNull(response.getAnswer());

        System.out.println("[Chat-3] 自定义system prompt成功, answer=" + truncate(response.getAnswer(), 120));
    }

    @Test
    @Order(4)
    @DisplayName("Chat - RAG 增强检索 (FAQ 知识库命中场景)")
    void testChat_ragEnhanced() {
        assumeLlmApiAvailable("Chat RAG增强");

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-rag-" + UUID.randomUUID())
                .setSessionId("session-rag")
                .setUserId("user-rag")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("如何重置密码").build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.5f).setMaxTokens(256).build())
                .build();

        ChatResponse response = llmStub.chat(request);
        assertChatSuccess(response, "Chat RAG增强");
        assertNotNull(response.getAnswer());

        System.out.println("[Chat-4] RAG增强检索成功, answer=" + truncate(response.getAnswer(), 150));
    }

    @Test
    @Order(5)
    @DisplayName("Chat - 空 messages 参数校验 (不依赖 LLM API)")
    void testChat_emptyMessages() {
        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-empty")
                .setSessionId("session-empty")
                .setUserId("user-empty")
                .build();

        ChatResponse response = llmStub.chat(request);

        assertEquals(2, response.getCode(), "空 messages 应返回 code=2");
        assertTrue(response.getMessage().contains("消息列表不能为空"));
        System.out.println("[Chat-5] 空消息校验通过: " + response.getMessage());
    }

    @Test
    @Order(6)
    @DisplayName("Chat - 返回 Usage 统计信息")
    void testChat_usageInfo() {
        assumeLlmApiAvailable("Chat Usage统计");

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId("test-chat-usage-" + UUID.randomUUID())
                .setSessionId("session-usage")
                .setUserId("user-usage")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("说一句简短的问候语").build())
                .setConfig(ChatConfig.newBuilder().build())
                .build();

        ChatResponse response = llmStub.chat(request);
        assertChatSuccess(response, "Chat Usage统计");
        assertNotNull(response.getUsage(), "Usage 信息不应为空");
        assertTrue(response.getUsage().getTotalTokens() > 0, "totalTokens 应大于0");

        System.out.println("[Chat-6] Usage: prompt=" + response.getUsage().getPromptTokens()
                + ", completion=" + response.getUsage().getCompletionTokens()
                + ", total=" + response.getUsage().getTotalTokens());
    }

    // ======================== 2. BatchChat 批量对话测试 ========================

    @Test
    @Order(7)
    @DisplayName("BatchChat - 批量处理多条对话")
    void testBatchChat_multipleRequests() {
        assumeLlmApiAvailable("BatchChat批量对话");

        String batchId = "test-batch-" + UUID.randomUUID();

        BatchChatRequest batchRequest = BatchChatRequest.newBuilder()
                .setBatchId(batchId)
                .addRequests(ChatRequest.newBuilder()
                        .setRequestId(batchId + "-1").setSessionId("session-batch").setUserId("user-batch")
                        .addMessages(ChatMessage.newBuilder().setRole("user").setContent("1+1等于几？").build())
                        .setConfig(ChatConfig.newBuilder().build()).build())
                .addRequests(ChatRequest.newBuilder()
                        .setRequestId(batchId + "-2").setSessionId("session-batch").setUserId("user-batch")
                        .addMessages(ChatMessage.newBuilder().setRole("user").setContent("中国的首都是哪里？").build())
                        .setConfig(ChatConfig.newBuilder().build()).build())
                .addRequests(ChatRequest.newBuilder()
                        .setRequestId(batchId + "-3").setSessionId("session-batch").setUserId("user-batch")
                        .addMessages(ChatMessage.newBuilder().setRole("user").setContent("天空为什么是蓝色的？").build())
                        .setConfig(ChatConfig.newBuilder().build()).build())
                .build();

        BatchChatResponse response = llmStub.batchChat(batchRequest);

        assertEquals(0, response.getCode(), "BatchChat 应成功, code=0");
        assertEquals(3, response.getResponsesCount());
        assertEquals(batchId, response.getBatchId());

        int successCount = 0;
        for (int i = 0; i < response.getResponsesCount(); i++) {
            ChatResponse chatResp = response.getResponses(i);
            if (chatResp.getCode() == 0 && chatResp.getAnswer() != null && !chatResp.getAnswer().isEmpty()) {
                successCount++;
                System.out.println("[BatchChat-7] 请求" + (i + 1) + ": " + truncate(chatResp.getAnswer(), 80));
            } else {
                System.out.println("[BatchChat-7] 请求" + (i + 1) + " 失败: code=" + chatResp.getCode());
            }
        }

        assertTrue(successCount > 0, "至少应有1个请求成功");
        System.out.println("[BatchChat-7] 成功 " + successCount + "/3");
    }

    @Test
    @Order(8)
    @DisplayName("BatchChat - 空批次 (不依赖 LLM API)")
    void testBatchChat_emptyBatch() {
        BatchChatRequest batchRequest = BatchChatRequest.newBuilder()
                .setBatchId("test-batch-empty-" + UUID.randomUUID())
                .build();

        BatchChatResponse response = llmStub.batchChat(batchRequest);

        assertEquals(0, response.getCode());
        assertEquals(0, response.getResponsesCount(), "空批次应返回0个响应");
        System.out.println("[BatchChat-8] 空批次处理通过");
    }

    // ======================== 3. StreamChat 流式对话测试 ========================

    @Test
    @Order(9)
    @DisplayName("StreamChat - 流式输出完整对话")
    void testStreamChat_fullResponse() {
        assumeLlmApiAvailable("StreamChat流式对话");

        String requestId = "test-stream-" + UUID.randomUUID();

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId(requestId)
                .setSessionId("session-stream")
                .setUserId("user-stream")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("用三句话介绍人工智能").build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.6f).build())
                .build();

        List<ChatStreamResponse> chunks = new ArrayList<>();
        llmStub.streamChat(request).forEachRemaining(chunks::add);

        assertFalse(chunks.isEmpty(), "流式响应不应为空");

        StringBuilder fullAnswer = new StringBuilder();
        for (ChatStreamResponse chunk : chunks) {
            assertEquals(requestId, chunk.getRequestId(), "每个 chunk 的 requestId 应一致");
            fullAnswer.append(chunk.getDeltaContent());
        }

        assertTrue(fullAnswer.length() > 0, "拼接后的完整回答不应为空");

        ChatStreamResponse lastChunk = chunks.get(chunks.size() - 1);
        assertTrue(lastChunk.getIsFinished(), "最后一个 chunk 应标记 isFinished=true");

        System.out.println("[StreamChat-9] 流式完成, chunks=" + chunks.size()
                + ", 总长度=" + fullAnswer.length()
                + ", 内容=" + truncate(fullAnswer.toString(), 150));
    }

    @Test
    @Order(10)
    @DisplayName("StreamChat - 短回答的流式输出")
    void testStreamChat_shortAnswer() {
        assumeLlmApiAvailable("StreamChat短回答");

        String requestId = "test-stream-short-" + UUID.randomUUID();

        ChatRequest request = ChatRequest.newBuilder()
                .setRequestId(requestId)
                .setSessionId("session-stream-short")
                .setUserId("user-stream-short")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("说一个字：好").build())
                .setConfig(ChatConfig.newBuilder().build())
                .build();

        List<ChatStreamResponse> chunks = new ArrayList<>();
        llmStub.streamChat(request).forEachRemaining(chunks::add);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1, "至少应有1个chunk");
        System.out.println("[StreamChat-10] 短回答流式, chunks=" + chunks.size());
    }

    // ======================== 4. CorpusGeneration 语料生成测试 ========================
    // GenerateCorpus 是异步的，gRPC 接口同步返回「任务已接收」
    // 不直接依赖外部 LLM API (异步生成过程中才调用)

    @Test
    @Order(11)
    @DisplayName("GenerateCorpus - 意图识别失败触发")
    void testGenerateCorpus_intentFailed() {
        String taskId = "test-corpus-intent-" + UUID.randomUUID();

        CorpusGenerationRequest request = CorpusGenerationRequest.newBuilder()
                .setTaskId(taskId)
                .setSessionId("session-corpus-intent")
                .setUserId("user-corpus-intent")
                .setTriggerType(TriggerType.INTENT_RECOGNITION_FAILED)
                .setCorpusCount(5)
                .setCallbackAddress("localhost:50052")
                .addDialogContext(ChatMessage.newBuilder().setRole("user").setContent("如何申请售后服务").build())
                .addDialogContext(ChatMessage.newBuilder().setRole("assistant").setContent("请问您要咨询什么问题？").build())
                .addDialogContext(ChatMessage.newBuilder().setRole("user").setContent("我想申请售后服务，产品有问题").build())
                .build();

        CorpusGenerationResponse response = corpusStub.generateCorpus(request);

        assertEquals(0, response.getCode(), "语料生成请求应被接受, code=" + response.getCode()
                + ", message=" + response.getMessage());
        assertEquals(taskId, response.getTaskId());
        assertTrue(response.getMessage().contains("任务已接收"), "应返回任务已接收");

        System.out.println("[CorpusGen-11] 意图失败触发语料生成成功, taskId=" + taskId);
    }

    @Test
    @Order(12)
    @DisplayName("GenerateCorpus - 用户反馈触发")
    void testGenerateCorpus_userFeedback() {
        String taskId = "test-corpus-feedback-" + UUID.randomUUID();

        CorpusGenerationRequest request = CorpusGenerationRequest.newBuilder()
                .setTaskId(taskId)
                .setSessionId("session-corpus-feedback")
                .setUserId("user-corpus-feedback")
                .setTriggerType(TriggerType.USER_NEGATIVE_FEEDBACK)
                .setCorpusCount(3)
                .setCallbackAddress("localhost:50052")
                .addDialogContext(ChatMessage.newBuilder().setRole("user").setContent("你们的物流多久能到？").build())
                .addDialogContext(ChatMessage.newBuilder().setRole("assistant").setContent("我需要更多信息").build())
                .build();

        CorpusGenerationResponse response = corpusStub.generateCorpus(request);

        assertEquals(0, response.getCode(), "语料生成请求应被接受");
        System.out.println("[CorpusGen-12] 用户反馈触发语料生成成功, taskId=" + taskId);
    }

    @Test
    @Order(13)
    @DisplayName("GenerateCorpus - 参数校验: 空 taskId")
    void testGenerateCorpus_emptyTaskId() {
        CorpusGenerationRequest request = CorpusGenerationRequest.newBuilder()
                .setSessionId("session-test").setUserId("user-test").build();

        CorpusGenerationResponse response = corpusStub.generateCorpus(request);

        assertEquals(2, response.getCode(), "空 taskId 应返回参数错误");
        assertTrue(response.getMessage().contains("task_id"));
        System.out.println("[CorpusGen-13] 空taskId校验: " + response.getMessage());
    }

    @Test
    @Order(14)
    @DisplayName("GenerateCorpus - 参数校验: 空 sessionId")
    void testGenerateCorpus_emptySessionId() {
        CorpusGenerationRequest request = CorpusGenerationRequest.newBuilder()
                .setTaskId("task-test-session").build();

        CorpusGenerationResponse response = corpusStub.generateCorpus(request);

        assertEquals(2, response.getCode(), "空 sessionId 应返回参数错误");
        assertTrue(response.getMessage().contains("session_id"));
        System.out.println("[CorpusGen-14] 空sessionId校验: " + response.getMessage());
    }

    @Test
    @Order(15)
    @DisplayName("GenerateCorpus - 参数校验: 空 dialogContext")
    void testGenerateCorpus_emptyDialogContext() {
        CorpusGenerationRequest request = CorpusGenerationRequest.newBuilder()
                .setTaskId("task-test-dialog")
                .setSessionId("session-test-dialog")
                .setUserId("user-test-dialog")
                .build();

        CorpusGenerationResponse response = corpusStub.generateCorpus(request);

        assertEquals(2, response.getCode(), "空 dialogContext 应返回参数错误");
        assertTrue(response.getMessage().contains("dialog_context"));
        System.out.println("[CorpusGen-15] 空dialogContext校验: " + response.getMessage());
    }

    // ======================== 5. 端到端组合流程 ========================

    @Test
    @Order(16)
    @DisplayName("端到端: 参数校验 + 空批次 + 语料生成受理 (不依赖 LLM API)")
    void testEndToEnd_validationOnly() {
        // Step 1: Chat 参数校验
        ChatResponse chatResponse = llmStub.chat(ChatRequest.newBuilder()
                .setRequestId("e2e-val-" + UUID.randomUUID())
                .setSessionId("session-e2e-val").build());
        assertEquals(2, chatResponse.getCode(), "空消息应被参数校验拦截");
        System.out.println("[E2E-16-Step1] 参数校验: " + chatResponse.getMessage());

        // Step 2: BatchChat 空批次
        BatchChatResponse batchResponse = llmStub.batchChat(BatchChatRequest.newBuilder()
                .setBatchId("e2e-batch-" + UUID.randomUUID()).build());
        assertEquals(0, batchResponse.getCode());
        assertEquals(0, batchResponse.getResponsesCount());
        System.out.println("[E2E-16-Step2] 空批次处理通过");

        // Step 3: 语料生成受理
        String corpusTaskId = "e2e-corpus-" + UUID.randomUUID();
        CorpusGenerationResponse corpusResponse = corpusStub.generateCorpus(
                CorpusGenerationRequest.newBuilder()
                        .setTaskId(corpusTaskId)
                        .setSessionId("session-e2e-corpus")
                        .setTriggerType(TriggerType.USER_NEGATIVE_FEEDBACK)
                        .setCorpusCount(5)
                        .setCallbackAddress("localhost:50052")
                        .addDialogContext(ChatMessage.newBuilder().setRole("user").setContent("退换货政策是什么").build())
                        .addDialogContext(ChatMessage.newBuilder().setRole("assistant").setContent("我不太理解").build())
                        .build());
        assertEquals(0, corpusResponse.getCode());
        System.out.println("[E2E-16-Step3] 语料生成已受理, taskId=" + corpusTaskId);

        // Step 4: 语料生成参数校验
        CorpusGenerationResponse badResponse = corpusStub.generateCorpus(
                CorpusGenerationRequest.newBuilder().build());
        assertEquals(2, badResponse.getCode(), "空请求应被参数校验拦截");
        System.out.println("[E2E-16-Step4] 语料生成参数校验: " + badResponse.getMessage());
    }

    @Test
    @Order(17)
    @DisplayName("端到端: 用户提问 → 大模型回答 + 触发音料生成 (依赖 LLM API)")
    void testEndToEnd_chatAndCorpusGeneration() {
        assumeLlmApiAvailable("端到端完整流程");

        String userQuestion = "你们的退换货政策是什么？我买的东西有质量问题";
        String sessionId = "session-e2e-" + UUID.randomUUID();

        // Step 1: 大模型回答
        ChatRequest chatRequest = ChatRequest.newBuilder()
                .setRequestId("e2e-chat-" + UUID.randomUUID())
                .setSessionId(sessionId)
                .setUserId("user-e2e")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent(userQuestion).build())
                .setConfig(ChatConfig.newBuilder().setTemperature(0.7f).setMaxTokens(512).build())
                .build();

        ChatResponse chatResponse = llmStub.chat(chatRequest);
        assertChatSuccess(chatResponse, "端到端Chat");
        assertNotNull(chatResponse.getAnswer());
        System.out.println("[E2E-17-Step1] 大模型回答: " + truncate(chatResponse.getAnswer(), 150));

        // Step 2: 触发音料生成（模拟调度层调用）
        String corpusTaskId = "e2e-corpus-" + UUID.randomUUID();
        CorpusGenerationRequest corpusRequest = CorpusGenerationRequest.newBuilder()
                .setTaskId(corpusTaskId).setSessionId(sessionId).setUserId("user-e2e")
                .setTriggerType(TriggerType.USER_NEGATIVE_FEEDBACK)
                .setCorpusCount(5).setCallbackAddress("localhost:50052")
                .addDialogContext(ChatMessage.newBuilder().setRole("user").setContent(userQuestion).build())
                .addDialogContext(ChatMessage.newBuilder().setRole("assistant").setContent(chatResponse.getAnswer()).build())
                .build();

        CorpusGenerationResponse corpusResponse = corpusStub.generateCorpus(corpusRequest);
        assertEquals(0, corpusResponse.getCode(), "语料生成应被受理");
        System.out.println("[E2E-17-Step2] 语料生成已触发, taskId=" + corpusTaskId);
    }

    // ======================== 辅助方法 ========================

    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
