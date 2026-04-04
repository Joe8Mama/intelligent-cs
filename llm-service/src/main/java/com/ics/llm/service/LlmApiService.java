package com.ics.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ics.llm.config.LlmApiConfig;
import com.ics.llm.model.dto.LlmApiRequest;
import com.ics.llm.model.dto.LlmApiResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * LLM 接入模块 - 核心服务
 * ============================================================================
 * 职责: 封装底层 LLM API (OpenAI兼容接口) 的调用逻辑
 *
 * 设计要点:
 * 1. 统一封装 HTTP 调用，屏蔽不同 LLM 提供商的差异
 * 2. 支持重试机制，应对 API 临时性故障
 * 3. 集中管理 API Key、超时等配置
 * 4. 详细的日志记录，便于追踪和排查问题
 */
@Slf4j
@Service
public class LlmApiService {

    private final LlmApiConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** HTTP 请求的 MediaType */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public LlmApiService(LlmApiConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();

        // 构建 OkHttp 客户端，设置超时和连接池
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }

    /**
     * 调用 LLM API 进行对话
     * 这是整个 LLM 接入模块的核心方法，所有上层调用最终都会走到这里
     *
     * @param messages    对话消息列表（含 system/user/assistant 消息）
     * @param model       模型名称（为空则使用默认模型）
     * @param temperature 温度参数（为空则使用默认值）
     * @param maxTokens   最大输出 token 数（为空则使用默认值）
     * @return LLM API 响应
     */
    public LlmApiResponse chat(List<LlmApiRequest.Message> messages,
                               String model,
                               Float temperature,
                               Integer maxTokens) {
        // 构建请求体
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
                    log.warn("[LLM API] 第 {} 次重试, requestModel={}", retries, request.getModel());
                    // 指数退避: 1s, 2s, 4s...
                    Thread.sleep(1000L * (1L << (retries - 1)));
                }

                LlmApiResponse response = doApiCall(request);

                log.info("[LLM API] 调用成功, model={}, promptTokens={}, completionTokens={}",
                        response.getModel(),
                        response.getUsage() != null ? response.getUsage().getPromptTokens() : "N/A",
                        response.getUsage() != null ? response.getUsage().getCompletionTokens() : "N/A");

                return response;

            } catch (Exception e) {
                lastException = e;
                retries++;
                log.error("[LLM API] 调用失败 (attempt {}/{}): {}",
                        retries, config.getMaxRetries() + 1, e.getMessage());
            }
        }

        // 所有重试都失败
        throw new RuntimeException("LLM API 调用失败，已重试 " + config.getMaxRetries() + " 次", lastException);
    }

    /**
     * 简化版调用：使用默认配置
     */
    public String chatSimple(String systemPrompt, String userMessage) {
        List<LlmApiRequest.Message> messages = List.of(
                LlmApiRequest.Message.builder().role("system").content(systemPrompt).build(),
                LlmApiRequest.Message.builder().role("user").content(userMessage).build()
        );

        LlmApiResponse response = chat(messages, null, null, null);

        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            return response.getChoices().get(0).getMessage().getContent();
        }
        throw new RuntimeException("LLM API 返回空结果");
    }

    /**
     * 执行实际的 HTTP 调用
     * 私有方法，封装 OkHttp 请求构建和响应解析
     */
    private LlmApiResponse doApiCall(LlmApiRequest request) throws IOException {
        // 序列化请求体
        String requestBody = objectMapper.writeValueAsString(request);

        log.debug("[LLM API] 请求体: {}", requestBody);

        // 构建 HTTP 请求
        Request httpRequest = new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        // 执行请求
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("[LLM API] HTTP 错误, code={}, body={}", response.code(), responseBody);
                throw new IOException("LLM API HTTP错误: " + response.code() + ", body: " + responseBody);
            }

            log.debug("[LLM API] 响应体: {}", responseBody);

            return objectMapper.readValue(responseBody, LlmApiResponse.class);
        }
    }

    /**
     * 提取 LLM 响应中的文本内容
     *
     * @param response LLM API 响应
     * @return 文本内容
     */
    public static String extractContent(LlmApiResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return "";
        }
        return response.getChoices().get(0).getMessage().getContent();
    }
}
