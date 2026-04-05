# 智能语音客服系统 - 大小模型协同后端核心模块

## 一、系统架构概览

```
┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐    ┌──────────────┐
│   前端/用户   │───▶│   小模型服务层    │───▶│   调度服务层      │───▶│  大模型服务层 │
│              │◀───│  (意图识别/对话)  │◀───│  (事件驱动调度)   │◀───│ (LLM/RAG/语料)│
└──────────────┘    └──────────────────┘    └──────────────────┘    └──────────────┘
                           │                        │                       │
                           ▼                        ▼                       ▼
                    ┌──────────────────────────────────────────────────────────────┐
                    │                       数据层                                │
                    │  Redis(缓存/幂等) │ MySQL(知识库/语料/任务) │ Qdrant(向量检索) │
                    │                           阿里云OSS(语料文件存储)              │
                    └──────────────────────────────────────────────────────────────┘
```

## 二、核心业务流程

```
用户对话 → 小模型意图识别
              │
              ├─ 成功 → 直接返回小模型回答
              │
              └─ 失败/用户反馈不佳
                    │
                    ▼
              调度层接收事件 (gRPC: OnIntentFailed / OnUserFeedback)
                    │
                    ├─ ① 幂等性校验（Redis SETNX + DB唯一索引）
                    ├─ ② 创建 DispatchTask + 写入 MessageOutbox（同事务）
                    ├─ ③ 事务提交后委托 Orchestrator 异步编排
                    │
                    ▼
              大模型语料生成流程 (CorpusGenOrchestrator):
                    ├─ ④ 状态机: PENDING → CORPUS_GENERATING
                    ├─ ⑤ gRPC 调用大模型服务生成语料
                    │     ├─ FAQ RAG检索（Qdrant向量 → MySQL全文 → MySQL模糊，三级降级）
                    │     ├─ LLM 生成问答语料（通过 UnifiedLLMService → OpenAICompatibleProvider）
                    │     ├─ 解析LLM输出 → 构建JSONL → 上传阿里云OSS
                    │     └─ MySQL保存语料元数据
                    ├─ ⑥ gRPC回调调度层 OnCorpusReady（携带OSS文件路径）
                    │
                    ▼
              调度层收到语料就绪通知:
                    ├─ ⑦ 状态机: CORPUS_GENERATING → CORPUS_STORED → TRAINING
                    ├─ ⑧ gRPC调用小模型训练（传递OSS datasetUri）
                    ├─ ⑨ 状态机: TRAINING → COMPLETED
                    │
                    ▼
              任务完成 (COMPLETED) 或 失败重试（指数退避，重试耗尽 → FAILED）
```

## 三、模块划分

| 模块 | 端口 | 职责 |
|------|------|------|
| **proto-api** | - | Proto 定义与编译，生成 gRPC Java 类供其他模块依赖 |
| **llm-service** | HTTP 9090 / gRPC 50051 | LLM接入（多Provider）、FAQ知识库RAG检索、语料生成、OSS存储 |
| **dispatcher-service** | HTTP 8088 / gRPC 50052 | 事件驱动调度、状态机管理、编排器模式、本地消息表、定时重试补偿 |

## 四、技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 应用框架 | Spring Boot 3.2.5 / Java 17 | 基础框架 |
| RPC通信 | gRPC 1.62.2 + Protobuf 3.25.5 | 服务间高性能通信 |
| ORM | MyBatis-Plus 3.5.5 | 数据访问层 |
| 数据库 | MySQL 8.0 | 持久化存储 |
| 缓存 | Redis 7.x (Lettuce) | 幂等缓存、FAQ检索缓存 |
| 向量数据库 | Qdrant 1.14.1 (gRPC) | FAQ向量语义检索 |
| Embedding | 阿里云DashScope (qwen3-vl-embedding, 多模态端点) | 文本向量化 |
| LLM | 硅基流动 API (Qwen2.5-72B-Instruct, OpenAI兼容) | 对话生成/语料生成 |
| HTTP客户端 | OkHttp 4.12.0 | 调用LLM REST API |
| 对象存储 | 阿里云OSS 3.17.4 | 语料JSONL文件存储 |
| 日志 | SLF4J + Logback (traceId MDC) | 链路追踪日志 |

