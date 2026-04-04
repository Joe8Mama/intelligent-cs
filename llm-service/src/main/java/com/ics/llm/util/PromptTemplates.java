package com.ics.llm.util;

import com.ics.llm.model.dto.FaqSearchResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 模板工具类
 * 集中管理所有发送给 LLM 的提示词模板
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * 语料生成的 System Prompt
     * 指导 LLM 基于对话上下文和 RAG 知识库内容生成多条问答语料
     */
    public static final String CORPUS_GENERATION_SYSTEM_PROMPT =
            "你是一个专业的智能客服训练语料生成助手。你的任务是根据给定的用户对话上下文和知识库参考内容，" +
            "生成高质量的问答训练语料。\n\n" +
            "生成要求：\n" +
            "1. 围绕用户对话涉及的主题，拓展生成多种不同表述方式的问答对\n" +
            "2. 问题应涵盖用户可能的不同提问方式（口语化、正式化、简短化等）\n" +
            "3. 答案应准确、完整、口语化，适合客服场景使用\n" +
            "4. 每条语料附带分类标签和置信度评分(0.0-1.0)\n" +
            "5. 确保生成内容与知识库内容一致，不要编造不存在的信息\n\n" +
            "输出格式（严格JSON数组）：\n" +
            "[\n" +
            "  {\"question\": \"...\", \"answer\": \"...\", \"category\": \"...\", \"confidence\": 0.9},\n" +
            "  ...\n" +
            "]";

    /**
     * 构建语料生成的用户 Prompt
     *
     * @param dialogContext 对话上下文
     * @param ragResults    RAG 检索到的知识库内容
     * @param corpusCount   期望生成的语料条数
     * @return 完整的用户 prompt
     */
    public static String buildCorpusGenerationPrompt(
            String dialogContext,
            List<FaqSearchResult> ragResults,
            int corpusCount) {

        StringBuilder sb = new StringBuilder();

        // 对话上下文部分
        sb.append("## 用户对话上下文\n");
        sb.append(dialogContext);
        sb.append("\n\n");

        // RAG 知识库参考内容
        if (ragResults != null && !ragResults.isEmpty()) {
            sb.append("## 知识库参考内容（RAG检索结果）\n");
            for (int i = 0; i < ragResults.size(); i++) {
                FaqSearchResult r = ragResults.get(i);
                sb.append(String.format("%d. [%s] 问题: %s\n   答案: %s\n",
                        i + 1, r.getCategory(), r.getQuestion(), r.getAnswer()));
            }
            sb.append("\n");
        }

        // 生成指令
        sb.append(String.format("## 任务\n请基于上述对话上下文和知识库内容，生成 %d 条高质量问答训练语料。\n", corpusCount));
        sb.append("严格按照JSON数组格式输出，不要包含任何其他内容。");

        return sb.toString();
    }

    /**
     * 直接回答用户问题的 System Prompt（用于意图识别失败时大模型兜底回答）
     */
    public static final String DIRECT_ANSWER_SYSTEM_PROMPT =
            "你是一个专业、友好的智能客服助手。请根据对话历史和知识库信息，为用户提供准确、有帮助的回答。" +
            "如果不确定答案，请诚实告知并建议用户转人工客服。回答应简洁、口语化。";

    /**
     * 构建直接回答的用户 Prompt（含 RAG 上下文）
     */
    public static String buildDirectAnswerPrompt(
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
