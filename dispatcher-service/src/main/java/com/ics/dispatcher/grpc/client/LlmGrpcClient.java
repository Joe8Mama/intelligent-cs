package com.ics.dispatcher.grpc.client;

import com.ics.llm.grpc.proto.*;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * 大模型服务 gRPC 客户端
 * ============================================================================
 * 调度层通过此客户端调用大模型服务层的 gRPC 接口
 *
 * 主要调用:
 * 1. CorpusGenerationService.GenerateCorpus - 触发语料生成
 * 2. LlmService.Chat - 大模型直接回答（意图失败时兜底）
 *
 * 连接配置: grpc.client.llm-service (application.yml)
 */
@Slf4j
@Component
public class LlmGrpcClient {

    /**
     * 语料生成服务 Stub
     * "llm-service" 对应 application.yml 中的 gRPC 客户端配置
     */
    @GrpcClient("llm-service")
    private CorpusGenerationServiceGrpc.CorpusGenerationServiceBlockingStub corpusGenerationStub;

    /**
     * LLM 对话服务 Stub
     */
    @GrpcClient("llm-service")
    private LlmServiceGrpc.LlmServiceBlockingStub llmServiceStub;

    /**
     * 调用大模型语料生成服务
     *
     * 将对话数据发送给大模型服务层，触发异步语料生成流程
     * 语料生成完成后，大模型服务层会回调 DispatcherService.OnCorpusReady
     *
     * @param taskId        任务ID
     * @param sessionId     会话ID
     * @param userId        用户ID
     * @param dialogContext 对话上下文
     * @param triggerType   触发类型
     * @return 是否成功发送
     */
    public boolean callCorpusGeneration(String taskId,
                                        String sessionId,
                                        String userId,
                                        String dialogContext,
                                        String triggerType) {

        log.info("[gRPC客户端→大模型] 调用语料生成, taskId={}, sessionId={}", taskId, sessionId);

        try {
            // 构建对话上下文消息
            // 将 dialogContext 文本解析为 ChatMessage 列表
            CorpusGenerationRequest.Builder requestBuilder = CorpusGenerationRequest.newBuilder()
                    .setTaskId(taskId)
                    .setSessionId(sessionId)
                    .setUserId(userId != null ? userId : "")
                    .setCorpusCount(5)  // 默认生成5条语料
                    .setCallbackAddress("localhost:50052");  // 调度服务回调地址

            // 设置触发类型
            if ("INTENT_FAILED".equals(triggerType)) {
                requestBuilder.setTriggerType(TriggerType.INTENT_RECOGNITION_FAILED);
            } else if ("USER_FEEDBACK".equals(triggerType)) {
                requestBuilder.setTriggerType(TriggerType.USER_NEGATIVE_FEEDBACK);
            }

            // 将对话上下文按行拆分为消息列表
            if (dialogContext != null && !dialogContext.isEmpty()) {
                String[] lines = dialogContext.split("\n");
                for (String line : lines) {
                    String[] parts = line.split(": ", 2);
                    if (parts.length == 2) {
                        requestBuilder.addDialogContext(ChatMessage.newBuilder()
                                .setRole(parts[0].trim())
                                .setContent(parts[1].trim())
                                .build());
                    }
                }
            }

            // 发送 gRPC 请求
            CorpusGenerationResponse response = corpusGenerationStub.generateCorpus(requestBuilder.build());

            if (response.getCode() == 0) {
                log.info("[gRPC客户端→大模型] 语料生成请求已被接收, taskId={}", taskId);
                return true;
            } else {
                log.warn("[gRPC客户端→大模型] 语料生成请求被拒绝, taskId={}, code={}, msg={}",
                        taskId, response.getCode(), response.getMessage());
                return false;
            }

        } catch (StatusRuntimeException e) {
            log.error("[gRPC客户端→大模型] gRPC调用异常, taskId={}, status={}",
                    taskId, e.getStatus(), e);
            throw new RuntimeException("调用大模型语料生成服务失败: " + e.getStatus().getDescription(), e);

        } catch (Exception e) {
            log.error("[gRPC客户端→大模型] 调用异常, taskId={}, error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("调用大模型语料生成服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用大模型直接回答
     *
     * 当小模型意图识别失败时，直接调用大模型生成回答返回给前端
     *
     * @param sessionId 会话ID
     * @param userId    用户ID
     * @param userInput 用户输入
     * @return 大模型的回答
     */
    public String callDirectAnswer(String sessionId, String userId, String userInput) {
        log.info("[gRPC客户端→大模型] 调用直接回答, sessionId={}", sessionId);

        try {
            ChatRequest request = ChatRequest.newBuilder()
                    .setRequestId("direct-" + System.currentTimeMillis())
                    .setSessionId(sessionId)
                    .setUserId(userId)
                    .addMessages(ChatMessage.newBuilder()
                            .setRole("user")
                            .setContent(userInput)
                            .build())
                    .setConfig(ChatConfig.newBuilder()
                            .setTemperature(0.7f)
                            .setMaxTokens(1024)
                            .build())
                    .build();

            ChatResponse response = llmServiceStub.chat(request);

            if (response.getCode() == 0) {
                log.info("[gRPC客户端→大模型] 直接回答成功, sessionId={}, answerLength={}",
                        sessionId, response.getAnswer().length());
                return response.getAnswer();
            } else {
                log.warn("[gRPC客户端→大模型] 直接回答失败, code={}, msg={}",
                        response.getCode(), response.getMessage());
                return "抱歉，当前无法回答您的问题，已为您转接人工客服。";
            }

        } catch (Exception e) {
            log.error("[gRPC客户端→大模型] 直接回答异常, sessionId={}", sessionId, e);
            return "系统繁忙，请稍后再试或联系人工客服。";
        }
    }
}
