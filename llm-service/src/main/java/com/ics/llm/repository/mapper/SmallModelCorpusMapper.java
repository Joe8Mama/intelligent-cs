package com.ics.llm.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ics.llm.model.entity.SmallModelCorpus;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小模型语料库 Mapper
 */
@Mapper
public interface SmallModelCorpusMapper extends BaseMapper<SmallModelCorpus> {
}
