package com.ics.llm.grpc.client;

import com.ics.dispatcher.grpc.proto.*;
import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ============================================================================
 * 调度服务 gRPC 客户端 (重构: 传递文件路径)
 * ============================================================================
 * 大模型服务层通过此客户端调用调度服务层的 gRPC 接口
 * 主要用于：语料生成完成后通知调度模块（携带 OSS 文件路径）
 */
@Slf4j
@Component
public class DispatcherGrpcClient {

    @GrpcClient("dispatcher-service")
    private DispatcherServiceGrpc.DispatcherServiceBlockingStub dispatcherStub;

    /**
     * 通知调度模块：语料已入库完成（携带 OSS 文件路径）
     *
     * @param taskId    语料生成任务ID
     * @param sessionId 原始会话ID
     * @param filePath  OSS 文件路径
     * @param fileMd5   文件 MD5
     * @param rowCount  语料行数
     * @param success   是否成功
     * @param message   描述信息
     */
    public void notifyCorpusReady(String taskId,
                                  String sessionId,
                                  String filePath,
                                  String fileMd5,
                                  int rowCount,
                                  boolean success,
                                  String message) {

        log.info("[gRPC客户端] 发送语料就绪通知, taskId={}, path={}, rows={}, success={}",
                taskId, filePath, rowCount, success);

        try {
            Instant now = Instant.now();
            CorpusReadyNotification.Builder builder = CorpusReadyNotification.newBuilder()
                    .setTaskId(taskId)
                    .setSessionId(sessionId)
                    .setSuccess(success)
                    .setMessage(message)
                    .setCompleteTime(Timestamp.newBuilder()
                            .setSeconds(now.getEpochSecond())
                            .setNanos(now.getNano())
                            .build());

            // 设置文件路径信息（成功时携带）
            if (success && filePath != null && !filePath.isEmpty()) {
                builder.setFilePath(filePath);
                if (fileMd5 != null) {
                    builder.setFileMd5(fileMd5);
                }
                builder.setRowCount(rowCount);
            } else {
                builder.setRowCount(rowCount);
            }

            CorpusReadyNotification notification = builder.build();

            EventResponse response = dispatcherStub.onCorpusReady(notification);

            if (response.getCode() == 0) {
                log.info("[gRPC客户端] 通知发送成功, taskId={}, dispatcherTaskId={}",
                        taskId, response.getTaskId());
            } else {
                log.warn("[gRPC客户端] 通知返回非0状态, taskId={}, code={}, msg={}",
                        taskId, response.getCode(), response.getMessage());
            }

        } catch (StatusRuntimeException e) {
            log.error("[gRPC客户端] gRPC调用异常, taskId={}, status={}, description={}",
                    taskId, e.getStatus().getCode(), e.getStatus().getDescription(), e);
            throw new RuntimeException("通知调度服务失败: " + e.getStatus().getDescription(), e);

        } catch (Exception e) {
            log.error("[gRPC客户端] 通知发送异常, taskId={}, error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("通知调度服务失败: " + e.getMessage(), e);
        }
    }
}
