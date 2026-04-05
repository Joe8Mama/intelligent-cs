package com.ics.llm.service;

import com.ics.llm.grpc.client.DispatcherGrpcClient;
import com.ics.llm.infra.DispatcherNotifyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DispatcherNotifyService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class DispatcherNotifyServiceTest {

    @Mock
    private DispatcherGrpcClient dispatcherGrpcClient;

    @InjectMocks
    private DispatcherNotifyService dispatcherNotifyService;

    @Test
    @DisplayName("通知语料就绪 - 正常情况")
    void testNotifyCorpusReady_normal() {
        // When
        dispatcherNotifyService.notifyCorpusReady("task-001", "session-001",
                "corpus/raw/batch_001.jsonl", "abc123md5", 10, true, "语料生成并入库完成");

        // Then
        verify(dispatcherGrpcClient).notifyCorpusReady(
                eq("task-001"), eq("session-001"), eq("corpus/raw/batch_001.jsonl"),
                eq("abc123md5"), eq(10), eq(true), eq("语料生成并入库完成"));
    }

    @Test
    @DisplayName("通知语料就绪 - gRPC调用异常不影响流程")
    void testNotifyCorpusReady_grpcException() {
        doThrow(new RuntimeException("gRPC调用失败"))
                .when(dispatcherGrpcClient).notifyCorpusReady(
                        anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyString());

        // When - 不应抛出异常
        dispatcherNotifyService.notifyCorpusReady("task-001", "session-001",
                "path", "md5", 5, true, "完成");

        // Then
        verify(dispatcherGrpcClient).notifyCorpusReady(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("通知语料生成失败 - 调用gRPC并标记success=false")
    void testNotifyCorpusReady_failed() {
        // When
        dispatcherNotifyService.notifyCorpusReady("task-001", "session-001",
                "", "", 0, false, "LLM API调用超时");

        // Then
        verify(dispatcherGrpcClient).notifyCorpusReady(
                eq("task-001"), eq("session-001"), eq(""),
                eq(""), eq(0), eq(false), eq("LLM API调用超时"));
    }

    @Test
    @DisplayName("通知语料就绪 - 空文件路径")
    void testNotifyCorpusReady_emptyFilePath() {
        dispatcherNotifyService.notifyCorpusReady("task-001", "session-001",
                "", "", 0, true, "无有效语料生成");

        verify(dispatcherGrpcClient).notifyCorpusReady(
                eq("task-001"), eq("session-001"), eq(""),
                eq(""), eq(0), eq(true), eq("无有效语料生成"));
    }
}
