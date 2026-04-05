package com.ics.dispatcher.service;

import com.ics.dispatcher.model.entity.DispatchTask;
import com.ics.dispatcher.orchestration.OrchestratorFactory;
import com.ics.dispatcher.orchestration.impl.CorpusGenOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ============================================================================
 * 异步调度服务 (重构: 委托给编排器)
 * ============================================================================
 * 原职责: 将 @Async 方法从 DispatchTaskService 中抽离
 * 重构后: 委托给 CorpusGenOrchestrator 执行编排逻辑
 *
 * 此类保留是为了向后兼容已有测试代码
 * 新代码建议直接使用 OrchestratorFactory 获取编排器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncDispatchService {

    private final OrchestratorFactory orchestratorFactory;
    private final CorpusGenOrchestrator corpusGenOrchestrator;

    /**
     * 调用语料生成 — 委托给 CORPUS_GEN 编排器
     */
    public void asyncCallCorpusGeneration(DispatchTask task) {
        orchestratorFactory.getOrchestrator("CORPUS_GEN").orchestrate(task);
    }

    /**
     * 调用小模型训练 — 委托给 CorpusGenOrchestrator
     */
    public void asyncCallSmallModelTraining(String taskId, String filePath, int rowCount) {
        corpusGenOrchestrator.handleCorpusReady(taskId, filePath, rowCount);
    }
}
