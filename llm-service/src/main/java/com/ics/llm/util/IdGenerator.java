package com.ics.llm.util;

import java.util.UUID;

/**
 * ID 生成工具类
 * 生成全局唯一标识符，用于语料ID、请求ID等场景
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    /**
     * 生成带前缀的唯一ID
     *
     * @param prefix 前缀标识，如 "corpus", "task"
     * @return 格式: prefix-xxxxxxxx
     */
    public static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 生成语料ID
     */
    public static String corpusId() {
        return generateId("corpus");
    }

    /**
     * 生成请求ID
     */
    public static String requestId() {
        return generateId("req");
    }
}
