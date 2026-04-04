-- ============================================================================
-- 智能语音客服系统 - 数据库初始化脚本
-- ============================================================================

CREATE DATABASE IF NOT EXISTS intelligent_cs DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE intelligent_cs;

-- ============================================================================
-- 1. FAQ 知识库表（大模型服务层 - FAQ知识管理模块使用）
-- ============================================================================
CREATE TABLE IF NOT EXISTS faq_knowledge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    question VARCHAR(500) NOT NULL COMMENT '标准问题',
    answer TEXT NOT NULL COMMENT '标准答案',
    category VARCHAR(100) DEFAULT '' COMMENT '问题分类',
    keywords VARCHAR(500) DEFAULT '' COMMENT '关键词（逗号分隔，用于检索）',
    status TINYINT DEFAULT 1 COMMENT '状态: 1=启用, 0=禁用',
    priority INT DEFAULT 0 COMMENT '优先级（越大越优先）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category (category),
    INDEX idx_status (status),
    FULLTEXT INDEX idx_question_ft (question, keywords) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='FAQ知识库';

-- ============================================================================
-- 2. 小模型语料库表（重构: 存储 OSS 文件元数据，而非具体问答内容）
-- ============================================================================
CREATE TABLE IF NOT EXISTS small_model_corpus (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id VARCHAR(64) NOT NULL COMMENT '关联调度任务ID',
    corpus_id VARCHAR(64) NOT NULL COMMENT '批次唯一标识 (等同于taskId)',
    file_path VARCHAR(500) NOT NULL COMMENT 'OSS文件路径 (如 corpus/raw/batch_xxx.jsonl)',
    file_md5 CHAR(32) NOT NULL COMMENT '文件MD5校验和',
    row_count INT DEFAULT 0 COMMENT '语料行数',
    category VARCHAR(100) DEFAULT '' COMMENT '语料分类',
    avg_confidence DECIMAL(5,2) DEFAULT 0.00 COMMENT '该批次平均置信度',
    trigger_type VARCHAR(50) DEFAULT '' COMMENT '触发类型',
    status TINYINT DEFAULT 0 COMMENT '状态: 0=待训练, 1=训练中, 2=已训练',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX idx_corpus_id (corpus_id),
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_file_path (file_path(255))
) ENGINE=InnoDB COMMENT='小模型训练语料库 (OSS元数据)';

-- ============================================================================
-- 3. 调度任务表（调度服务层使用，记录任务全生命周期）
-- ============================================================================
CREATE TABLE IF NOT EXISTS dispatch_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id VARCHAR(64) NOT NULL UNIQUE COMMENT '任务唯一标识',
    event_id VARCHAR(64) NOT NULL COMMENT '来源事件ID（幂等键）',
    session_id VARCHAR(64) NOT NULL COMMENT '关联会话ID',
    user_id VARCHAR(64) DEFAULT '' COMMENT '用户ID',
    trigger_type VARCHAR(50) NOT NULL COMMENT '触发类型: INTENT_FAILED / USER_FEEDBACK',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    dialog_context TEXT COMMENT '对话上下文（JSON）',
    corpus_count INT DEFAULT 0 COMMENT '生成语料条数',
    corpus_file_path VARCHAR(500) DEFAULT '' COMMENT '语料OSS文件路径',
    training_task_id VARCHAR(64) DEFAULT '' COMMENT '关联训练任务ID',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retries INT DEFAULT 3 COMMENT '最大重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    UNIQUE INDEX idx_event_id (event_id),
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_retry (status, next_retry_time)
) ENGINE=InnoDB COMMENT='调度任务表';

-- ============================================================================
-- 4. 消息投递记录表（本地消息表模式，保证消息可靠性）
-- ============================================================================
CREATE TABLE IF NOT EXISTS message_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    message_id VARCHAR(64) NOT NULL UNIQUE COMMENT '消息唯一标识',
    task_id VARCHAR(64) NOT NULL COMMENT '关联任务ID',
    target_service VARCHAR(100) NOT NULL COMMENT '目标服务',
    method_name VARCHAR(100) NOT NULL COMMENT '调用方法',
    payload TEXT NOT NULL COMMENT '消息体（JSON）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/SENT/CONFIRMED/FAILED',
    retry_count INT DEFAULT 0 COMMENT '已重试次数',
    max_retries INT DEFAULT 3 COMMENT '最大重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    error_message TEXT COMMENT '最近一次错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_time),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB COMMENT='消息投递记录表（本地消息表）';

-- ============================================================================
-- 5. 初始化 FAQ 测试数据
-- ============================================================================
INSERT INTO faq_knowledge (question, answer, category, keywords) VALUES
('如何重置密码', '您可以通过以下步骤重置密码：1. 点击登录页面的"忘记密码"；2. 输入注册邮箱；3. 查收重置邮件并点击链接；4. 设置新密码。', '账号安全', '密码,重置,忘记,修改'),
('退款流程是什么', '退款流程：1. 进入"我的订单"页面；2. 找到需要退款的订单，点击"申请退款"；3. 填写退款原因；4. 等待审核（1-3个工作日）；5. 审核通过后原路退回。', '订单服务', '退款,退货,退钱,取消订单'),
('如何联系人工客服', '您可以通过以下方式联系人工客服：1. 在对话中输入"转人工"；2. 拨打客服热线 400-xxx-xxxx（工作日9:00-18:00）；3. 发送邮件至 support@example.com。', '客服咨询', '人工,客服,电话,联系'),
('配送时间是多久', '标准配送时间为：1. 同城配送：下单后24小时内送达；2. 省内配送：2-3个工作日；3. 跨省配送：3-7个工作日；4. 偏远地区可能需要额外1-3天。', '物流配送', '配送,物流,快递,送货,时间'),
('会员权益有哪些', '会员权益包括：1. 专属折扣（最高8折）；2. 免运费特权；3. 优先客服通道；4. 生日专属礼包；5. 积分加倍；6. 会员专属活动参与权。', '会员服务', '会员,权益,VIP,折扣,积分');
