package com.ics.llm.service;

import com.ics.llm.model.dto.FaqSearchResult;
import com.ics.llm.model.entity.FaqKnowledge;
import com.ics.llm.repository.mapper.FaqKnowledgeMapper;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FaqKnowledgeService 单元测试
 *
 * 默认 vectorEnabled=false，测试 MySQL 降级检索路径
 * 向量检索路径的测试通过设置 vectorEnabled=true 来覆盖
 */
@ExtendWith(MockitoExtension.class)
class FaqKnowledgeServiceTest {

    @Mock
    private FaqKnowledgeMapper faqKnowledgeMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorStoreService vectorStoreService;

    @InjectMocks
    private FaqKnowledgeService faqKnowledgeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(faqKnowledgeService, "maxResults", 5);
        ReflectionTestUtils.setField(faqKnowledgeService, "minScore", 0.3);
        ReflectionTestUtils.setField(faqKnowledgeService, "vectorEnabled", false);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== MySQL 降级检索测试 ====================

    @Test
    @DisplayName("MySQL检索 - 全文检索正常返回结果")
    void testSearchRelevantFaq_mysqlNormal() {
        // Given
        String query = "如何修改密码";
        FaqKnowledge faq = createFaq(1L, "如何修改密码?", "请进入设置页面修改", "账号");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("如何修改密码?", results.get(0).getQuestion());
        verify(valueOperations).set(anyString(), anyList(), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("MySQL检索 - 全文检索无结果降级为模糊检索")
    void testSearchRelevantFaq_fallbackToFuzzySearch() {
        // Given
        String query = "如何修改密码";
        FaqKnowledge faq = createFaq(1L, "密码找回方法", "通过邮箱找回", "账号");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(Collections.emptyList());
        when(faqKnowledgeMapper.fuzzySearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(faqKnowledgeMapper).fuzzySearch(query, 5);
    }

    // ==================== 向量语义检索测试 ====================

    @Test
    @DisplayName("向量检索 - Qdrant 可用时使用向量搜索")
    void testSearchRelevantFaq_vectorSearch() {
        // Given - 启用向量检索
        ReflectionTestUtils.setField(faqKnowledgeService, "vectorEnabled", true);
        when(vectorStoreService.isInitialized()).thenReturn(true);

        String query = "我要退钱";
        FaqKnowledge faq = createFaq(1L, "退款流程是什么", "退款流程如下...", "订单服务");

        // Mock Embedding 返回
        List<Float> mockVector = createMockVector(1024);
        when(embeddingService.embedText(eq(query))).thenReturn(mockVector);

        // Mock Qdrant 返回
        ScoredPoint scoredPoint = mock(ScoredPoint.class);
        when(scoredPoint.getId()).thenReturn(
                io.qdrant.client.grpc.Points.PointId.newBuilder().setNum(1L).build());
        when(scoredPoint.getScore()).thenReturn(0.92f);
        when(vectorStoreService.searchSimilar(eq(mockVector), eq(5)))
                .thenReturn(List.of(scoredPoint));

        // Mock MySQL 回查
        when(faqKnowledgeMapper.selectBatchIds(eq(List.of(1L)))).thenReturn(List.of(faq));
        when(valueOperations.get(anyString())).thenReturn(null);

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("退款流程是什么", results.get(0).getQuestion());
        assertEquals(0.92, results.get(0).getScore(), 0.001);
        // 验证走的是向量检索路径，没有调用 MySQL 全文检索
        verify(faqKnowledgeMapper, never()).fullTextSearch(anyString(), anyInt());
    }

    @Test
    @DisplayName("向量检索 - Qdrant 无结果时降级为 MySQL")
    void testSearchRelevantFaq_vectorNoResultsFallback() {
        // Given
        ReflectionTestUtils.setField(faqKnowledgeService, "vectorEnabled", true);
        when(vectorStoreService.isInitialized()).thenReturn(true);

        String query = "我要退钱";
        FaqKnowledge faq = createFaq(1L, "退款流程是什么", "退款流程如下...", "订单服务");

        List<Float> mockVector = createMockVector(1024);
        when(embeddingService.embedText(eq(query))).thenReturn(mockVector);
        when(vectorStoreService.searchSimilar(eq(mockVector), eq(5)))
                .thenReturn(Collections.emptyList());
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));
        when(valueOperations.get(anyString())).thenReturn(null);

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(faqKnowledgeMapper).fullTextSearch(query, 5);
    }

    @Test
    @DisplayName("向量检索 - Qdrant 未初始化时降级为 MySQL")
    void testSearchRelevantFaq_qdrantNotInitialized() {
        // Given
        ReflectionTestUtils.setField(faqKnowledgeService, "vectorEnabled", true);
        when(vectorStoreService.isInitialized()).thenReturn(false);

        String query = "我要退钱";
        FaqKnowledge faq = createFaq(1L, "退款流程是什么", "退款流程如下...", "订单服务");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(faqKnowledgeMapper).fullTextSearch(query, 5);
        verify(embeddingService, never()).embedText(anyString());
    }

    @Test
    @DisplayName("向量检索 - Embedding 异常时降级为 MySQL")
    void testSearchRelevantFaq_embeddingExceptionFallback() {
        // Given
        ReflectionTestUtils.setField(faqKnowledgeService, "vectorEnabled", true);
        when(vectorStoreService.isInitialized()).thenReturn(true);

        String query = "我要退钱";
        FaqKnowledge faq = createFaq(1L, "退款流程是什么", "退款流程如下...", "订单服务");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(embeddingService.embedText(eq(query)))
                .thenThrow(new RuntimeException("DashScope API 超时"));
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then - 降级到 MySQL
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(faqKnowledgeMapper).fullTextSearch(query, 5);
    }

    // ==================== 通用测试 ====================

    @Test
    @DisplayName("检索 - 命中缓存直接返回")
    void testSearchRelevantFaq_cacheHit() {
        // Given
        String query = "如何修改密码";
        List<FaqSearchResult> cachedResults = List.of(
                FaqSearchResult.builder()
                        .faqId(1L)
                        .question("如何修改密码?")
                        .answer("请进入设置页面修改")
                        .category("账号")
                        .score(1.0)
                        .build()
        );

        when(valueOperations.get(anyString())).thenReturn(cachedResults);

        // When
        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(faqKnowledgeMapper, never()).fullTextSearch(anyString(), anyInt());
    }

    @Test
    @DisplayName("检索 - 空查询返回空列表")
    void testSearchRelevantFaq_emptyQuery() {
        List<FaqSearchResult> results1 = faqKnowledgeService.searchRelevantFaq(null);
        List<FaqSearchResult> results2 = faqKnowledgeService.searchRelevantFaq("");
        List<FaqSearchResult> results3 = faqKnowledgeService.searchRelevantFaq("   ");

        assertTrue(results1.isEmpty());
        assertTrue(results2.isEmpty());
        assertTrue(results3.isEmpty());
    }

    @Test
    @DisplayName("检索 - 数据库异常返回空列表")
    void testSearchRelevantFaq_databaseException() {
        String query = "测试查询";
        when(valueOperations.get(anyString())).thenReturn(null);
        when(faqKnowledgeMapper.fullTextSearch(anyString(), anyInt()))
                .thenThrow(new RuntimeException("Database error"));

        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("检索 - 缓存写入异常不影响结果")
    void testSearchRelevantFaq_cacheWriteException() {
        String query = "如何修改密码";
        FaqKnowledge faq = createFaq(1L, "如何修改密码?", "请进入设置页面修改", "账号");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(faqKnowledgeMapper.fullTextSearch(eq(query), eq(5)))
                .thenReturn(List.of(faq));
        doThrow(new RuntimeException("Redis error"))
                .when(valueOperations).set(anyString(), anyList(), anyLong(), any());

        List<FaqSearchResult> results = faqKnowledgeService.searchRelevantFaq(query);

        assertNotNull(results);
        assertEquals(1, results.size());
    }

    // ==================== FAQ 同步测试 ====================

    @Test
    @DisplayName("同步FAQ到向量库 - 正常同步")
    void testSyncFaqToVector_normal() {
        // Given
        FaqKnowledge faq = createFaq(1L, "如何重置密码", "请点击忘记密码...", "账号安全");
        List<Float> mockVector = createMockVector(1024);
        when(embeddingService.embedText(anyString())).thenReturn(mockVector);

        // When
        faqKnowledgeService.syncFaqToVector(faq);

        // Then
        verify(embeddingService).embedText(eq("如何重置密码 请点击忘记密码..."));
        verify(vectorStoreService).upsertFaqVector(eq(1L), eq(mockVector), anyMap());
    }

    @Test
    @DisplayName("同步FAQ到向量库 - Embedding异常不抛出")
    void testSyncFaqToVector_embeddingFailure() {
        FaqKnowledge faq = createFaq(1L, "如何重置密码", "请点击忘记密码...", "账号安全");
        when(embeddingService.embedText(anyString()))
                .thenThrow(new RuntimeException("API 超时"));

        // Should not throw
        assertDoesNotThrow(() -> faqKnowledgeService.syncFaqToVector(faq));
    }

    @Test
    @DisplayName("批量同步 - Qdrant未初始化时跳过")
    void testSyncAllFaqToVector_qdrantNotInitialized() {
        when(vectorStoreService.isInitialized()).thenReturn(false);

        int result = faqKnowledgeService.syncAllFaqToVector();

        assertEquals(0, result);
        verify(faqKnowledgeMapper, never()).selectList(any());
    }

    // ==================== RAG 上下文格式化测试 ====================

    @Test
    @DisplayName("格式化RAG上下文 - 正常情况")
    void testFormatAsRagContext_normal() {
        List<FaqSearchResult> results = List.of(
                FaqSearchResult.builder()
                        .faqId(1L)
                        .question("如何修改密码?")
                        .answer("请进入设置页面修改")
                        .category("账号")
                        .score(1.0)
                        .build(),
                FaqSearchResult.builder()
                        .faqId(2L)
                        .question("如何注销账号?")
                        .answer("请联系客服")
                        .category("账号")
                        .score(0.9)
                        .build()
        );

        String context = faqKnowledgeService.formatAsRagContext(results);

        assertNotNull(context);
        assertTrue(context.contains("【知识1】"));
        assertTrue(context.contains("【知识2】"));
        assertTrue(context.contains("如何修改密码?"));
        assertTrue(context.contains("请进入设置页面修改"));
    }

    @Test
    @DisplayName("格式化RAG上下文 - 空列表")
    void testFormatAsRagContext_emptyList() {
        String context1 = faqKnowledgeService.formatAsRagContext(null);
        String context2 = faqKnowledgeService.formatAsRagContext(Collections.emptyList());

        assertEquals("未找到相关知识库内容。", context1);
        assertEquals("未找到相关知识库内容。", context2);
    }

    // ==================== 辅助方法 ====================

    private FaqKnowledge createFaq(Long id, String question, String answer, String category) {
        FaqKnowledge faq = new FaqKnowledge();
        faq.setId(id);
        faq.setQuestion(question);
        faq.setAnswer(answer);
        faq.setCategory(category);
        return faq;
    }

    private List<Float> createMockVector(int dimension) {
        List<Float> vector = new java.util.ArrayList<>(dimension);
        for (int i = 0; i < dimension; i++) {
            vector.add((float) (Math.random() * 2 - 1));
        }
        return vector;
    }
}
