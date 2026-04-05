package com.ics.llm.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.llm.config.LlmApiConfig;
import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * OpenAI 兼容接口 Provider
 * ============================================================================
 * 适用于所有实现了 OpenAI Chat Completions API 格式的提供商:
 * - OpenAI (GPT-4, GPT-3.5)
 * - 通义千问 (DashScope)
 * - 硅基流动 (SiliconFlow)
 * - DeepSeek
 * - 智谱 (GLM)
 * - Moonshot (Kimi)
 *
 * 设计要点:
 * - 通过 LlmApiConfig 中的 baseUrl/apiKey/model 配置切换不同提供商
 * - 所有使用 OpenAI 兼容接口的提供商共享此实现，避免代码重复
 * - 内置指数退避重试机制
 */
@Slf4j
public class OpenAICompatibleProvider implements LLMProvider {

    private final String providerName;
    private final LlmApiConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public OpenAICompatibleProvider(String providerName, LlmApiConfig config) {
        this.providerName = providerName;
        this.config = config;
        this.objectMapper = new ObjectMapper();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                               Float temperature,
                               Integer maxTokens) {
        return chat(messages, config.getModel(), temperature, maxTokens);
    }

    @Override
    public LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                               String model,
                               Float temperature,
                               Integer maxTokens) {
        LlmApiRequest request = LlmApiRequest.builder()
                .model(model != null ? model : config.getModel())
                .messages(messages)
                .temperature(temperature != null ? temperature : 0.7f)
                .maxTokens(maxTokens != null ? maxTokens : 2048)
                .build();

        // 带重试的 API 调用
        int retries = 0;
        Exception lastException = null;

        while (retries <= config.getMaxRetries()) {
            try {
                if (retries > 0) {
                    log.warn("[LLM:{}] 第 {} 次重试, model={}", providerName, retries, request.getModel());
                    Thread.sleep(1000L * (1L << (retries - 1)));
                }

                LlmApiResponse response = doApiCall(request);

                log.info("[LLM:{}] 调用成功, model={}, promptTokens={}, completionTokens={}",
                        providerName,
                        response.getModel(),
                        response.getUsage() != null ? response.getUsage().getPromptTokens() : "N/A",
                        response.getUsage() != null ? response.getUsage().getCompletionTokens() : "N/A");

                return response;

            } catch (Exception e) {
                lastException = e;
                retries++;
                log.error("[LLM:{}] 调用失败 (attempt {}/{}): {}",
                        providerName, retries, config.getMaxRetries() + 1, e.getMessage());
            }
        }

        throw new RuntimeException("LLM [" + providerName + "] 调用失败，已重试 " + config.getMaxRetries() + " 次",
                lastException);
    }

    private LlmApiResponse doApiCall(LlmApiRequest request) throws IOException {
        String requestBody = objectMapper.writeValueAsString(request);
        log.debug("[LLM:{}] 请求体: {}", providerName, requestBody);

        Request httpRequest = new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("[LLM:{}] HTTP 错误, code={}, body={}", providerName, response.code(), responseBody);
                throw new IOException("LLM [" + providerName + "] HTTP错误: " + response.code() + ", body: " + responseBody);
            }

            log.debug("[LLM:{}] 响应体: {}", providerName, responseBody);
            return objectMapper.readValue(responseBody, LlmApiResponse.class);
        }
    }
}
