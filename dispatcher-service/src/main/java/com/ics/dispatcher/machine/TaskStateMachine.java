package com.ics.dispatcher.machine;

import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.model.entity.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * 任务状态机 - 管理任务状态流转
 * ============================================================================
 * 职责:
 * 1. 校验状态转换的合法性
 * 2. 执行状态转换并持久化
 * 3. 记录状态变更日志
 * 设计要点:
 * - 所有状态变更必须经过此状态机，确保流转的合法性和可追溯性
 * - 状态枚举定义在 TaskStatus 中，此状态机负责执行流转逻辑
 * - 后续可扩展为基于数据库的持久化状态机（如 Spring Statemachine）
 */
@Slf4j
@Component
public class TaskStateMachine {

    /**
     * 执行状态转换
     *
     * @param task    任务实体（会被直接修改）
     * @param current 当前状态
     * @param target  目标状态
     * @return 转换是否成功
     */
    public boolean transition(DispatchTask task, TaskStatus current, TaskStatus target) {
        if (task == null) {
            log.warn("[状态机] 任务为空，拒绝状态转换");
            return false;
        }

        if (current == null) {
            log.warn("[状态机] 当前状态为空, taskId={}", task.getTaskId());
            return false;
        }

        if (!current.canTransitionTo(target)) {
            log.warn("[状态机] 非法状态转换, taskId={}, {} -> {}",
                    task.getTaskId(), current, target);
            return false;
        }

        task.setStatus(target.getCode());
        log.info("[状态机] 状态转换成功, taskId={}, {} -> {}",
                task.getTaskId(), current, target);
        return true;
    }

    /**
     * 强制设置失败状态（任何状态均可进入 FAILED）
     *
     * @param task         任务实体
     * @param errorMessage 失败原因
     */
    public void fail(DispatchTask task, String errorMessage) {
        TaskStatus previous = TaskStatus.fromCode(task.getStatus());
        task.setStatus(TaskStatus.FAILED.getCode());
        task.setErrorMessage(errorMessage);
        log.info("[状态机] 任务失败, taskId={}, {} -> FAILED, reason={}",
                task.getTaskId(), previous, errorMessage);
    }

    /**
     * 判断任务是否处于终态（不可继续流转）
     */
    public boolean isTerminal(DispatchTask task) {
        TaskStatus status = TaskStatus.fromCode(task.getStatus());
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

}
