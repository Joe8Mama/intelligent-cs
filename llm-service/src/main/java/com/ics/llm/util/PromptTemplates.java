package com.ics.llm.util;

import com.ics.llm.model.dto.FaqSearchResult;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 模板组件
 * 集中管理所有发送给 LLM 的提示词，支持通过 application.yml 配置自定义
 */
@Setter
@Component
public class PromptTemplates {

    /**
     * 语料生成的 System Prompt
     * 配置项: prompt.corpus-generation
     */
    @Value("${prompt.corpus-generation}")
    private String corpusGenerationSystemPrompt;

    /**
     * 直接回答用户问题的 System Prompt（意图识别失败时大模型兜底回答）
     * 配置项: prompt.direct-answer
     */
    @Value("${prompt.direct-answer}")
    private String directAnswerSystemPrompt;

    public String getCorpusGenerationSystemPrompt() {
        return corpusGenerationSystemPrompt;
    }

    public String getDirectAnswerSystemPrompt() {
        return directAnswerSystemPrompt;
    }

    /**
     * 构建语料生成的用户 Prompt
     *
     * @param dialogContext 对话上下文
     * @param ragResults    RAG 检索到的知识库内容
     * @param corpusCount   期望生成的语料条数
     * @return 完整的用户 prompt
     */
    public String buildCorpusGenerationPrompt(
            String dialogContext,
            List<FaqSearchResult> ragResults,
            int corpusCount) {

        StringBuilder sb = new StringBuilder();

        sb.append("## 用户对话上下文\n");
        sb.append(dialogContext);
        sb.append("\n\n");

        if (ragResults != null && !ragResults.isEmpty()) {
            sb.append("## 知识库参考内容（RAG检索结果）\n");
            for (int i = 0; i < ragResults.size(); i++) {
                FaqSearchResult r = ragResults.get(i);
                sb.append(String.format("%d. [%s] 问题: %s\n   答案: %s\n",
                        i + 1, r.getCategory(), r.getQuestion(), r.getAnswer()));
            }
            sb.append("\n");
        }

        sb.append(String.format("## 任务\n请基于上述对话上下文和知识库内容，生成 %d 条高质量问答训练语料。\n", corpusCount));
        sb.append("严格按照JSON数组格式输出，不要包含任何其他内容。");

        return sb.toString();
    }

    /**
     * 构建直接回答的用户 Prompt（含 RAG 上下文）
     */
    public String buildDirectAnswerPrompt(
            String userQuestion,
            List<FaqSearchResult> ragResults) {

        StringBuilder sb = new StringBuilder();

        if (ragResults != null && !ragResults.isEmpty()) {
            sb.append("参考知识库内容：\n");
            String ragContext = ragResults.stream()
                    .map(r -> String.format("- 问题: %s\n  答案: %s", r.getQuestion(), r.getAnswer()))
                    .collect(Collectors.joining("\n"));
            sb.append(ragContext);
            sb.append("\n\n");
        }

        sb.append("用户问题: ").append(userQuestion);
        return sb.toString();
    }
}
