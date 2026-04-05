package com.ics.llm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdGenerator 单元测试
 */
class IdGeneratorTest {

    @Test
    @DisplayName("生成带前缀的唯一ID - 正常情况")
    void testGenerateId_withPrefix() {
        String id = IdGenerator.generateId("test");
        
        assertNotNull(id);
        assertTrue(id.startsWith("test-"));
        assertEquals(21, id.length()); // "test-" (5) + 16位UUID
    }

    @Test
    @DisplayName("生成带前缀的唯一ID - 不同调用返回不同ID")
    void testGenerateId_uniqueness() {
        String id1 = IdGenerator.generateId("test");
        String id2 = IdGenerator.generateId("test");
        
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("生成带前缀的唯一ID - 空前缀")
    void testGenerateId_emptyPrefix() {
        String id = IdGenerator.generateId("");
        
        assertNotNull(id);
        assertTrue(id.startsWith("-"));
    }

    @Test
    @DisplayName("生成语料ID")
    void testCorpusId() {
        String corpusId = IdGenerator.corpusId();
        
        assertNotNull(corpusId);
        assertTrue(corpusId.startsWith("corpus-"));
        assertEquals(23, corpusId.length()); // "corpus-" (7) + 16位UUID
    }

    @Test
    @DisplayName("生成请求ID")
    void testRequestId() {
        String requestId = IdGenerator.requestId();
        
        assertNotNull(requestId);
        assertTrue(requestId.startsWith("req-"));
        assertEquals(20, requestId.length()); // "req-" (4) + 16位UUID
    }

    @Test
    @DisplayName("生成语料ID - 唯一性")
    void testCorpusId_uniqueness() {
        String id1 = IdGenerator.corpusId();
        String id2 = IdGenerator.corpusId();
        
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("生成请求ID - 唯一性")
    void testRequestId_uniqueness() {
        String id1 = IdGenerator.requestId();
        String id2 = IdGenerator.requestId();
        
        assertNotEquals(id1, id2);
    }
}
