package com.ics.llm.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM API 请求 DTO
 * 兼容 OpenAI Chat Completions API 格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmApiRequest {

    /** 模型名称 */
    private String model;

    /** 对话消息列表 */
    private List<Message> messages;

    /** 温度参数 */
    private Float temperature;

    /** 最大输出 token 数 */
    private Integer maxTokens;

    /** Top-P 采样 */
    private Float topP;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }
}
