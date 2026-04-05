package com.ics.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM API 配置属性
 * 从 application.yml 中读取 llm.api.* 配置
 *
 * 支持配置多组 Provider:
 * llm:
 *   api:
 *     default-provider: qwenProvider    # 默认使用的 Provider
 *     base-url: https://api.openai.com/v1
 *     api-key: sk-xxx
 *     model: gpt-4
 *     timeout: 60
 *     max-retries: 3
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm.api")
public class LlmApiConfig {

    /** API 基础地址 */
    private String baseUrl = "https://api.openai.com/v1";

    /** API Key */
    private String apiKey;

    /** 默认模型名称 */
    private String model = "gpt-4";

    /** 请求超时时间（秒） */
    private int timeout = 60;

    /** 最大重试次数 */
    private int maxRetries = 3;

    /** 默认 Provider Bean 名称 */
    private String defaultProvider = "openaiProvider";
}
