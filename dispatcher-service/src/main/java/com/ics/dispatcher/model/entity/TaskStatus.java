package com.ics.dispatcher.model.entity;

import lombok.Getter;

/**
 * ============================================================================
 * 任务状态枚举 - 状态机核心定义
 * ============================================================================
 * 定义调度任务的完整生命周期状态流转:
 * PENDING → CORPUS_GENERATING → CORPUS_STORED → TRAINING → COMPLETED
 *                                                            ↘ FAILED
 * 任何阶段均可因异常转为 FAILED，FAILED 可通过重试回到对应阶段
 */
@Getter
public enum TaskStatus {

    /** 待处理 - 任务已创建，等待编排器分配执行 */
    PENDING("PENDING", "待处理"),

    /** 语料生成中 - 已调用大模型服务进行语料生成 */
    CORPUS_GENERATING("CORPUS_GENERATING", "语料生成中"),

    /** 语料已入库 - 语料文件已上传 OSS，元数据已保存 */
    CORPUS_STORED("CORPUS_STORED", "语料已入库"),

    /** 小模型训练中 - 已触发小模型训练 */
    TRAINING("TRAINING", "小模型训练中"),

    /** 全流程完成 */
    COMPLETED("COMPLETED", "已完成"),

    /** 任务失败 - 重试次数耗尽或不可恢复的错误 */
    FAILED("FAILED", "失败");

    private final String code;
    private final String description;

    TaskStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 从数据库存储的状态字符串解析为枚举
     */
    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return PENDING;
        }
        for (TaskStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return PENDING;
    }

    /**
     * 判断当前状态是否允许转换到目标状态
     */
    public boolean canTransitionTo(TaskStatus target) {
        return switch (this) {
            case PENDING -> target == CORPUS_GENERATING || target == FAILED;
            case CORPUS_GENERATING -> target == CORPUS_STORED || target == FAILED;
            case CORPUS_STORED -> target == TRAINING || target == FAILED;
            case TRAINING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false; // 终态不可流转
        };
    }
}
