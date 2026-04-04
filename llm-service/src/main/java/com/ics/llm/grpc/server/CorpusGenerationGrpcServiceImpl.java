package com.ics.llm.grpc.server;

import com.ics.llm.grpc.proto.*;
import com.ics.llm.service.CorpusGenerationService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.stream.Collectors;

/**
 * ============================================================================
 * 语料生成模块 - gRPC 服务端实现
 * ============================================================================
 * 接收调度模块发来的语料生成请求，触发异步语料生成流程
 *
 * 关键设计:
 * - RPC 方法同步返回「任务已接收」状态，实际生成在后台异步执行
 * - 生成完成后通过 gRPC 回调通知调度模块
 * - 支持幂等性：相同 task_id 不会重复处理
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class CorpusGenerationGrpcServiceImpl
        extends CorpusGenerationServiceGrpc.CorpusGenerationServiceImplBase {

    private final CorpusGenerationService corpusGenerationService;

    /**
     * 生成语料 RPC 实现
     *
     * 处理流程:
     * 1. 参数校验
     * 2. 同步返回「任务已接收」响应（快速响应，不阻塞调度端）
     * 3. 异步执行语料生成流程（RAG检索 → LLM生成 → 入库 → 通知）
     *
     * 注意: 此方法设计为「接收即返回」模式，实际语料生成在后台线程池异步执行
     */
    @Override
    public void generateCorpus(CorpusGenerationRequest request,
                               StreamObserver<CorpusGenerationResponse> responseObserver) {

        String taskId = request.getTaskId();
        String sessionId = request.getSessionId();

        log.info("[gRPC:GenerateCorpus] 收到语料生成请求, taskId={}, sessionId={}, triggerType={}, corpusCount={}",
                taskId, sessionId, request.getTriggerType(), request.getCorpusCount());

        try {
            // ===== 参数校验 =====
            if (taskId == null || taskId.isEmpty()) {
                respondError(responseObserver, "", 2, "task_id 不能为空");
                return;
            }
            if (sessionId == null || sessionId.isEmpty()) {
                respondError(responseObserver, taskId, 2, "session_id 不能为空");
                return;
            }
            if (request.getDialogContextCount() == 0) {
                respondError(responseObserver, taskId, 2, "dialog_context 不能为空");
                return;
            }

            // ===== 将 Proto 对话上下文转为文本 =====
            String dialogContext = request.getDialogContextList().stream()
                    .map(msg -> msg.getRole() + ": " + msg.getContent())
                    .collect(Collectors.joining("\n"));

            // ===== 同步返回「任务已接收」=====
            // 关键: 这里立即返回，告知调度端任务已被接受
            CorpusGenerationResponse response = CorpusGenerationResponse.newBuilder()
                    .setTaskId(taskId)
                    .setCode(0)
                    .setMessage("任务已接收，正在异步生成语料")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("[gRPC:GenerateCorpus] 已返回接收确认, taskId={}", taskId);

            // ===== 异步触发语料生成 =====
            // @Async 注解确保在独立线程池中执行，不阻塞 gRPC 线程
            corpusGenerationService.generateCorpusAsync(
                    taskId,
                    sessionId,
                    request.getUserId(),
                    dialogContext,
                    request.getTriggerType().name(),
                    request.getCorpusCount(),
                    request.getCallbackAddress()
            );

        } catch (Exception e) {
            log.error("[gRPC:GenerateCorpus] 请求处理异常, taskId={}, error={}", taskId, e.getMessage(), e);
            respondError(responseObserver, taskId, 99, "系统异常: " + e.getMessage());
        }
    }

    /**
     * 错误响应辅助方法
     */
    private void respondError(StreamObserver<CorpusGenerationResponse> observer,
                              String taskId, int code, String message) {
        log.warn("[gRPC:GenerateCorpus] 返回错误, taskId={}, code={}, message={}", taskId, code, message);
        observer.onNext(CorpusGenerationResponse.newBuilder()
                .setTaskId(taskId)
                .setCode(code)
                .setMessage(message)
                .build());
        observer.onCompleted();
    }
}
