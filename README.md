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
                    │                       数据层                                  │
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
                    ├─ ③ 事务提交后异步调用大模型语料生成
                    │
                    ▼
              大模型语料生成流程:
                    ├─ ④ FAQ RAG检索（Qdrant向量 → MySQL全文 → MySQL模糊，三级降级）
                    ├─ ⑤ LLM生成问答语料（硅基流动 API）
                    ├─ ⑥ 解析LLM输出 → 构建JSONL → 上传阿里云OSS
                    ├─ ⑦ MySQL保存语料元数据
                    ├─ ⑧ gRPC回调调度层 OnCorpusReady（携带OSS文件路径）
                    │
                    ▼
              调度层收到语料就绪通知:
                    ├─ ⑨ 更新任务状态 CORPUS_STORED
                    ├─ ⑩ 异步调用小模型训练（传递OSS datasetUri）
                    │
                    ▼
              任务完成 (COMPLETED) 或 失败重试（指数退避）
```

## 三、模块划分

| 模块 | 端口 | 职责 |
|------|------|------|
| **proto-api** | - | Proto 定义与编译，生成 gRPC Java 类供其他模块依赖 |
| **llm-service** | HTTP 9090 / gRPC 50051 | LLM接入、FAQ知识库RAG检索、语料生成、OSS存储 |
| **dispatcher-service** | HTTP 8088 / gRPC 50052 | 事件驱动调度、状态机管理、本地消息表、定时重试补偿 |

## 四、技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 应用框架 | Spring Boot 3.2.5 | 基础框架 |
| RPC通信 | gRPC + Protobuf 3.25.5 | 服务间高性能通信 |
| ORM | MyBatis-Plus 3.5.5 | 数据访问层 |
| 数据库 | MySQL 8.0 (阿里云RDS) | 持久化存储 |
| 缓存 | Redis 7.x (Lettuce) | 幂等缓存、FAQ检索缓存 |
| 向量数据库 | Qdrant | FAQ向量语义检索 |
| Embedding | 阿里云DashScope (qwen3-vl-embedding) | 文本向量化 |
| LLM | 硅基流动 API (Qwen2.5-72B-Instruct) | 对话生成/语料生成 |
| HTTP客户端 | OkHttp 4.12.0 | 调用LLM REST API |
| 对象存储 | 阿里云OSS 3.17.4 | 语料JSONL文件存储 |
| 连接池 | HikariCP | 数据库连接池 |
| 日志 | SLF4J + Logback (traceId MDC) | 链路追踪日志 |

## 五、项目结构

```
intelligent-cs/
├── pom.xml                        # 父POM（版本管理/依赖管理/插件管理）
├── sql/
│   └── init.sql                   # 数据库初始化（4张表+种子数据）
├── proto-api/                     # Proto编译模块
│   └── src/main/proto/
│       ├── llm_service.proto      # 大模型服务 gRPC 定义
│       ├── dispatcher_service.proto # 调度服务 gRPC 定义
│       └── small_model_service.proto # 小模型服务 gRPC 定义(接口约定)
├── llm-service/                   # 大模型服务层
│   └── src/main/java/com/ics/llm/
│       ├── config/                # LlmApiConfig, AsyncConfig, RedisConfig
│       ├── grpc/server/           # LlmGrpcServiceImpl, CorpusGenerationGrpcServiceImpl
│       ├── grpc/client/           # DispatcherGrpcClient
│       ├── service/               # LlmApiService, FaqKnowledgeService, CorpusGenerationService,
│       │                          #   DispatcherNotifyService, EmbeddingService, OssService,
│       │                          #   VectorStoreService
│       ├── model/entity/          # FaqKnowledge, SmallModelCorpus
│       ├── model/dto/             # LlmApiRequest, LlmApiResponse, CorpusItem, FaqSearchResult
│       ├── repository/mapper/     # FaqKnowledgeMapper, SmallModelCorpusMapper
│       └── util/                  # PromptTemplates, IdGenerator
└── dispatcher-service/            # 调度服务层
    └── src/main/java/com/ics/dispatcher/
        ├── config/                # AsyncConfig, RedisConfig
        ├── grpc/server/           # DispatcherGrpcServiceImpl
        ├── grpc/client/           # LlmGrpcClient, SmallModelGrpcClient
        ├── service/               # DispatchTaskService, AsyncDispatchService
        ├── retry/                 # RetryScheduler（定时重试/超时检查/消息补偿）
        ├── model/entity/          # DispatchTask, MessageOutbox
        └── repository/mapper/     # DispatchTaskMapper, MessageOutboxMapper
```

## 六、数据库设计（4张表）

| 表名 | 所属服务 | 说明 |
|------|---------|------|
| `faq_knowledge` | llm-service | FAQ知识库（含FULLTEXT中文全文索引） |
| `small_model_corpus` | llm-service | 语料元数据（仅存OSS文件路径，不存具体内容） |
| `dispatch_task` | dispatcher-service | 调度任务（状态机：PENDING→GENERATING→STORED→TRAINING→COMPLETED/FAILED） |
| `message_outbox` | dispatcher-service | 本地消息表（事务一致性保证） |

## 七、可靠性机制

| 机制 | 说明 |
|------|------|
| **幂等性** | Redis SETNX + DB唯一索引双重保证 |
| **本地消息表** | 业务操作+消息写入同事务，定时扫描补偿 |
| **指数退避重试** | dispatcher最大1次，LLM API最大3次 |
| **事务后回调** | TransactionSynchronization确保异步操作在事务提交后执行 |
| **RAG三级降级** | Qdrant向量检索 → MySQL全文检索 → MySQL模糊检索 |
| **Qdrant优雅降级** | 连接失败时自动降级，不影响核心流程 |

## 八、快速启动

```bash
# 1. 初始化数据库
mysql -u root -p < sql/init.sql

# 2. 确保 Redis 和 Qdrant 已启动
#    Redis:    localhost:6379
#    Qdrant:   localhost:6334

# 3. 编译 Proto 模块（其他模块依赖其编译产物）
mvn clean install -pl proto-api

# 4. 启动大模型服务层
cd llm-service && mvn spring-boot:run

# 5. 启动调度服务层
cd dispatcher-service && mvn spring-boot:run
```

## 九、gRPC 接口一览

### LlmService（llm-service 暴露）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `Chat` | 调度层/任意 | 单轮对话（含RAG增强） |
| `BatchChat` | 调度层 | 批量对话 |
| `StreamChat` | 前端 | 流式对话 |

### CorpusGenerationService（llm-service 暴露）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `GenerateCorpus` | 调度层 | 生成训练语料（异步执行，同步返回） |

### DispatcherService（dispatcher-service 暴露）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `OnIntentFailed` | 小模型层 | 意图识别失败事件 |
| `OnUserFeedback` | 前端/网关 | 用户反馈不佳事件 |
| `OnCorpusReady` | 大模型层 | 语料入库完成通知（携带OSS路径） |
| `QueryTaskStatus` | 任意 | 查询调度任务状态 |

### SmallModelTrainingService（小模型层，仅接口约定）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `StartTraining` | 调度层 | 触发增量训练（传递OSS datasetUri） |
| `QueryTrainingStatus` | 调度层 | 查询训练进度 |
