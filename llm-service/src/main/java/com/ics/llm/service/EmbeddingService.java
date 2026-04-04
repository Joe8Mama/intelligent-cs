package com.ics.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 向量化服务 - 调用阿里云 DashScope Embedding API 将文本转换为向量
 *
 */
@Slf4j
@Service
public class EmbeddingService {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper;

    @Value("${embedding.api-key}")
    private String apiKey;

    @Value("${embedding.url}")
    private String url;

    @Value("${embedding.model}")
    private String model;

    @Value("${embedding.dimensions}")
    private int dimensions;

    public EmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将单条文本转换为浮点向量
     *
     * @param text 输入文本
     * @return 向量数组 (float32)
     * @throws RuntimeException 向量化失败时抛出
     */
    public List<Float> embedText(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", text,
                    "encoding_format", "float",
                    "dimensions", dimensions
            );

            String json = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    throw new RuntimeException("Embedding API 调用失败, HTTP " + response.code() + ": " + body);
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                JsonNode dataNode = root.path("data");
                if (!dataNode.isArray() || dataNode.isEmpty()) {
                    throw new RuntimeException("Embedding API 返回数据为空");
                }

                JsonNode embedding = dataNode.get(0).path("embedding");
                List<Float> vector = new ArrayList<>(embedding.size());
                embedding.forEach(n -> vector.add((float) n.asDouble()));

                if (vector.isEmpty()) {
                    throw new RuntimeException("Embedding 向量为空");
                }

                log.debug("[Embedding] 向量化成功, 维度={}, 文本长度={}", vector.size(), text.length());
                return vector;
            }
        } catch (IOException e) {
            throw new RuntimeException("Embedding API 请求异常", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("向量化失败", e);
        }
    }

    /**
     * 批量向量化文本列表
     *
     * @param texts 文本列表
     * @return 向量列表 (与输入顺序一一对应)
     */
    public List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", texts,
                    "encoding_format", "float",
                    "dimensions", dimensions
            );

            String json = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "null";
                    throw new RuntimeException("Embedding API 批量调用失败, HTTP " + response.code() + ": " + body);
                }

                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);

                JsonNode dataNode = root.path("data");
                if (!dataNode.isArray() || dataNode.isEmpty()) {
                    throw new RuntimeException("Embedding API 批量返回数据为空");
                }

                // 按 index 排序确保顺序一致
                List<JsonNode> sortedData = new ArrayList<>();
                dataNode.forEach(sortedData::add);
                sortedData.sort(Comparator.comparingInt(n -> n.path("index").asInt()));

                List<List<Float>> vectors = new ArrayList<>(sortedData.size());
                for (JsonNode item : sortedData) {
                    JsonNode embedding = item.path("embedding");
                    List<Float> vector = new ArrayList<>(embedding.size());
                    embedding.forEach(n -> vector.add((float) n.asDouble()));
                    vectors.add(vector);
                }

                log.debug("[Embedding] 批量向量化成功, 数量={}, 维度={}",
                        vectors.size(), vectors.isEmpty() ? 0 : vectors.get(0).size());
                return vectors;
            }
        } catch (IOException e) {
            throw new RuntimeException("Embedding API 批量请求异常", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("批量向量化失败", e);
        }
    }
}