## 五、项目结构

```
intelligent-cs/
├── pom.xml                              # 父POM（版本管理/依赖管理/插件管理）
├── sql/
│   └── init.sql                         # 数据库初始化（4张表 + 10条种子数据）
├── proto/                               # Proto 源文件（独立副本）
│   ├── llm_service.proto
│   ├── dispatcher_service.proto
│   └── small_model_service.proto
├── proto-api/                           # Proto编译模块（生成Java类）
│   └── src/main/proto/                  # 与 proto/ 内容一致
│
├── llm-service/                         # 大模型服务层（31个源文件）
│   └── src/main/java/com/ics/llm/
│       ├── config/
│       │   ├── LlmApiConfig.java        # LLM API 配置属性
│       │   ├── AsyncConfig.java         # 异步线程池配置
│       │   ├── RedisConfig.java         # Redis序列化配置
│       │   └── ProviderAutoConfiguration.java  # Provider自动装配
│       ├── grpc/
│       │   ├── server/
│       │   │   ├── LlmGrpcServiceImpl.java           # LLM对话gRPC实现
│       │   │   └── CorpusGenerationGrpcServiceImpl.java  # 语料生成gRPC实现
│       │   └── client/
│       │       └── DispatcherGrpcClient.java          # 调用调度服务客户端
│       ├── infra/
│       │   ├── DispatcherNotifyService.java  # 通知调度服务（gRPC回调）
│       │   └── OssService.java               # 阿里云OSS文件上传
│       ├── service/
│       │   ├── UnifiedLLMService.java        # 统一LLM服务入口（路由门面）
│       │   ├── LlmApiService.java            # LLM API调用封装
│       │   ├── FaqKnowledgeService.java      # FAQ知识库（混合检索）
│       │   ├── CorpusGenerationService.java  # 语料生成核心流程
│       │   ├── EmbeddingService.java         # 文本向量化门面
│       │   ├── VectorStoreService.java       # Qdrant向量存储
│       │   └── provider/                     # Provider策略模式
│       │       ├── LLMProvider.java                # LLM能力接口
│       │       ├── OpenAICompatibleProvider.java   # OpenAI兼容实现（通义/硅基流动/DeepSeek等）
│       │       ├── EmbeddingProvider.java          # Embedding能力接口
│       │       └── DashScopeEmbeddingProvider.java # DashScope多模态Embedding实现
│       ├── scheduler/
│       │   └── FaqVectorSyncScheduler.java  # FAQ向量定时同步（5分钟间隔）
│       ├── model/
│       │   ├── entity/  # FaqKnowledge, SmallModelCorpus
│       │   └── dto/     # LlmApiRequest, LlmApiResponse, CorpusItem, FaqSearchResult
│       ├── repository/mapper/   # FaqKnowledgeMapper, SmallModelCorpusMapper
│       └── util/
│           ├── PromptTemplates.java  # Prompt模板（支持application.yml配置化）
│           └── IdGenerator.java      # ID生成器
│
└── dispatcher-service/                  # 调度服务层（19个源文件）
    └── src/main/java/com/ics/dispatcher/
        ├── config/
        │   ├── AsyncConfig.java         # 异步线程池配置
        │   └── RedisConfig.java         # Redis序列化配置
        ├── grpc/
        │   ├── server/
        │   │   └── DispatcherGrpcServiceImpl.java  # 调度服务gRPC实现
        │   └── client/
        │       ├── LlmGrpcClient.java              # 调用大模型服务
        │       └── SmallModelGrpcClient.java       # 调用小模型服务
        ├── orchestration/                    # 编排器策略模式
        │   ├── TaskOrchestrator.java         # 编排器接口
        │   ├── OrchestratorFactory.java      # 编排器工厂（自动发现注册）
        │   └── impl/
        │       └── CorpusGenOrchestrator.java # 语料生成编排器实现
        ├── machine/
        │   └── TaskStateMachine.java        # 任务状态机
        ├── retry/
        │   └── RetryScheduler.java          # 定时重试/超时检查/消息补偿
        ├── service/
        │   ├── DispatchTaskService.java     # 调度任务核心服务
        │   └── AsyncDispatchService.java    # 异步调度服务
        ├── model/
        │   ├── entity/  # DispatchTask, MessageOutbox, TaskStatus(枚举)
        │   └── dto/     # TaskContext
        └── repository/mapper/   # DispatchTaskMapper, MessageOutboxMapper
```

