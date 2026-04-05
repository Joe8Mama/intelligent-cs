package com.ics.llm.util;

import com.ics.llm.model.dto.FaqSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptTemplates 单元测试
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplatesTest {

    private PromptTemplates createWithDefaults() {
        PromptTemplates pt = new PromptTemplates();
        // 模拟 @Value 注入默认值
        ReflectionTestUtils.setField(pt, "corpusGenerationSystemPrompt",
                "你是一个专业的智能客服训练语料生成助手。生成要求：1. 生成问答对 2. 附带分类和置信度。\n输出格式（严格JSON数组）：\n[{\"question\": \"...\", \"answer\": \"...\", \"category\": \"...\", \"confidence\": 0.9}]");
        ReflectionTestUtils.setField(pt, "directAnswerSystemPrompt",
                "你是一个专业、友好的智能客服助手。请根据对话历史和知识库信息，为用户提供准确、有帮助的回答。");
        return pt;
    }

    @Test
    @DisplayName("构建语料生成Prompt - 正常情况")
    void testBuildCorpusGenerationPrompt_normal() {
        PromptTemplates pt = createWithDefaults();
        String dialogContext = "用户: 我想修改密码\n客服: 请问您需要什么帮助?";
        List<FaqSearchResult> ragResults = List.of(
                FaqSearchResult.builder()
                        .faqId(1L)
                        .question("如何修改密码?")
                        .answer("请进入设置页面修改")
                        .category("账号")
                        .score(1.0)
                        .build()
        );
        int corpusCount = 5;

        String prompt = pt.buildCorpusGenerationPrompt(dialogContext, ragResults, corpusCount);

        assertNotNull(prompt);
        assertTrue(prompt.contains("用户对话上下文"));
        assertTrue(prompt.contains(dialogContext));
        assertTrue(prompt.contains("知识库参考内容"));
        assertTrue(prompt.contains("如何修改密码?"));
        assertTrue(prompt.contains("生成 5 条"));
        assertTrue(prompt.contains("JSON数组格式"));
    }

    @Test
    @DisplayName("构建语料生成Prompt - 无RAG结果")
    void testBuildCorpusGenerationPrompt_noRagResults() {
        PromptTemplates pt = createWithDefaults();
        String dialogContext = "用户: 我想修改密码";
        int corpusCount = 3;

        String prompt = pt.buildCorpusGenerationPrompt(dialogContext, null, corpusCount);

        assertNotNull(prompt);
        assertTrue(prompt.contains("用户对话上下文"));
        assertFalse(prompt.contains("知识库参考内容"));
    }

    @Test
    @DisplayName("构建语料生成Prompt - 空RAG结果列表")
    void testBuildCorpusGenerationPrompt_emptyRagResults() {
        PromptTemplates pt = createWithDefaults();
        String dialogContext = "用户: 我想修改密码";

        String prompt = pt.buildCorpusGenerationPrompt(dialogContext, Collections.emptyList(), 3);

        assertNotNull(prompt);
        assertTrue(prompt.contains("用户对话上下文"));
        assertFalse(prompt.contains("知识库参考内容"));
    }

    @Test
    @DisplayName("构建直接回答Prompt - 正常情况")
    void testBuildDirectAnswerPrompt_normal() {
        PromptTemplates pt = createWithDefaults();
        String userQuestion = "如何找回密码?";
        List<FaqSearchResult> ragResults = List.of(
                FaqSearchResult.builder()
                        .faqId(1L)
                        .question("密码找回方法")
                        .answer("通过邮箱找回")
                        .category("账号")
                        .score(0.9)
                        .build()
        );

        String prompt = pt.buildDirectAnswerPrompt(userQuestion, ragResults);

        assertNotNull(prompt);
        assertTrue(prompt.contains("参考知识库内容"));
        assertTrue(prompt.contains("密码找回方法"));
        assertTrue(prompt.contains("通过邮箱找回"));
        assertTrue(prompt.contains("用户问题: " + userQuestion));
    }

    @Test
    @DisplayName("构建直接回答Prompt - 无RAG结果")
    void testBuildDirectAnswerPrompt_noRagResults() {
        PromptTemplates pt = createWithDefaults();
        String userQuestion = "如何找回密码?";

        String prompt = pt.buildDirectAnswerPrompt(userQuestion, null);

        assertNotNull(prompt);
        assertFalse(prompt.contains("参考知识库内容"));
        assertTrue(prompt.contains("用户问题: " + userQuestion));
    }

    @Test
    @DisplayName("System Prompt - 非空且含关键词")
    void testSystemPrompts_notEmpty() {
        PromptTemplates pt = createWithDefaults();

        assertNotNull(pt.getCorpusGenerationSystemPrompt());
        assertNotNull(pt.getDirectAnswerSystemPrompt());

        assertTrue(pt.getCorpusGenerationSystemPrompt().length() > 50);
        assertTrue(pt.getDirectAnswerSystemPrompt().length() > 20);

        assertTrue(pt.getCorpusGenerationSystemPrompt().contains("JSON"));
        assertTrue(pt.getDirectAnswerSystemPrompt().contains("智能客服"));
    }

    @Test
    @DisplayName("构建语料生成Prompt - 多条RAG结果")
    void testBuildCorpusGenerationPrompt_multipleRagResults() {
        PromptTemplates pt = createWithDefaults();
        String dialogContext = "用户咨询账号问题";
        List<FaqSearchResult> ragResults = List.of(
                FaqSearchResult.builder()
                        .faqId(1L)
                        .question("如何修改密码?")
                        .answer("请进入设置页面修改")
                        .category("账号")
                        .build(),
                FaqSearchResult.builder()
                        .faqId(2L)
                        .question("如何注销账号?")
                        .answer("请联系客服")
                        .category("账号")
                        .build(),
                FaqSearchResult.builder()
                        .faqId(3L)
                        .question("账号被盗怎么办?")
                        .answer("请立即联系客服")
                        .category("安全")
                        .build()
        );

        String prompt = pt.buildCorpusGenerationPrompt(dialogContext, ragResults, 10);

        assertTrue(prompt.contains("1. [账号] 问题: 如何修改密码?"));
        assertTrue(prompt.contains("2. [账号] 问题: 如何注销账号?"));
        assertTrue(prompt.contains("3. [安全] 问题: 账号被盗怎么办?"));
    }
}
