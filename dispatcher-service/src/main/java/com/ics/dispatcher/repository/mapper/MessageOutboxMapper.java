package com.ics.dispatcher.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ics.dispatcher.model.entity.MessageOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息投递记录 Mapper（本地消息表）
 */
@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutbox> {

    /**
     * 查询待投递/需要重试的消息
     */
    @Select("SELECT * FROM message_outbox " +
            "WHERE status IN ('PENDING', 'SENT') " +
            "AND retry_count < max_retries " +
            "AND (next_retry_time IS NULL OR next_retry_time <= #{now}) " +
            "ORDER BY created_at ASC " +
            "LIMIT 100")
    List<MessageOutbox> selectPendingMessages(@Param("now") LocalDateTime now);
}