## 六、核心设计模式

### 6.1 Provider 策略模式（llm-service）

通过 `LLMProvider` 和 `EmbeddingProvider` 接口抽象不同厂商能力，`UnifiedLLMService` 作为门面统一路由：

```
UnifiedLLMService (路由门面)
  ├── OpenAICompatibleProvider  → 硅基流动/通义千问/DeepSeek/智谱GLM/Moonshot 等
  └── (可扩展) BaiduErnieProvider, ZhipuProvider ...

EmbeddingService (向量化门面)
  └── DashScopeEmbeddingProvider → qwen3-vl-embedding (多模态端点)
  └── (可扩展) OpenAIEmbeddingProvider, CohereEmbeddingProvider ...
```

新增 LLM/Embedding 提供商只需：实现对应 Provider 接口 + 注册为 Spring Bean，无需修改上层代码。

### 6.2 Orchestrator 编排器模式（dispatcher-service）

通过 `TaskOrchestrator` 接口 + `OrchestratorFactory` 工厂实现任务类型的策略路由：

```
DispatchTaskService (事件接收)
  └── OrchestratorFactory (按triggerType自动路由)
        └── CorpusGenOrchestrator → 处理 INTENT_FAILED / USER_FEEDBACK 事件
        └── (可扩展) 新编排器实现，自动发现注册，零修改
```

### 6.3 状态机（dispatcher-service）

`TaskStateMachine` 管理任务生命周期，所有状态转换必须经过合法性校验：

```
PENDING → CORPUS_GENERATING → CORPUS_STORED → TRAINING → COMPLETED
   │            │                  │             │
   └────────────┴──────────────────┴─────────────┘ → FAILED (重试耗尽)
```

### 6.4 RAG 三级降级（llm-service）

`FaqKnowledgeService` 实现检索链逐级降级，保证在任何情况下都能返回结果：

```
Qdrant 向量语义检索 (主路径)
  → MySQL FULLTEXT 中文全文检索 (降级1)
    → MySQL LIKE 模糊检索 (降级2)
```

## 七、数据库设计（4张表）

| 表名 | 所属服务 | 说明 |
|------|---------|------|
| `faq_knowledge` | llm-service | FAQ知识库（含 FULLTEXT ngram 中文全文索引，10条金融领域种子数据） |
| `small_model_corpus` | llm-service | 语料元数据（仅存OSS文件路径和MD5，不存具体问答内容） |
| `dispatch_task` | dispatcher-service | 调度任务全生命周期（状态机流转、重试记录、OSS路径） |
| `message_outbox` | dispatcher-service | 本地消息表（事务一致性保证，定时扫描补偿） |

## 八、可靠性机制

| 机制 | 说明 |
|------|------|
| **幂等性** | Redis SETNX + DB唯一索引（`event_id` / `corpus_id` / `message_id`）双重保证 |
| **本地消息表** | 业务操作 + 消息写入同事务，RetryScheduler 定时扫描补偿 |
| **指数退避重试** | 调度任务可配置 `max-retries`、`initial-delay`、`backoff-multiplier`；LLM API 内置重试 |
| **事务后回调** | `TransactionSynchronization` 确保异步编排在事务提交后执行 |
| **RAG三级降级** | Qdrant向量 → MySQL全文 → MySQL模糊，任何环节失败自动降级 |
| **Qdrant优雅降级** | 连接失败时 `initialized=false`，自动切换 MySQL 检索，不影响核心流程 |
| **Prompt配置化** | 所有Prompt模板通过 `application.yml` 配置，支持运行时自定义 |

## 九、配置说明

