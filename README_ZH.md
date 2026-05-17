# Custom-Study

> Java 后端全栈知识体系学习项目——以手写原理代码为核心，覆盖 JDK 底层、中间件、存储引擎、分布式计算、Spring 生态五大领域。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)]()
[![Maven](https://img.shields.io/badge/Maven-3.9+-blue.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.9-green.svg)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)]()

## 特性

- **手写原理**：所有 Demo 均用纯 Java 标准库模拟框架核心机制，不依赖真实框架运行
- **可运行**：每个 Demo 都有 `main()` 方法，可直接运行观察输出
- **四阶段递进**：JDK 核心 → Spring 生态 → 中间件 → 存储/分布式，由浅入深
- **面试导向**：每个模块配有面试高频问题专项 Demo 和学习笔记
- **设计模式密集**：代理、工厂、责任链、组合、策略等 11 种设计模式贯穿项目

## 项目架构

```
custom-study
├── custom-study-bom            ← BOM 依赖版本管理
├── study-jdk-core              ← STAGE 1：JDK 核心原理
│   ├── study-overview          ← 项目总览与交互式启动器
│   ├── study-generics          ← 泛型原理
│   ├── study-collection        ← 集合框架
│   ├── study-concurrent        ← 并发编程
│   ├── study-jvm               ← JVM 虚拟机
│   ├── study-nio               ← NIO 网络编程
│   ├── study-reflect           ← 反射与注解
│   └── study-design-patterns   ← 设计模式
├── study-spring-eco            ← STAGE 2：Spring 生态
│   ├── study-spring-core       ← Spring IoC/AOP
│   ├── study-spring-boot       ← Spring Boot
│   ├── study-spring-alibaba    ← Spring Cloud Alibaba
│   ├── study-mybatis           ← MyBatis 原理
│   └── study-devops            ← DevOps 工具链
├── study-middleware            ← STAGE 3：中间件原理
│   ├── study-redis             ← Redis 原理
│   ├── study-mq                ← 消息队列原理
│   ├── study-netty             ← Netty 原理
│   └── study-websocket         ← WebSocket
├── study-storage               ← STAGE 4：存储引擎
│   ├── study-db                ← 数据库原理
│   ├── study-elasticsearch     ← Elasticsearch
│   ├── study-mongodb           ← MongoDB 原理
│   ├── study-hbase             ← HBase 原理
│   └── study-minio             ← MinIO 原理
└── study-distributed           ← STAGE 4：分布式计算
    ├── study-distributed-theory ← 分布式理论
    ├── study-dolphinscheduler   ← DolphinScheduler
    ├── study-seatunnel          ← SeaTunnel
    ├── study-flink              ← Flink 原理
    └── study-spark              ← Spark 原理
```

## 模块概览

### STAGE 1：JDK 核心原理

| 模块 | 核心类 | 关键知识点 |
|------|--------|-----------|
| **study-generics** | `GenericDemo` `PECSDemo` `TypeErasureDemo` `WildcardDemo` | 类型擦除、PECS 原则、上界/下界通配符 |
| **study-collection** | `HashMapDemo` | 扰动函数、扩容机制、链表转红黑树 |
| **study-concurrent** | `CASDemo` `AQSDemo` `MyLock` | CAS + ABA、AQS 三要素、手写可重入锁 |
| **study-jvm** | `MemoryModelDemo` `ClassLoaderDemo` `GCDemo` `JVMTuningDemo` | 运行时数据区、双亲委派、GC 算法、调优实战 |
| **study-nio** | `NIODemo` `StickyPacketDemo` `ZeroCopyDemo` | BIO/NIO/AIO 对比、粘包半包、零拷贝 |
| **study-reflect** | `ReflectDemo` `AnnotationDemo` `ProxyDemo` | 反射 API、自定义注解、JDK 动态代理 |

### STAGE 2：Spring 生态

| 模块 | 核心类 | 关键知识点 |
|------|--------|-----------|
| **study-spring-core** | `IoCDemo` `AOPDemo` `BeanLifecycleDemo` | 手写 IoC 容器、JDK/CGLIB 代理、Bean 生命周期 |
| **study-spring-boot** | `AutoConfigPrincipleDemo` `ConditionalDemo` `StarterPrincipleDemo` | 自动配置原理、条件装配、Starter 机制 |
| **study-spring-alibaba** | `NacosDiscoveryDemo` `SentinelDemo` `SeataDemo` | 服务发现、流控熔断、分布式事务 |
| **study-mybatis** | `MyBatisCoreDemo` `DynamicSQLDemo` `MyBatisPlusDemo` | 完整调用链、动态 SQL 引擎、乐观锁/逻辑删除 |
| **study-devops** | `MavenDemo` `GitDemo` `DockerDemo` `K8sDemo` `LinuxDemo` | 构建工具、版本控制、容器化、编排、故障排查 |

### STAGE 3：中间件原理

| 模块 | 核心类 | 关键知识点 |
|------|--------|-----------|
| **study-redis** | `DataStructureDemo` `PersistenceDemo` `CacheProblemDemo` `ClusterDemo` `BigKeyDemo` `RedisTuningDemo` | SDS/Ziplist/Skiplist、RDB/AOF、穿透/击穿/雪崩、哈希槽、大 Key/热 Key |
| **study-mq** | `KafkaCoreDemo` `KafkaIdempotentDemo` `MessageReliabilityDemo` `RocketMQCoreDemo` `TransactionDemo` | ISR/HW-LEO、幂等事务、可靠性保障、CommitLog/ConsumeQueue、事务消息 |
| **study-netty** | `EventLoopDemo` `PipelineDemo` `StickyPacketDemo` `ZeroCopyDemo` | Reactor 模式、责任链、粘包解码、零拷贝 |
| **study-websocket** | `WebSocketCoreDemo` `HeartbeatDemo` `ReconnectDemo` | 协议原理、心跳机制、断线重连 |

### STAGE 4：存储引擎与分布式计算

| 模块 | 核心类 | 关键知识点 |
|------|--------|-----------|
| **study-db** | `IndexDemo` `SQLOptimizerDemo` `SQLRewriteDemo` `ShardingJDBCDemo` `PostgreSQLDemo` | B+Tree、CBO 优化器、SQL 改写、分库分表、MVCC |
| **study-elasticsearch** | `InvertedIndexDemo` `DSLQueryDemo` `AggregationDemo` `ShardingClusterDemo` | 倒排索引、DSL 查询、聚合分析、分片集群 |
| **study-mongodb** | `DocumentModelDemo` `IndexDemo` `ReplicaShardDemo` `AggregationPipelineDemo` | BSON/嵌套/引用、ESR 规则、副本集/分片、聚合管道 |
| **study-hbase** | `RowKeyDesignDemo` `RegionSplitDemo` `SecondaryIndexDemo` | RowKey 设计、Region 拆分、二级索引 |
| **study-minio** | `ErasureCodingDemo` `ObjectStorageDemo` `PolicyVersionDemo` | 纠删码、S3 API、IAM Policy、版本控制 |
| **study-flink** | `CheckpointDemo` `WatermarkDemo` `WindowDemo` `StateBackendDemo` | Barrier 对齐、水位线、窗口机制、State 管理 |
| **study-spark** | `RDDDemo` `DAGShuffleDemo` `DataFrameDemo` `BroadcastJoinDemo` | 惰性求值、DAG 划分、Catalyst 优化器、Join 策略 |

## 快速开始

### 环境要求

- JDK ≥ 21
- Maven ≥ 3.9

### 编译

```bash
git clone git@github.com:NoPr/custom-study.git
cd custom-study
mvn clean compile -DskipTests
```

### 运行 Demo

**方式一：IDE 直接运行**

在 IntelliJ IDEA 中打开任意 Demo 类，右键 `main()` 方法 → Run。

**方式二：Maven 命令行**

```bash
mvn exec:java -pl study-jdk-core/study-concurrent -Dexec.mainClass="base.concurrent.CASDemo"
```

**方式三：交互式启动器**

```bash
mvn exec:java -pl study-jdk-core/study-overview -Dexec.mainClass="study.StudyRunner"
```

StudyRunner 支持以下命令：

| 命令 | 说明 |
|------|------|
| `list` | 列出所有可用 Demo |
| `run <类名>` | 运行指定 Demo |
| `find <关键词>` | 按关键词搜索 Demo |
| `interactive` | 进入交互式 REPL 模式 |

## 学习路线

```
STAGE 1              STAGE 2              STAGE 3/4
┌──────────┐     ┌──────────────┐     ┌──────────────────┐
│  JDK     │     │  Spring      │     │  中间件/存储/     │
│  Core    │────▶│  Eco         │────▶│  分布式           │
│          │     │              │     │                  │
│ ·泛型    │     │ ·IoC/AOP     │     │ ·Redis/MQ/Netty  │
│ ·集合    │     │ ·MyBatis     │     │ ·DB/HBase/MongoDB│
│ ·并发    │     │ ·Spring Boot │     │ ·MinIO/ES        │
│ ·JVM     │     │ ·DevOps      │     │ ·Flink/Spark     │
│ ·NIO     │     │              │     │                  │
│ ·反射    │     │              │     │                  │
└──────────┘     └──────────────┘     └──────────────────┘
```

推荐学习顺序：

1. **study-concurrent**：`CASDemo` → `AQSDemo` → `MyLock`（CAS 到 AQS 到手写锁，递进理解并发）
2. **study-jvm**：`MemoryModelDemo` → `GCDemo` → `JVMTuningDemo`（内存模型 → GC 算法 → 调优实战）
3. **study-spring-core**：`IoCDemo` → `AOPDemo`（IoC 容器核心骨架与代理机制）
4. **study-mybatis**：`MyBatisCoreDemo` → `DynamicSQLDemo` → `MyBatisPlusDemo`（ORM 框架完整调用链）
5. **study-redis**：`DataStructureDemo` → `CacheProblemDemo` → `ClusterDemo`（Redis 从底层到集群）
6. **study-mq**：`KafkaCoreDemo` → `MessageReliabilityDemo` → `TransactionDemo`（消息可靠性保障全链路）
7. **study-db**：`IndexDemo` → `SQLOptimizerDemo` → `ShardingJDBCDemo`（数据库从索引到分库分表）
8. **study-flink**：`CheckpointDemo` → `WatermarkDemo` → `WindowDemo`（流处理核心机制）
9. **study-spark**：`RDDDemo` → `DAGShuffleDemo` → `DataFrameDemo`（批处理核心机制）

## 设计模式索引

| 设计模式 | 出现位置 | 具体应用 |
|---------|---------|---------|
| 代理模式 | `ProxyDemo` `MyLock` `MyBatisCoreDemo` `AOPDemo` | JDK 动态代理、CGLIB、MapperProxy、Plugin.wrap |
| 工厂模式 | `IoCDemo` `MyBatisCoreDemo` | BeanFactory、SqlSessionFactory |
| 单例模式 | `IoCDemo` `ReflectDemo` | ConcurrentHashMap 单例池、防御反射破坏单例 |
| 模板方法 | `MyLock` `AQSDemo` | AQS acquire() 调用子类 tryAcquire() |
| 责任链 | `PipelineDemo` `MyBatisCoreDemo` | Netty Pipeline 双向链表、MyBatis Interceptor 链 |
| 组合模式 | `DynamicSQLDemo` | SqlNode 树形结构 |
| 策略模式 | `WindowDemo` `StateBackendDemo` | WindowAssigner/Trigger 可替换、ReducingState |
| 观察者 | `SecondaryIndexDemo` | HBase 协处理器 postPut 钩子 |
| 构建器 | `MyBatisCoreDemo` | SqlSessionFactoryBuilder |
| 解释器 | `DynamicSQLDemo` | OGNL 表达式解析 |
| CAS 自旋 | `CASDemo` `MyLock` `WatermarkDemo` | 无锁并发控制 |

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 项目基础 JDK 版本 |
| Maven | 3.9+ | 多模块聚合构建 |
| Spring Boot | 3.3.9 | BOM 版本基线 |
| Lombok | 1.18.40 | 编译期代码生成 |
| JUnit Jupiter | 5.11.4 | 单元测试框架 |

## 文档

- [CODE_WIKI.md](./CODE_WIKI.md)——项目结构化知识文档（架构、模块、类说明、依赖关系、运行方式）

## 许可证

[MIT](./LICENSE)
