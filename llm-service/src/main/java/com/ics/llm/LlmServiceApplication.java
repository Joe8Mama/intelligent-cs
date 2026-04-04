package com.ics.llm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 大模型服务层启动类
 * <p>
 * 职责:
 * 1. LLM接入模块 - 封装大模型API调用，提供统一gRPC接口
 * 2. FAQ知识管理模块 - 对接FAQ知识库，实现RAG检索增强生成
 * 3. 语料生成模块 - 基于对话数据+RAG生成高质量问答语料
 */
@SpringBootApplication
@EnableAsync  // 启用异步支持，语料生成为异步操作
@MapperScan("com.ics.llm.repository.mapper")
public class LlmServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmServiceApplication.class, args);
    }
}
