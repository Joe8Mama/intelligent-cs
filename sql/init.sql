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
    vector_synced TINYINT DEFAULT 0 COMMENT '向量同步状态: 0=未同步, 1=已同步',
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
-- 5. 金融领域 FAQ 知识库数据（初始态）
-- ============================================================================
INSERT INTO faq_knowledge (question, answer, category, keywords, status, priority, vector_synced) VALUES
('如何开通网上银行?', '您可以通过以下方式开通网上银行：1.携带本人有效身份证件和银行卡，前往我行任意网点柜台办理；2.登录我行手机银行APP，在"设置-网上银行"中自助开通；3.拨打客服热线95588，通过人工客服协助开通。开通后请及时设置网银登录密码和交易密码，并绑定手机号以便接收验证码。', '网银服务', '网上银行,开通,注册,激活', 1, 90, 0),
('信用卡遗失了怎么办?', '如果您的信用卡遗失，请立即采取以下措施：1.拨打我行24小时客服热线95588，选择信用卡挂失服务进行临时挂失；2.临时挂失有效期为15天，期间卡片无法使用；3.请您尽快携带有效身份证件前往就近网点办理正式挂失和补卡手续。补卡费用一般为20元/张，新卡将在7-15个工作日内寄送到您的预留地址。挂失前产生的交易需由您自行承担，建议同时开启短信提醒功能以便及时发现异常。', '信用卡', '信用卡,挂失,遗失,补卡,丢失', 1, 85, 0),
('贷款利率是多少?', '我行贷款利率根据贷款类型、期限和个人信用状况有所不同，具体如下：1.个人住房贷款：首套房LPR-0.2%（当前约3.95%），二套房LPR+0.6%（当前约4.75%）；2.个人消费贷款：年化利率3.6%起，具体利率以审批结果为准；3.个人经营贷款：年化利率3.85%起；4.公积金贷款：5年以下2.6%，5年以上3.1%。实际利率以贷款审批时的利率政策和您的个人资质为准，建议您前往网点咨询或通过手机银行进行贷款预评估。', '贷款业务', '贷款,利率,房贷,消费贷,经营贷,LPR', 1, 80, 0),
('定期存款提前支取会有损失吗?', '定期存款提前支取会有利息损失，具体规则如下：1.提前支取时，支取部分将按活期存款利率计息（目前为0.2%），而非原定期利率；2.部分提前支取：每笔定期存款最多可办理一次部分提前支取，剩余金额仍按原存期和利率计息；3.全额提前支取：全部金额按活期利率计息。建议您在存款前合理规划资金使用，也可以考虑大额存单或结构性存款等灵活性更高的产品。如确需用款，可先咨询我行理财经理了解是否有低利率的贷款产品可替代。', '存款业务', '定期存款,提前支取,活期利率,利息损失', 1, 75, 0),
('手机银行转账有额度限制吗?', '手机银行转账设有不同的额度限制：1.默认限额：单笔5万元，单日累计20万元；2.动态限额：使用U盾/蓝牙U盾认证后，单笔可达100万元，单日累计500万元；3.便捷支付限额：仅短信验证方式，单笔5000元，单日累计1万元；4.免密支付限额：单笔300元，单日累计1000元。您可以根据实际需求在手机银行"设置-安全中心-转账限额"中自行调整限额。如需进一步提高限额，请携带有效证件前往柜台办理。', '转账汇款', '手机银行,转账,限额,额度,U盾', 1, 70, 0),
('如何申请个人住房贷款?', '申请个人住房贷款需要准备以下材料和满足相应条件：一、所需材料：1.借款人及配偶有效身份证件、户口本、婚姻证明；2.收入证明（近6个月工资流水或纳税证明）；3.购房合同或意向协议；4.首付款凭证（首套房不低于20%，二套房不低于30%）；5.个人征信报告授权书。二、申请条件：1.年龄在18-65周岁之间；2.有稳定的收入来源，月收入不低于月供的2倍；3.个人信用良好，无严重逾期记录。您可通过手机银行提交预审申请，或携带材料前往任意网点办理。审批一般在5-15个工作日内完成。', '贷款业务', '房贷,住房贷款,申请材料,贷款条件,首付', 1, 85, 0),
('银行理财产品有风险吗?', '银行理财产品具有一定的风险，不同风险等级对应不同的投资方向和预期收益。我行理财产品风险等级分为五级：R1（低风险）：主要投资国债、存款等，本金风险极小，预期收益2%-3%；R2（中低风险）：以债券类资产为主，本金出现亏损的可能性较小，预期收益3%-4%；R3（中等风险）：可配置部分权益类资产，存在一定本金亏损可能，预期收益4%-6%；R4（中高风险）：权益类资产占比较高，本金亏损可能性较大，预期收益6%-10%；R5（高风险）：主要投资股票、衍生品等，本金可能遭受较大损失。温馨提示：理财非存款，产品有风险，投资需谨慎。购买前请认真阅读产品说明书，根据自身风险承受能力选择合适的产品。', '理财业务', '理财产品,风险,收益,R1,R2,R3,投资', 1, 80, 0),
('账户被冻结了怎么解冻?', '账户冻结可能有以下几种原因及对应处理方式：1.司法冻结：因涉及诉讼、执行等原因被法院冻结，需联系冻结法院了解具体情况，待法律程序结束后自动解冻；2.风控冻结：系统检测到异常交易自动触发保护，请携带本人身份证件前往任意网点核实身份后申请解冻；3.休眠账户冻结：连续6个月无任何交易，持身份证到网点即可激活；4.密码错误冻结：连续输错密码被锁，可通过手机银行或网点重置密码。一般风控冻结和休眠冻结可在1-3个工作日内处理完毕，司法冻结需视法律程序进度而定。如有疑问，请拨打客服热线95588咨询。', '账户服务', '账户冻结,解冻,司法冻结,风控,休眠账户', 1, 75, 0),
('跨行转账手续费是多少?', '我行跨行转账手续费标准如下：1.手机银行/网上银行转账：目前暂免手续费（优惠活动期间）；2.ATM机跨行转账：2万元以下每笔2元，2万-5万元每笔5元，5万-10万元每笔8元；3.柜台跨行转账：按转账金额的0.05%收取，最低2元/笔，最高50元/笔；4.跨行转账到账时间：5万元以下通常实时到账，5万元以上需经人民银行清算，一般2小时内到账。建议您优先使用手机银行进行转账，既免手续费又方便快捷。具体手续费政策可能随活动调整，以实际办理时为准。', '转账汇款', '跨行转账,手续费,转账费,到账时间', 1, 65, 0),
('如何查询个人征信报告?', '查询个人征信报告有以下几种方式：1.线上查询：登录中国人民银行征信中心官网（www.pbccrc.org.cn），注册并完成身份验证后可免费查询，每人每年有2次免费查询机会；2.手机银行查询：通过我行手机银行APP，在"我的-信用报告"中申请查询；3.线下查询：携带本人有效身份证件前往当地人民银行征信服务大厅或我行指定网点自助查询机查询。个人征信报告包含您的信贷记录、信用卡使用情况、公共记录等信息，建议每年至少查询一次。如发现征信记录有误，可向征信中心或信息提供机构提出异议申请。', '信用服务', '征信,征信报告,个人征信,信用查询,央行征信', 1, 70, 0);
