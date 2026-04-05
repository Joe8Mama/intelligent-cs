package com.ics.llm.service;

import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================================================
 * LLM 接入模块 - 核心服务 (重构: 委托 UnifiedLLMService)
 * ============================================================================
 * 职责: 对外提供 LLM 调用能力的统一入口
 *
 * 重构说明:
 * - 原 LlmApiService 直接使用 OkHttp 调用 OpenAI 兼容接口
 * - 重构后委托给 UnifiedLLMService，由其路由到具体的 LLMProvider
 * - 保持原有的方法签名不变，确保上层调用方无需修改
 * - 具体的 HTTP 调用逻辑已迁移到 OpenAICompatibleProvider
 *
 * 兼容性:
 * 此类作为向后兼容的 Facade，新代码建议直接使用 UnifiedLLMService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmApiService {

    private final UnifiedLLMService unifiedLLMService;

    /**
     * 调用 LLM API 进行对话
     *
     * @param messages    对话消息列表
     * @param model       模型名称（为空则使用默认模型）
     * @param temperature 温度参数
     * @param maxTokens   最大输出 token 数
     * @return LLM API 响应
     */
    public LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                               String model,
                               Float temperature,
                               Integer maxTokens) {
        if (model != null && !model.isEmpty()) {
            // 如果指定了具体模型名，尝试使用对应 provider
            try {
                return unifiedLLMService.chat(model, messages, temperature, maxTokens);
            } catch (IllegalArgumentException e) {
                // provider 不存在时回退到默认
                log.warn("[LLM API] 指定模型 provider [{}] 不存在, 回退默认", model);
            }
        }
        return unifiedLLMService.chat(messages, temperature, maxTokens);
    }

    /**
     * 简化版调用：使用默认配置
     */
    public String chatSimple(String systemPrompt, String userMessage) {
        return unifiedLLMService.chatSimple(systemPrompt, userMessage);
    }

    /**
     * 提取 LLM 响应中的文本内容
     */
    public static String extractContent(LlmApiResponse response) {
        return UnifiedLLMService.extractContent(response);
    }
}
