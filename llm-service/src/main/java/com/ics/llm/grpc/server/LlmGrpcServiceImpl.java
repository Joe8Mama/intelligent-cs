package com.ics.llm.grpc.server;

import com.ics.llm.grpc.proto.*;
import com.ics.llm.model.dto.FaqSearchResult;
import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import com.ics.llm.service.FaqKnowledgeService;
import com.ics.llm.service.LlmApiService;
import com.ics.llm.util.PromptTemplates;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * LLM 接入模块 - gRPC 服务端实现
 * ============================================================================
 * 对外提供统一的 gRPC 接口，封装 LLM API 调用逻辑
 *
 * 服务端口: 50051 (配置在 application.yml 的 grpc.server.port)
 *
 * 提供的 RPC 方法:
 * - Chat: 单轮对话
 * - BatchChat: 批量对话
 * - StreamChat: 流式对话
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class LlmGrpcServiceImpl extends LlmServiceGrpc.LlmServiceImplBase {

    private final LlmApiService llmApiService;
    private final FaqKnowledgeService faqKnowledgeService;
    private final PromptTemplates promptTemplates;

    /**
     * 单轮对话 RPC 实现
     *
     * 处理流程:
     * 1. 参数校验
     * 2. 提取用户最新消息用于 RAG 检索
     * 3. 将 RAG 结果注入 system prompt
     * 4. 调用 LLM API 获取回答
     * 5. 封装响应返回
     */
    @Override
    public void chat(ChatRequest request, StreamObserver<ChatResponse> responseObserver) {
        String requestId = request.getRequestId();
        log.info("[gRPC:Chat] 收到请求, requestId={}, sessionId={}, messageCount={}",
                requestId, request.getSessionId(), request.getMessagesCount());

        try {
            // 1. 参数校验
            if (request.getMessagesCount() == 0) {
                responseObserver.onNext(ChatResponse.newBuilder()
                        .setRequestId(requestId)
                        .setCode(2)
                        .setMessage("消息列表不能为空")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // 2. 提取用户最新消息，进行 RAG 检索
            String userLastMessage = extractLastUserMessage(request.getMessagesList());
            List<FaqSearchResult> ragResults = faqKnowledgeService.searchRelevantFaq(userLastMessage);

            // 3. 构建包含 RAG 上下文的消息列表
            List<LlmApiRequest.Message> messages = buildMessagesWithRag(
                    request.getMessagesList(), ragResults, request.getConfig());

            // 4. 调用 LLM API
            String modelName = request.getConfig().getModelName().isEmpty()
                    ? null : request.getConfig().getModelName();
            Float temperature = request.getConfig().getTemperature() > 0
                    ? request.getConfig().getTemperature() : null;
            Integer maxTokens = request.getConfig().getMaxTokens() > 0
                    ? request.getConfig().getMaxTokens() : null;

            LlmApiResponse apiResponse = llmApiService.chat(messages, modelName, temperature, maxTokens);

            // 5. 封装响应
            String answer = LlmApiService.extractContent(apiResponse);
            UsageInfo.Builder usageBuilder = UsageInfo.newBuilder();
            if (apiResponse.getUsage() != null) {
                usageBuilder.setPromptTokens(apiResponse.getUsage().getPromptTokens())
                        .setCompletionTokens(apiResponse.getUsage().getCompletionTokens())
                        .setTotalTokens(apiResponse.getUsage().getTotalTokens());
            }

            ChatResponse response = ChatResponse.newBuilder()
                    .setRequestId(requestId)
                    .setCode(0)
                    .setMessage("success")
                    .setAnswer(answer)
                    .setUsage(usageBuilder.build())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("[gRPC:Chat] 请求处理完成, requestId={}, answerLength={}", requestId, answer.length());

        } catch (Exception e) {
            log.error("[gRPC:Chat] 请求处理异常, requestId={}, error={}", requestId, e.getMessage(), e);

            responseObserver.onNext(ChatResponse.newBuilder()
                    .setRequestId(requestId)
                    .setCode(99)
                    .setMessage("系统异常: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    /**
     * 批量对话 RPC 实现
     * 逐个处理批量请求中的每个对话
     */
    @Override
    public void batchChat(BatchChatRequest request, StreamObserver<BatchChatResponse> responseObserver) {
        String batchId = request.getBatchId();
        log.info("[gRPC:BatchChat] 收到批量请求, batchId={}, count={}", batchId, request.getRequestsCount());

        List<ChatResponse> responses = new ArrayList<>();
        for (ChatRequest chatRequest : request.getRequestsList()) {
            try {
                String userMessage = extractLastUserMessage(chatRequest.getMessagesList());
                String answer = llmApiService.chatSimple(
                        promptTemplates.getDirectAnswerSystemPrompt(), userMessage);

                responses.add(ChatResponse.newBuilder()
                        .setRequestId(chatRequest.getRequestId())
                        .setCode(0)
                        .setMessage("success")
                        .setAnswer(answer)
                        .build());
            } catch (Exception e) {
                responses.add(ChatResponse.newBuilder()
                        .setRequestId(chatRequest.getRequestId())
                        .setCode(99)
                        .setMessage("处理失败: " + e.getMessage())
                        .build());
            }
        }

        responseObserver.onNext(BatchChatResponse.newBuilder()
                .setBatchId(batchId)
                .setCode(0)
                .setMessage("batch completed")
                .addAllResponses(responses)
                .build());
        responseObserver.onCompleted();

        log.info("[gRPC:BatchChat] 批量请求处理完成, batchId={}, successCount={}",
                batchId, responses.stream().filter(r -> r.getCode() == 0).count());
    }

    /**
     * 流式对话 RPC 实现
     * 当前为模拟实现：将完整回答按句子拆分后逐个发送
     * 生产环境应对接 LLM 的流式 API (SSE)
     */
    @Override
    public void streamChat(ChatRequest request, StreamObserver<ChatStreamResponse> responseObserver) {
        String requestId = request.getRequestId();
        log.info("[gRPC:StreamChat] 收到流式请求, requestId={}", requestId);

        try {
            String userMessage = extractLastUserMessage(request.getMessagesList());
            String fullAnswer = llmApiService.chatSimple(
                    promptTemplates.getDirectAnswerSystemPrompt(), userMessage);

            // 模拟流式输出：按标点符号分段发送
            String[] segments = fullAnswer.split("(?<=[。！？.!?])");
            for (int i = 0; i < segments.length; i++) {
                boolean isLast = (i == segments.length - 1);
                ChatStreamResponse.Builder builder = ChatStreamResponse.newBuilder()
                        .setRequestId(requestId)
                        .setDeltaContent(segments[i])
                        .setIsFinished(isLast);

                responseObserver.onNext(builder.build());
            }

            responseObserver.onCompleted();
            log.info("[gRPC:StreamChat] 流式响应完成, requestId={}, segments={}", requestId, segments.length);

        } catch (Exception e) {
            log.error("[gRPC:StreamChat] 流式请求异常, requestId={}", requestId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("流式对话异常: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    // ============================== 私有辅助方法 ==============================

    /**
     * 提取对话历史中最后一条用户消息
     */
    private String extractLastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).getContent();
    }

    /**
     * 构建包含 RAG 上下文的消息列表
     * 将 FAQ 检索结果注入到 system prompt 中
     */
    private List<LlmApiRequest.Message> buildMessagesWithRag(
            List<ChatMessage> originalMessages,
            List<FaqSearchResult> ragResults,
            ChatConfig config) {

        List<LlmApiRequest.Message> result = new ArrayList<>();

        // 1. 构建包含 RAG 上下文的 system prompt
        String systemPrompt = config.getSystemPrompt().isEmpty()
                ? promptTemplates.getDirectAnswerSystemPrompt()
                : config.getSystemPrompt();

        if (!ragResults.isEmpty()) {
            String ragContext = faqKnowledgeService.formatAsRagContext(ragResults);
            systemPrompt += "\n\n以下是相关知识库内容，请参考回答：\n" + ragContext;
        }

        result.add(LlmApiRequest.Message.builder()
                .role("system")
                .content(systemPrompt)
                .build());

        // 2. 添加对话历史
        for (ChatMessage msg : originalMessages) {
            if (!"system".equals(msg.getRole())) { // 跳过原始 system 消息
                result.add(LlmApiRequest.Message.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build());
            }
        }

        return result;
    }
}
