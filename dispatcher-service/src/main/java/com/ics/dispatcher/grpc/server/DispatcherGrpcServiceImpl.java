package com.ics.dispatcher.grpc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.dispatcher.grpc.proto.*;
import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.service.DispatchTaskService;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.ZoneOffset;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 调度模块 - gRPC 服务端实现
 * ============================================================================
 * 对外暴露的 gRPC 接口，接收来自不同模块的事件和通知
 *
 * 服务端口: 50052
 *
 * 接口说明:
 * ① OnIntentFailed   - 接收小模型层的「意图识别失败」事件
 * ② OnUserFeedback   - 接收前端的「用户反馈不佳」事件
 * ③ OnCorpusReady    - 接收大模型层的「语料入库完成」通知
 * ④ QueryTaskStatus  - 查询任务状态
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class DispatcherGrpcServiceImpl extends DispatcherServiceGrpc.DispatcherServiceImplBase {

    private final DispatchTaskService dispatchTaskService;
    private final ObjectMapper objectMapper;

    /**
     * ① 接收「意图识别失败」事件
     *
     * 由小模型服务层在识别失败时调用
     * 触发: 异步语料生成 + 大模型直接回答
     */
    @Override
    public void onIntentFailed(IntentFailedEvent request,
                               StreamObserver<EventResponse> responseObserver) {

        String eventId = request.getEventId();
        log.info("[gRPC:OnIntentFailed] 收到意图识别失败事件, eventId={}, sessionId={}, confidence={}",
                eventId, request.getSessionId(), request.getIntentConfidence());

        try {
            // 参数校验
            if (eventId == null || eventId.isEmpty()) {
                respondError(responseObserver, "", 2, "event_id 不能为空");
                return;
            }

            // 将对话历史序列化为 JSON
            String dialogContext;
            try {
                dialogContext = request.getDialogHistoryList().stream()
                        .map(msg -> msg.getRole() + ": " + msg.getContent())
                        .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                dialogContext = "user: " + request.getUserInput();
            }

            // 委托给业务服务处理
            String taskId = dispatchTaskService.handleIntentFailedEvent(
                    eventId,
                    request.getSessionId(),
                    request.getUserId(),
                    dialogContext
            );

            // 构建响应
            EventResponse response = EventResponse.newBuilder()
                    .setEventId(eventId)
                    .setCode(0)
                    .setMessage("事件已接收，调度任务已创建")
                    .setTaskId(taskId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("[gRPC:OnIntentFailed] 事件处理完成, eventId={}, taskId={}", eventId, taskId);

        } catch (Exception e) {
            log.error("[gRPC:OnIntentFailed] 处理异常, eventId={}, error={}", eventId, e.getMessage(), e);
            respondError(responseObserver, eventId, 99, "系统异常: " + e.getMessage());
        }
    }

    /**
     * ② 接收「用户反馈不佳」事件
     *
     * 由前端/网关层在用户给出负面反馈时调用
     * 触发: 异步语料生成
     */
    @Override
    public void onUserFeedback(UserFeedbackEvent request,
                               StreamObserver<EventResponse> responseObserver) {

        String eventId = request.getEventId();
        log.info("[gRPC:OnUserFeedback] 收到用户反馈事件, eventId={}, sessionId={}, rating={}",
                eventId, request.getSessionId(), request.getRating());

        try {
            // 参数校验
            if (eventId == null || eventId.isEmpty()) {
                respondError(responseObserver, "", 2, "event_id 不能为空");
                return;
            }

            // 序列化对话历史
            String dialogContext = request.getDialogHistoryList().stream()
                    .map(msg -> msg.getRole() + ": " + msg.getContent())
                    .collect(Collectors.joining("\n"));

            // 委托给业务服务处理
            String taskId = dispatchTaskService.handleUserFeedbackEvent(
                    eventId,
                    request.getSessionId(),
                    request.getUserId(),
                    request.getFeedbackContent(),
                    dialogContext
            );

            EventResponse response = EventResponse.newBuilder()
                    .setEventId(eventId)
                    .setCode(0)
                    .setMessage("反馈已接收，调度任务已创建")
                    .setTaskId(taskId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("[gRPC:OnUserFeedback] 事件处理完成, eventId={}, taskId={}", eventId, taskId);

        } catch (Exception e) {
            log.error("[gRPC:OnUserFeedback] 处理异常, eventId={}", eventId, e);
            respondError(responseObserver, eventId, 99, "系统异常: " + e.getMessage());
        }
    }

    /**
     * ③ 接收「语料入库完成」通知
     *
     * 由大模型语料生成模块在语料入库后回调
     * 触发: 异步调用小模型训练
     */
    @Override
    public void onCorpusReady(CorpusReadyNotification request,
                              StreamObserver<EventResponse> responseObserver) {

        String taskId = request.getTaskId();
        log.info("[gRPC:OnCorpusReady] 收到语料就绪通知, taskId={}, sessionId={}, filePath={}, rowCount={}, success={}",
                taskId, request.getSessionId(), request.getFilePath(), request.getRowCount(), request.getSuccess());

        try {
            // 参数校验
            if (taskId == null || taskId.isEmpty()) {
                respondError(responseObserver, "", 2, "task_id 不能为空");
                return;
            }

            // 委托给业务服务处理（传递文件路径）
            dispatchTaskService.handleCorpusReadyNotification(
                    taskId,
                    request.getSessionId(),
                    request.getFilePath(),
                    request.getFileMd5(),
                    request.getRowCount(),
                    request.getSuccess(),
                    request.getMessage()
            );

            EventResponse response = EventResponse.newBuilder()
                    .setEventId(taskId)
                    .setCode(0)
                    .setMessage("语料就绪通知已处理")
                    .setTaskId(taskId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            log.info("[gRPC:OnCorpusReady] 通知处理完成, taskId={}", taskId);

        } catch (Exception e) {
            log.error("[gRPC:OnCorpusReady] 处理异常, taskId={}", taskId, e);
            respondError(responseObserver, taskId, 99, "系统异常: " + e.getMessage());
        }
    }

    /**
     * ④ 查询任务状态
     *
     * 供管理后台或其他模块查询调度任务的当前状态
     */
    @Override
    public void queryTaskStatus(TaskStatusRequest request,
                                StreamObserver<TaskStatusResponse> responseObserver) {

        String taskId = request.getTaskId();
        log.info("[gRPC:QueryTaskStatus] 查询任务状态, taskId={}", taskId);

        try {
            DispatchTask task = dispatchTaskService.queryTask(taskId);

            if (task == null) {
                responseObserver.onNext(TaskStatusResponse.newBuilder()
                        .setTaskId(taskId)
                        .setStatus(TaskStatus.TASK_STATUS_UNKNOWN)
                        .setMessage("任务不存在")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // 映射状态
            TaskStatus grpcStatus = mapToGrpcStatus(task.getStatus());

            TaskStatusResponse.Builder responseBuilder = TaskStatusResponse.newBuilder()
                    .setTaskId(task.getTaskId())
                    .setStatus(grpcStatus)
                    .setSessionId(task.getSessionId())
                    .setTriggerType(task.getTriggerType())
                    .setMessage(task.getErrorMessage() != null ? task.getErrorMessage() : "")
                    .setRetryCount(task.getRetryCount());

            if (task.getCreatedAt() != null) {
                responseBuilder.setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(task.getCreatedAt().toEpochSecond(ZoneOffset.of("+8")))
                        .build());
            }
            if (task.getUpdatedAt() != null) {
                responseBuilder.setUpdatedAt(Timestamp.newBuilder()
                        .setSeconds(task.getUpdatedAt().toEpochSecond(ZoneOffset.of("+8")))
                        .build());
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("[gRPC:QueryTaskStatus] 查询异常, taskId={}", taskId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("查询失败: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    // ============================== 辅助方法 ==============================

    /**
     * 映射内部状态到 gRPC 状态枚举
     */
    private TaskStatus mapToGrpcStatus(String internalStatus) {
        if (internalStatus == null) return TaskStatus.TASK_STATUS_UNKNOWN;
        return switch (internalStatus) {
            case "PENDING" -> TaskStatus.TASK_PENDING;
            case "CORPUS_GENERATING" -> TaskStatus.TASK_CORPUS_GENERATING;
            case "CORPUS_STORED" -> TaskStatus.TASK_CORPUS_STORED;
            case "TRAINING" -> TaskStatus.TASK_TRAINING;
            case "COMPLETED" -> TaskStatus.TASK_COMPLETED;
            case "FAILED" -> TaskStatus.TASK_FAILED;
            default -> TaskStatus.TASK_STATUS_UNKNOWN;
        };
    }

    /**
     * 错误响应辅助方法
     */
    private void respondError(StreamObserver<EventResponse> observer,
                              String eventId, int code, String message) {
        observer.onNext(EventResponse.newBuilder()
                .setEventId(eventId)
                .setCode(code)
                .setMessage(message)
                .build());
        observer.onCompleted();
    }
}
