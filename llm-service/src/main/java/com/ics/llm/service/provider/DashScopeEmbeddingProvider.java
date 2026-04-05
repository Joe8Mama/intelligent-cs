package com.ics.llm.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope Embedding Provider
 *
 * 支持多模态端点 (qwen3-vl-embedding 等)
 * 请求格式:
 * {
 *   "model": "qwen3-vl-embedding",
 *   "input": { "contents": [{"text": "文本"}] },
 *   "parameters": { "dimension": 1024 }
 * }
 *
 * 响应格式:
 * { "output": { "embeddings": [{"embedding": [...], "content_index": 0}] } }
 */
@Slf4j
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String url;
    private final String model;
    private final int dimensions;

    public DashScopeEmbeddingProvider(ObjectMapper objectMapper,
                                     String apiKey, String url,
                                     String model, int dimensions) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.url = url;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String getProviderName() {
        return "dashscope-embedding";
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> requestBody = buildRequest(List.of(text));

            String json = objectMapper.writeValueAsString(requestBody);
            Request request = buildHttpRequest(json);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    throw new RuntimeException("DashScope Embedding API 失败, HTTP " + response.code() + ": " + body);
                }

                List<List<Float>> vectors = parseResponse(response.body().string());
                log.debug("[Embedding] 向量化成功, 维度={}, 文本长度={}", vectors.get(0).size(), text.length());
                return vectors.get(0);
            }
        } catch (IOException e) {
            throw new RuntimeException("DashScope Embedding 请求异常", e);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> requestBody = buildRequest(texts);

            String json = objectMapper.writeValueAsString(requestBody);
            Request request = buildHttpRequest(json);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    throw new RuntimeException("DashScope Embedding 批量调用失败, HTTP " + response.code() + ": " + body);
                }

                List<List<Float>> vectors = parseResponse(response.body().string());
                log.debug("[Embedding] 批量向量化成功, 数量={}, 维度={}",
                        vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).size());
                return vectors;
            }
        } catch (IOException e) {
            throw new RuntimeException("DashScope Embedding 批量请求异常", e);
        }
    }

    /**
     * 构建请求体 (DashScope 原生格式)
     * input.contents: 每个元素可以是 {"text": "..."} 或直接字符串
     */
    private Map<String, Object> buildRequest(List<String> texts) {
        List<Object> contents = new ArrayList<>();
        for (String text : texts) {
            contents.add(Map.of("text", text));
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", Map.of("contents", contents));

        Map<String, Object> params = new HashMap<>();
        params.put("dimension", dimensions);
        requestBody.put("parameters", params);

        return requestBody;
    }

    private Request buildHttpRequest(String json) {
        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .build();
    }

    /**
     * 解析 DashScope 原生响应: output.embeddings[].embedding
     */
    private List<List<Float>> parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode embeddingsNode = root.path("output").path("embeddings");

        if (!embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
            throw new RuntimeException("DashScope Embedding 返回数据为空: " + body);
        }

        List<JsonNode> sorted = new ArrayList<>();
        embeddingsNode.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(n -> n.path("content_index").asInt()));

        List<List<Float>> vectors = new ArrayList<>(sorted.size());
        for (JsonNode item : sorted) {
            JsonNode embeddingNode = item.path("embedding");
            List<Float> vector = new ArrayList<>(embeddingNode.size());
            embeddingNode.forEach(n -> vector.add((float) n.asDouble()));
            if (vector.isEmpty()) {
                throw new RuntimeException("DashScope Embedding 向量为空");
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
