package com.ics.llm.config;

import com.ics.llm.service.provider.LLMProvider;
import com.ics.llm.service.provider.OpenAICompatibleProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================================
 * LLM Provider 自动配置
 * ============================================================================
 * 根据 application.yml 配置自动注册 LLM Provider
 *
 * 扩展方式:
 * 新增 Provider 时:
 * 1. 实现 LLMProvider 接口
 * 2. 在此类中添加 @Bean 方法（或创建独立的 @Configuration 类）
 * 3. 在 application.yml 中配置 llm.api.default-provider 指向新 Provider
 *
 * 当前内置:
 * - openaiProvider: OpenAI 兼容接口 (通义/硅基流动/DeepSeek/智谱等)
 */
@Slf4j
@Configuration
public class ProviderAutoConfiguration {

    /**
     * 注册默认的 OpenAI 兼容 Provider
     * 条件: llm.api.api-key 不为空
     *
     * 这个 Provider 覆盖所有实现了 OpenAI Chat Completions API 格式的 LLM 服务商，
     * 包括: OpenAI, 通义千问(DashScope), 硅基流动(SiliconFlow), DeepSeek, 智谱(GLM), Moonshot 等
     */
    @Bean(name = "openaiProvider")
    @ConditionalOnProperty(prefix = "llm.api", name = "api-key")
    public LLMProvider openaiProvider(LlmApiConfig config) {
        log.info("[Provider配置] 注册 OpenAI 兼容 Provider, baseUrl={}, model={}",
                config.getBaseUrl(), config.getModel());
        return new OpenAICompatibleProvider("openaiProvider", config);
    }
}
