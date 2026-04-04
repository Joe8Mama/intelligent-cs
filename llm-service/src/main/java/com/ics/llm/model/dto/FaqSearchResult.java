package com.ics.llm.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FAQ 检索结果 DTO
 * RAG 检索返回的单条知识库匹配结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaqSearchResult {

    /** FAQ 记录ID */
    private Long faqId;

    /** 标准问题 */
    private String question;

    /** 标准答案 */
    private String answer;

    /** 分类 */
    private String category;

    /** 相似度分数 (0.0 - 1.0) */
    private Double score;
}
