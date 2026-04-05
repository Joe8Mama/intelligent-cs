package com.ics.dispatcher.orchestration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * 编排器工厂 - 根据任务类型路由到对应的编排器实现
 * ============================================================================
 * 职责:
 * 1. 收集所有 TaskOrchestrator 实现类
 * 2. 根据 triggerType 路由到对应的编排器
 * 3. 支持 Spring 自动注入发现机制
 * 扩展方式:
 * 新增任务类型时，只需创建新的 TaskOrchestrator 实现并加上 @Component 注解，
 * 工厂会自动发现并注册，无需修改此工厂代码 (符合开闭原则)
 */
@Component
public class OrchestratorFactory {

    private final Map<String, TaskOrchestrator> orchestratorMap;

    /**
     * 通过 Spring 依赖注入自动收集所有 TaskOrchestrator 实现
     */
    @Autowired
    public OrchestratorFactory(List<TaskOrchestrator> orchestrators) {
        this.orchestratorMap = orchestrators.stream()
                .collect(Collectors.toMap(
                        TaskOrchestrator::supportsType,
                        Function.identity(),
                        (existing, replacement) -> {
                            throw new IllegalStateException(
                                    "存在重复的编排器类型: " + existing.supportsType());
                        }
                ));
    }

    /**
     * 根据触发类型获取对应的编排器
     *
     * @param triggerType 触发类型 (对应 DispatchTask.triggerType)
     * @return 对应的编排器实现
     * @throws IllegalArgumentException 如果没有找到支持的编排器
     */
    public TaskOrchestrator getOrchestrator(String triggerType) {
        TaskOrchestrator orchestrator = orchestratorMap.get(triggerType);
        if (orchestrator == null) {
            throw new IllegalArgumentException("未找到支持类型 [" + triggerType + "] 的编排器");
        }
        return orchestrator;
    }

    /**
     * 判断是否存在指定类型的编排器
     */
    public boolean hasOrchestrator(String triggerType) {
        return orchestratorMap.containsKey(triggerType);
    }

    /**
     * 获取所有已注册的编排器类型
     */
    public List<String> getSupportedTypes() {
        return List.copyOf(orchestratorMap.keySet());
    }
}
