package com.ics.llm.service.provider;

import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;

import java.util.List;

/**
 * ============================================================================
 * LLM 能力接口 - 模型无关的统一抽象
 * ============================================================================
 * 职责: 定义大模型能力的标准接口，屏蔽不同 LLM 提供商的实现差异
 *
 * 实现类:
 * - OpenAICompatibleProvider: 基于 OpenAI 兼容接口的提供商 (通义/硅基流动/DeepSeek 等)
 * - 后续可扩展: BaiduErnieProvider, ZhipuProvider 等
 *
 * 使用方式:
 * 通过 UnifiedLLMService 获取 Provider 实例，上层代码无需关心底层实现
 */
public interface LLMProvider {

    /**
     * 获取提供商唯一标识
     * 用于 Spring Bean 注册和配置路由
     */
    String getProviderName();

    /**
     * 同步聊天
     *
     * @param messages    对话消息列表
     * @param temperature 温度参数 (可为 null，使用默认值)
     * @param maxTokens   最大输出 token 数 (可为 null，使用默认值)
     * @return LLM API 响应
     */
    LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                        Float temperature,
                        Integer maxTokens);

    /**
     * 使用指定模型调用
     *
     * @param messages    对话消息列表
     * @param model       模型名称 (可为 null，使用默认值)
     * @param temperature 温度参数
     * @param maxTokens   最大输出 token 数
     * @return LLM API 响应
     */
    default LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                                String model,
                                Float temperature,
                                Integer maxTokens) {
        // 默认实现忽略 model 参数，具体实现可覆盖
        return chat(messages, temperature, maxTokens);
    }
}
