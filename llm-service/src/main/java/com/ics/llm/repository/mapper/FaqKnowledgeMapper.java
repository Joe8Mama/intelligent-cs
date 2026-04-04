package com.ics.llm.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ics.llm.model.entity.FaqKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * FAQ 知识库 Mapper
 */
@Mapper
public interface FaqKnowledgeMapper extends BaseMapper<FaqKnowledge> {

    /**
     * 全文检索 FAQ
     * 使用 MySQL FULLTEXT 索引进行自然语言匹配
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回条数
     * @return 匹配的 FAQ 列表（按相关性排序）
     */
    @Select("SELECT *, " +
            "MATCH(question, keywords) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) AS score " +
            "FROM faq_knowledge " +
            "WHERE status = 1 " +
            "AND MATCH(question, keywords) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) " +
            "ORDER BY score DESC, priority DESC " +
            "LIMIT #{limit}")
    List<FaqKnowledge> fullTextSearch(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 基于关键词的模糊检索（全文检索的降级方案）
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回条数
     * @return 匹配的 FAQ 列表
     */
    @Select("SELECT * FROM faq_knowledge " +
            "WHERE status = 1 " +
            "AND (question LIKE CONCAT('%', #{keyword}, '%') " +
            "     OR keywords LIKE CONCAT('%', #{keyword}, '%')) " +
            "ORDER BY priority DESC " +
            "LIMIT #{limit}")
    List<FaqKnowledge> fuzzySearch(@Param("keyword") String keyword, @Param("limit") int limit);
}
