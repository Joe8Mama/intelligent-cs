package com.ics.llm.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FAQ 知识库实体
 * 对应数据库表: faq_knowledge
 */
@Data
@TableName("faq_knowledge")
public class FaqKnowledge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标准问题 */
    private String question;

    /** 标准答案 */
    private String answer;

    /** 问题分类 */
    private String category;

    /** 关键词（逗号分隔） */
    private String keywords;

    /** 状态: 1=启用, 0=禁用 */
    private Integer status;

    /** 优先级 */
    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