两个服务的所有配置项通过 `application.yml` 管理，无代码内默认值（`@Value` 不带默认值，必须在配置文件中显式声明）。

| 配置文件 | 说明 |
|---------|------|
| `llm-service/src/main/resources/application.yml` | 大模型服务完整配置 |
| `llm-service/src/main/resources/application-example.yml` | 大模型服务配置模板（脱敏，用于新环境部署参考） |
| `dispatcher-service/src/main/resources/application.yml` | 调度服务完整配置 |
| `dispatcher-service/src/main/resources/application-example.yml` | 调度服务配置模板（脱敏，用于新环境部署参考） |

主要配置分组：

- **`llm.api`** — LLM API 地址/密钥/模型/超时
- **`prompt`** — Prompt模板（direct-answer、corpus-generation）
- **`embedding`** — DashScope Embedding（api-key、url、model、dimensions）
- **`qdrant`** — Qdrant向量库连接（host、port、collection-name）
- **`faq.search`** — FAQ检索参数（max-results、min-score、vector-enabled）
- **`faq.sync`** — FAQ向量同步调度（interval、initial-delay、batch-size）
- **`corpus.generation`** — 语料生成参数（default-count、max-count、min-confidence）
- **`aliyun.oss`** — OSS存储（endpoint、access-key、bucket、dir-prefix）
- **`dispatch.retry`** — 调度重试策略（max-retries、initial-delay、backoff-multiplier）

## 十、快速启动

```bash
# 1. 初始化数据库
mysql -u root -p < sql/init.sql

# 2. 确保 Redis 和 Qdrant 已启动
#    Redis:    localhost:6379
#    Qdrant:   localhost:6334

# 3. 编译 Proto 模块（其他模块依赖其编译产物）
mvn clean install -pl proto-api -DskipTests

# 4. 配置各服务 application.yml（参考 application-example.yml）

# 5. 启动大模型服务层
cd llm-service && mvn spring-boot:run

# 6. 启动调度服务层
cd dispatcher-service && mvn spring-boot:run
```

## 十一、gRPC 接口一览

### LlmService（llm-service 暴露，端口 50051）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `Chat` | 调度层/任意 | 单轮对话（含RAG增强） |
| `BatchChat` | 调度层 | 批量对话 |
| `StreamChat` | 前端 | 流式对话 |

### CorpusGenerationService（llm-service 暴露，端口 50051）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `GenerateCorpus` | 调度层 | 生成训练语料（异步执行，同步返回） |

### DispatcherService（dispatcher-service 暴露，端口 50052）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `OnIntentFailed` | 小模型层 | 意图识别失败事件 |
| `OnUserFeedback` | 前端/网关 | 用户反馈不佳事件 |
| `OnCorpusReady` | 大模型层 | 语料入库完成通知（携带OSS路径） |
| `QueryTaskStatus` | 任意 | 查询调度任务状态 |

### SmallModelTrainingService（小模型层，仅接口约定，端口 50053）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `StartTraining` | 调度层 | 触发增量训练（传递OSS datasetUri） |
| `QueryTrainingStatus` | 调度层 | 查询训练进度 |

## 十二、测试覆盖

共 **10 个测试文件**，覆盖核心业务逻辑：

| 模块 | 测试文件 | 说明 |
|------|---------|------|
| llm-service | `LlmServiceIntegrationTest` | 大模型服务集成测试 |
| llm-service | `CorpusGenerationServiceTest` | 语料生成服务单元测试 |
| llm-service | `FaqKnowledgeServiceTest` | FAQ知识库服务单元测试 |
| llm-service | `LlmApiServiceTest` | LLM API调用单元测试 |
| llm-service | `DispatcherNotifyServiceTest` | 通知服务单元测试 |
| llm-service | `PromptTemplatesTest` | Prompt模板单元测试 |
| llm-service | `IdGeneratorTest` | ID生成器单元测试 |
| dispatcher-service | `DispatcherServiceIntegrationTest` | 调度服务集成测试 |
| dispatcher-service | `DispatchTaskServiceTest` | 调度任务服务单元测试 |
| dispatcher-service | `RetrySchedulerTest` | 重试调度器单元测试 |
