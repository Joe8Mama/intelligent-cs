package com.ics.llm.service;

import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import com.ics.llm.service.provider.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * 统一 LLM 服务 - 模型路由门面
 * ============================================================================
 * 职责:
 * 1. 管理所有已注册的 LLMProvider 实现
 * 2. 根据配置的默认模型或请求指定的模型路由到对应 Provider
 * 3. 提供简化的调用接口（chatSimple），供上层业务直接使用
 *
 * 使用方式:
 * - 调用时指定 model 名称: chat(model, messages, ...)
 * - 或使用默认模型: chat(messages, ...)
 * - 默认模型通过 llm.api.default-provider 配置
 *
 * 设计要点:
 * - Spring 自动注入所有 LLMProvider 实现到 providers Map
 * - Provider 的注册名称即为 Bean 名称
 * - 新增 Provider 只需实现 LLMProvider 接口并注册为 Spring Bean
 */
@Slf4j
@Service
public class UnifiedLLMService {

    /** 所有已注册的 LLM Provider，key 为 Spring Bean 名称 */
    private final Map<String, LLMProvider> providers;

    /** 默认使用的 Provider Bean 名称 */
    @Value("${llm.api.default-provider}")
    private String defaultProvider;

    @Autowired
    public UnifiedLLMService(List<LLMProvider> providerList) {
        this.providers = new HashMap<>();
        for (LLMProvider provider : providerList) {
            providers.put(provider.getProviderName(), provider);
            log.info("[统一LLM] 注册 Provider: {}", provider.getProviderName());
        }
    }

    /**
     * 使用默认 Provider 调用 LLM
     */
    public LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                               Float temperature,
                               Integer maxTokens) {
        return getProvider(defaultProvider).chat(messages, temperature, maxTokens);
    }

    /**
     * 使用指定 Provider 调用 LLM
     */
    public LlmApiResponse chat(String providerName,
                               List<LlmApiRequest.Message> messages,
                               Float temperature,
                               Integer maxTokens) {
        return getProvider(providerName).chat(messages, temperature, maxTokens);
    }

    /**
     * 简化版调用：使用默认 Provider，system + user 两轮对话
     */
    public String chatSimple(String systemPrompt, String userMessage) {
        List<LlmApiRequest.Message> messages = List.of(
                LlmApiRequest.Message.builder().role("system").content(systemPrompt).build(),
                LlmApiRequest.Message.builder().role("user").content(userMessage).build()
        );

        LlmApiResponse response = chat(messages, null, null);
        return extractContent(response);
    }

    /**
     * 简化版调用：指定 Provider
     */
    public String chatSimple(String providerName, String systemPrompt, String userMessage) {
        List<LlmApiRequest.Message> messages = List.of(
                LlmApiRequest.Message.builder().role("system").content(systemPrompt).build(),
                LlmApiRequest.Message.builder().role("user").content(userMessage).build()
        );

        LlmApiResponse response = chat(providerName, messages, null, null);
        return extractContent(response);
    }

    /**
     * 获取 Provider 实例
     */
    private LLMProvider getProvider(String name) {
        LLMProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未找到 LLM Provider: " + name
                    + ", 可用: " + providers.keySet());
        }
        return provider;
    }

    /**
     * 提取 LLM 响应中的文本内容
     */
    public static String extractContent(LlmApiResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "";
        }
        return response.getChoices().get(0).getMessage().getContent();
    }
}
