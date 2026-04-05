package com.ics.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.llm.infra.DispatcherNotifyService;
import com.ics.llm.infra.OssService;
import com.ics.llm.model.dto.CorpusItem;
import com.ics.llm.model.dto.FaqSearchResult;
import com.ics.llm.repository.mapper.SmallModelCorpusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CorpusGenerationService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class CorpusGenerationServiceTest {

    @Mock
    private LlmApiService llmApiService;

    @Mock
    private FaqKnowledgeService faqKnowledgeService;

    @Mock
    private SmallModelCorpusMapper corpusMapper;

    @Mock
    private DispatcherNotifyService dispatcherNotifyService;

    @Mock
    private OssService ossService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CorpusGenerationService corpusGenerationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(corpusGenerationService, "defaultCorpusCount", 5);
        ReflectionTestUtils.setField(corpusGenerationService, "maxCorpusCount", 20);
        ReflectionTestUtils.setField(corpusGenerationService, "minConfidence", 0.6);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("异步生成语料 - 幂等性检查失败（已处理）")
    void testGenerateCorpusAsync_alreadyProcessed() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(false);

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "对话上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(faqKnowledgeService, never()).searchRelevantFaq(anyString());
        verify(llmApiService, never()).chatSimple(anyString(), anyString());
    }

    @Test
    @DisplayName("异步生成语料 - 正常流程")
    void testGenerateCorpusAsync_normal() {
        String taskId = "task-001";
        String dialogContext = "用户: 我想修改密码";
        String llmOutput = "[{\"question\":\"如何修改密码?\",\"answer\":\"请进入设置\",\"category\":\"账号\",\"confidence\":0.9}]";

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of(FaqSearchResult.builder()
                        .faqId(1L)
                        .question("如何修改密码?")
                        .answer("请进入设置页面")
                        .build()));
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn(llmOutput);
        when(ossService.uploadCorpusJsonl(anyString(), any(byte[].class)))
                .thenReturn("corpus/raw/batch_task-001_abc.jsonl");
        when(corpusMapper.insert(any())).thenReturn(1);

        corpusGenerationService.generateCorpusAsync(
                taskId, "session-001", "user-001", dialogContext, "INTENT_FAILED", 5, "localhost:9090");

        verify(faqKnowledgeService).searchRelevantFaq(anyString());
        verify(llmApiService).chatSimple(anyString(), anyString());
        verify(dispatcherNotifyService).notifyCorpusReady(
                eq(taskId), eq("session-001"), anyString(), anyString(), anyInt(), eq(true), anyString());
    }

    @Test
    @DisplayName("异步生成语料 - LLM输出包含markdown代码块")
    void testGenerateCorpusAsync_markdownCodeBlock() {
        String taskId = "task-001";
        String llmOutput = "```json\n[{\"question\":\"问题?\",\"answer\":\"答案\",\"confidence\":0.8}]\n```";

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn(llmOutput);
        when(ossService.uploadCorpusJsonl(anyString(), any(byte[].class)))
                .thenReturn("corpus/raw/batch_task-001.jsonl");
        when(corpusMapper.insert(any())).thenReturn(1);

        corpusGenerationService.generateCorpusAsync(
                taskId, "session-001", "user-001", "上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(dispatcherNotifyService).notifyCorpusReady(
                eq(taskId), eq("session-001"), anyString(), anyString(), anyInt(), eq(true), anyString());
    }

    @Test
    @DisplayName("异步生成语料 - 生成失败后清除幂等标记并通知")
    void testGenerateCorpusAsync_generationFailed() {
        String taskId = "task-001";
        String errorMsg = "LLM API调用失败";

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenThrow(new RuntimeException(errorMsg));

        corpusGenerationService.generateCorpusAsync(
                taskId, "session-001", "user-001", "上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(redisTemplate).delete(contains(taskId));
        verify(dispatcherNotifyService).notifyCorpusReady(
                eq(taskId), eq("session-001"), eq(""), eq(""), eq(0), eq(false), contains(errorMsg));
    }

    @Test
    @DisplayName("异步生成语料 - corpusCount参数规范化")
    void testGenerateCorpusAsync_corpusCountNormalization() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn("[]");

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "上下文", "INTENT_FAILED", 0, "localhost:9090");
        corpusGenerationService.generateCorpusAsync(
                "task-002", "session-001", "user-001", "上下文", "INTENT_FAILED", 100, "localhost:9090");

        verify(llmApiService, times(2)).chatSimple(anyString(), anyString());
    }

    @Test
    @DisplayName("异步生成语料 - 空对话上下文")
    void testGenerateCorpusAsync_emptyDialogContext() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn("[]");

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "", "INTENT_FAILED", 5, "localhost:9090");

        verify(faqKnowledgeService).searchRelevantFaq(eq(""));
    }

    @Test
    @DisplayName("异步生成语料 - 过滤低置信度语料")
    void testGenerateCorpusAsync_filterLowConfidence() {
        String llmOutput = "[{\"question\":\"问题1\",\"answer\":\"答案1\",\"confidence\":0.9}," +
                "{\"question\":\"问题2\",\"answer\":\"答案2\",\"confidence\":0.3}]";

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn(llmOutput);
        when(ossService.uploadCorpusJsonl(anyString(), any(byte[].class)))
                .thenReturn("corpus/raw/batch.jsonl");
        when(corpusMapper.insert(any())).thenReturn(1);

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(corpusMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("异步生成语料 - 过滤空问题和答案")
    void testGenerateCorpusAsync_filterEmptyContent() {
        String llmOutput = "[{\"question\":\"\",\"answer\":\"答案\",\"confidence\":0.9}," +
                "{\"question\":\"问题\",\"answer\":\"\",\"confidence\":0.9}," +
                "{\"question\":\"有效问题\",\"answer\":\"有效答案\",\"confidence\":0.9}]";

        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn(llmOutput);
        when(ossService.uploadCorpusJsonl(anyString(), any(byte[].class)))
                .thenReturn("corpus/raw/batch.jsonl");
        when(corpusMapper.insert(any())).thenReturn(1);

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(corpusMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("异步生成语料 - 无有效语料时通知空结果")
    void testGenerateCorpusAsync_noValidCorpus() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(faqKnowledgeService.searchRelevantFaq(anyString()))
                .thenReturn(List.of());
        when(llmApiService.chatSimple(anyString(), anyString()))
                .thenReturn("[]");

        corpusGenerationService.generateCorpusAsync(
                "task-001", "session-001", "user-001", "上下文", "INTENT_FAILED", 5, "localhost:9090");

        verify(corpusMapper, never()).insert(any());
        verify(dispatcherNotifyService).notifyCorpusReady(
                eq("task-001"), eq("session-001"), eq(""), eq(""), eq(0), eq(true), contains("无有效语料"));
    }
}
