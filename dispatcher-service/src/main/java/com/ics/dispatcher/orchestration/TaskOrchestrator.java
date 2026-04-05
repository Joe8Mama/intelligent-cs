package com.ics.dispatcher.orchestration;

import com.ics.dispatcher.model.entity.DispatchTask;

/**
 * 任务编排器接口 - 策略模式实现
 * 用于处理不同类型的调度任务。不同的 triggerType 对应不同的实现类。
 */
public interface TaskOrchestrator {
    /**
     * 获取支持的触发类型 (对应 DispatchTask.triggerType)
     */
    String supportsType();

    /**
     * 执行任务编排逻辑
     * 注意：该方法通常在事务提交后异步执行
     * 
     * @param task 任务实体
     */
    void orchestrate(DispatchTask task);
}
