package com.ics.dispatcher.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ics.dispatcher.model.entity.DispatchTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度任务 Mapper
 */
@Mapper
public interface DispatchTaskMapper extends BaseMapper<DispatchTask> {

    /**
     * 根据事件ID查询任务（幂等性检查）
     */
    @Select("SELECT * FROM dispatch_task WHERE event_id = #{eventId} LIMIT 1")
    DispatchTask selectByEventId(@Param("eventId") String eventId);

    /**
     * 根据任务ID查询
     */
    @Select("SELECT * FROM dispatch_task WHERE task_id = #{taskId} LIMIT 1")
    DispatchTask selectByTaskId(@Param("taskId") String taskId);

    /**
     * 查询需要重试的任务
     * 条件: 状态为失败/进行中 且 已达到重试时间 且 未超过最大重试次数
     */
    @Select("SELECT * FROM dispatch_task " +
            "WHERE status IN ('CORPUS_GENERATING', 'TRAINING') " +
            "AND retry_count < max_retries " +
            "AND next_retry_time IS NOT NULL " +
            "AND next_retry_time <= #{now} " +
            "ORDER BY next_retry_time ASC " +
            "LIMIT 50")
    List<DispatchTask> selectRetryableTasks(@Param("now") LocalDateTime now);

    /**
     * 更新任务状态
     */
    @Update("UPDATE dispatch_task SET status = #{status}, updated_at = NOW() " +
            "WHERE task_id = #{taskId}")
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);
}
