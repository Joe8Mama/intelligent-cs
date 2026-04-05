package com.ics.llm.service;

import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LlmApiService 单元测试
 * 重构后 LlmApiService 委托给 UnifiedLLMService，测试聚焦于委托逻辑
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmApiServiceTest {

    @Mock
    private UnifiedLLMService unifiedLLMService;

    private LlmApiService llmApiService;

    @BeforeEach
    void setUp() {
        llmApiService = new LlmApiService(unifiedLLMService);
    }

    @Test
    @DisplayName("简化版调用 - 委托给 UnifiedLLMService 成功")
    void testChatSimple_success() {
        String expectedContent = "这是测试回答";
        when(unifiedLLMService.chatSimple("系统提示", "用户问题")).thenReturn(expectedContent);

        String result = llmApiService.chatSimple("系统提示", "用户问题");

        assertEquals(expectedContent, result);
        verify(unifiedLLMService).chatSimple("系统提示", "用户问题");
    }

    @Test
    @DisplayName("简化版调用 - UnifiedLLMService 抛出异常")
    void testChatSimple_exception() {
        when(unifiedLLMService.chatSimple(anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM API 调用失败"));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                llmApiService.chatSimple("系统提示", "用户问题")
        );
        assertTrue(exception.getMessage().contains("LLM API 调用失败"));
    }

    @Test
    @DisplayName("chat 指定模型 - 使用指定 provider 成功")
    void testChat_withSpecificModel_success() {
        LlmApiResponse mockResponse = createMockResponse("指定模型回答");
        when(unifiedLLMService.chat(eq("gpt-4"), anyList(), any(), any()))
                .thenReturn(mockResponse);

        LlmApiResponse result = llmApiService.chat(
                List.of(LlmApiRequest.Message.builder().role("user").content("问题").build()),
                "gpt-4", 0.7f, 1024);

        assertEquals("指定模型回答", result.getChoices().get(0).getMessage().getContent());
        verify(unifiedLLMService).chat(eq("gpt-4"), anyList(), eq(0.7f), eq(1024));
    }

    @Test
    @DisplayName("chat 指定模型不存在 - 回退到默认 provider")
    void testChat_withSpecificModel_fallback() {
        LlmApiResponse mockResponse = createMockResponse("默认模型回答");
        when(unifiedLLMService.chat(eq("nonexistent-model"), anyList(), any(), any()))
                .thenThrow(new IllegalArgumentException("provider not found"));
        when(unifiedLLMService.chat(anyList(), any(), any())).thenReturn(mockResponse);

        LlmApiResponse result = llmApiService.chat(
                List.of(LlmApiRequest.Message.builder().role("user").content("问题").build()),
                "nonexistent-model", 0.7f, 1024);

        assertEquals("默认模型回答", result.getChoices().get(0).getMessage().getContent());
        verify(unifiedLLMService).chat(anyList(), eq(0.7f), eq(1024));
    }

    @Test
    @DisplayName("chat 未指定模型 - 使用默认 provider")
    void testChat_withoutModel() {
        LlmApiResponse mockResponse = createMockResponse("默认回答");
        when(unifiedLLMService.chat(anyList(), any(), any())).thenReturn(mockResponse);

        LlmApiResponse result = llmApiService.chat(
                List.of(LlmApiRequest.Message.builder().role("user").content("问题").build()),
                null, 0.7f, 1024);

        assertEquals("默认回答", result.getChoices().get(0).getMessage().getContent());
        verify(unifiedLLMService).chat(anyList(), eq(0.7f), eq(1024));
    }

    @Test
    @DisplayName("提取响应内容 - 正常情况")
    void testExtractContent_normal() {
        LlmApiResponse response = createMockResponse("测试内容");
        String content = LlmApiService.extractContent(response);
        assertEquals("测试内容", content);
    }

    @Test
    @DisplayName("提取响应内容 - 空响应返回空字符串")
    void testExtractContent_emptyResponse() {
        assertEquals("", LlmApiService.extractContent(null));

        LlmApiResponse emptyResponse = new LlmApiResponse();
        assertEquals("", LlmApiService.extractContent(emptyResponse));

        emptyResponse.setChoices(List.of());
        assertEquals("", LlmApiService.extractContent(emptyResponse));
    }

    private LlmApiResponse createMockResponse(String content) {
        LlmApiResponse response = new LlmApiResponse();
        response.setId("test-id");
        response.setModel("gpt-4");

        LlmApiResponse.Choice choice = new LlmApiResponse.Choice();
        choice.setIndex(0);
        choice.setFinishReason("stop");

        LlmApiResponse.Message message = new LlmApiResponse.Message();
        message.setRole("assistant");
        message.setContent(content);
        choice.setMessage(message);

        response.setChoices(List.of(choice));

        LlmApiResponse.Usage usage = new LlmApiResponse.Usage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        response.setUsage(usage);

        return response;
    }
}
