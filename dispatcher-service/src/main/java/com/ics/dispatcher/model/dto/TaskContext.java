package com.ics.dispatcher.model.dto;

import com.ics.dispatcher.model.entity.DispatchTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 任务上下文 - 用于在 Orchestrator 之间传递数据
 * ============================================================================
 * 替代直接传递 String/Map，提供更好的类型安全和扩展性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskContext {
    private String taskId;
    private String eventId;
    private String sessionId;
    private String userId;
    private String triggerType;
    private String content; // 包含对话上下文或反馈内容
    
    /** 任务实体引用，用于编排器直接更新状态 */
    private DispatchTask taskEntity;
}
