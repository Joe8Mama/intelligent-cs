package com.ics.dispatcher;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度服务层启动类
 * <p>
 * 职责:
 * 1. 接收「意图识别失败」和「用户反馈不佳」事件
 * 2. 异步调用大模型语料生成服务
 * 3. 接收「语料入库完成」通知，触发小模型训练
 * 4. 管理调度任务全生命周期（创建→语料生成→训练→完成）
 * 5. 保证消息可靠性（重试、幂等、补偿）
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling  // 启用定时任务（重试补偿机制）
@MapperScan("com.ics.dispatcher.repository.mapper")
public class DispatcherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatcherServiceApplication.class, args);
    }
}
