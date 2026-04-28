package com.ics.llm.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 生成的单条语料 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorpusItem {

    /** 语料唯一ID */
    private String corpusId;

    /** 问题 */
    private String question;

    /** 答案 */
    private String answer;

    /** 分类 */
    private String category;

    /** 意图 */
    private String intent;

    /** 生成置信度 */
    private BigDecimal confidence;
}
