package com.ics.llm.service;

import com.ics.llm.grpc.client.DispatcherGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ============================================================================
 * 调度服务通知模块 (重构: 传递文件路径)
 * ============================================================================
 * 职责: 封装向调度服务层发送通知的逻辑
 * 在语料生成完成/失败后，通过 gRPC 通知调度模块，携带 OSS 文件路径
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatcherNotifyService {

    private final DispatcherGrpcClient dispatcherGrpcClient;

    /**
     * 通知调度模块：语料已入库（携带 OSS 文件路径）
     *
     * @param taskId    任务ID
     * @param sessionId 会话ID
     * @param filePath  OSS 文件路径
     * @param fileMd5   文件 MD5
     * @param rowCount  语料行数
     * @param success   是否成功
     * @param message   描述信息
     */
    public void notifyCorpusReady(String taskId, String sessionId,
                                  String filePath, String fileMd5, int rowCount,
                                  boolean success, String message) {
        try {
            log.info("[调度通知] 发送语料就绪通知, taskId={}, path={}, rows={}, success={}",
                    taskId, filePath, rowCount, success);
            dispatcherGrpcClient.notifyCorpusReady(taskId, sessionId,
                    filePath, fileMd5, rowCount, success, message);
            log.info("[调度通知] 通知发送成功, taskId={}", taskId);

        } catch (Exception e) {
            log.error("[调度通知] 通知发送失败, taskId={}, error={}", taskId, e.getMessage(), e);
        }
    }
}
