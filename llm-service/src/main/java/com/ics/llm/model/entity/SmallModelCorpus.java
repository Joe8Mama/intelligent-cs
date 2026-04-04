package com.ics.llm.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 小模型训练语料实体 (重构: 存储文件元数据，而非具体问答内容)
 * 对应数据库表: small_model_corpus
 */
@Data
@TableName("small_model_corpus")
public class SmallModelCorpus {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联调度任务ID */
    private String taskId;

    /** 批次唯一标识 (等同于 taskId) */
    private String corpusId;

    /** OSS 文件路径 (如 corpus/raw/batch_xxx.jsonl) */
    private String filePath;

    /** 文件 MD5 校验和 */
    private String fileMd5;

    /** 语料行数 (该文件包含多少条对话) */
    private Integer rowCount;

    /** 语料分类 */
    private String category;

    /** 该批次平均置信度 */
    private BigDecimal avgConfidence;

    /** 触发类型 */
    private String triggerType;

    /** 状态: 0=待训练, 1=训练中, 2=已训练 */
    private Integer status;

    /** 错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
