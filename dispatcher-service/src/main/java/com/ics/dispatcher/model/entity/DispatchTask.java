package com.ics.dispatcher.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调度任务实体
 * 对应数据库表: dispatch_task
 *
 * 记录每个调度任务的完整生命周期:
 * PENDING → CORPUS_GENERATING → CORPUS_STORED → TRAINING → COMPLETED
 *                                                        → FAILED
 */
@Data
@TableName("dispatch_task")
public class DispatchTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识 */
    private String taskId;

    /** 来源事件ID（幂等键） */
    private String eventId;

    /** 关联会话ID */
    private String sessionId;

    /** 用户ID */
    private String userId;

    /** 触发类型: INTENT_FAILED / USER_FEEDBACK */
    private String triggerType;

    /** 任务状态 */
    private String status;

    /** 对话上下文（JSON） */
    private String dialogContext;

    /** 生成语料条数 */
    private Integer corpusCount;

    /** 语料 OSS 文件路径 (如 corpus/raw/batch_xxx.jsonl) */
    private String corpusFilePath;

    /** 关联训练任务ID */
    private String trainingTaskId;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    /** 错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    // ===== 任务状态常量 =====
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CORPUS_GENERATING = "CORPUS_GENERATING";
    public static final String STATUS_CORPUS_STORED = "CORPUS_STORED";
    public static final String STATUS_TRAINING = "TRAINING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}
