package com.ics.dispatcher.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息投递记录实体（本地消息表）
 * 对应数据库表: message_outbox
 * 本地消息表模式:
 * 1. 业务操作和消息写入在同一个数据库事务中
 * 2. 定时任务扫描未投递的消息进行补偿发送
 * 3. 保证「至少一次」投递语义
 * 4. 配合接收端的幂等性校验，实现「恰好一次」处理
 */
@Data
@TableName("message_outbox")
public class MessageOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一标识 */
    private String messageId;

    /** 关联任务ID */
    private String taskId;

    /** 目标服务 */
    private String targetService;

    /** 调用方法 */
    private String methodName;

    /** 消息体（JSON） */
    private String payload;

    /** 状态: PENDING/SENT/CONFIRMED/FAILED */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    /** 最近一次错误信息 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ===== 状态常量 =====
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
}
