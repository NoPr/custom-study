# Custom-Study Code Wiki

> Java 后端全栈知识体系学习项目——以手写原理代码为核心，覆盖 JDK 底层、中间件、存储引擎、分布式计算、Spring 生态五大领域。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 整体架构](#2-整体架构)
- [3. 技术栈与依赖](#3-技术栈与依赖)
- [4. 模块详解](#4-模块详解)
  - [4.1 study-jdk-core——JDK 核心原理](#41-study-jdk-corejdk-核心原理)
  - [4.2 study-middleware——中间件原理](#42-study-middleware中间件原理)
  - [4.3 study-storage——存储引擎原理](#43-study-storage存储引擎原理)
  - [4.4 study-distributed——分布式计算原理](#44-study-distributed分布式计算原理)
  - [4.5 study-spring-eco——Spring 生态原理](#45-study-spring-ecospring-生态原理)
- [5. 模块依赖关系图](#5-模块依赖关系图)
- [6. 设计模式索引](#6-设计模式索引)
- [7. 项目运行方式](#7-项目运行方式)
- [8. 学习路线建议](#8-学习路线建议)

---

## 1. 项目概述

| 项目 | 说明 |
|------|------|
| **GroupId** | `org.nopr` |
| **ArtifactId** | `custom-study` |
| **版本** | `0.0.1-SNAPSHOT` |
| **Java 版本** | 21 |
| **构建工具** | Maven（多模块聚合） |
| **项目性质** | 教学型——手写原理代码，不依赖真实框架运行 |

**核心理念：** 所有 Demo 类均为"手写原理版"，用纯 Java 标准库模拟框架核心机制，通过 `main()` 方法直接运行演示，强调**可运行、可观察、可理解**。

---

## 2. 整体架构

项目采用 Maven 多模块聚合结构，共 **5 大一级模块**、**22 个二级子模块**，形成四阶段递进式知识体系：

```
custom-study (root)
├── custom-study-bom          ← BOM 依赖版本管理
├── study-jdk-core            ← STAGE 1：JDK 核心原理
│   ├── study-overview        ← 项目总览与交互式启动器
│   ├── study-generics        ← 泛型原理
│   ├── study-collection      ← 集合框架
│   ├── study-concurrent      ← 并发编程
│   ├── study-jvm             ← JVM 虚拟机
│   ├── study-nio             ← NIO 网络编程
│   ├── study-reflect         ← 反射与注解
│   └── study-design-patterns ← 设计模式（骨架）
├── study-spring-eco          ← STAGE 2：Spring 生态
│   ├── study-spring-core     ← Spring IoC/AOP
│   ├── study-spring-boot     ← Spring Boot（骨架）
│   ├── study-spring-alibaba  ← Spring Cloud Alibaba（骨架）
│   ├── study-mybatis         ← MyBatis 原理
│   └── study-devops          ← DevOps 工具链
├── study-middleware          ← STAGE 3：中间件原理
│   ├── study-redis           ← Redis 原理
│   ├── study-mq              ← 消息队列原理
│   ├── study-netty           ← Netty 原理
│   └── study-websocket       ← WebSocket（骨架）
├── study-storage             ← STAGE 4：存储引擎
│   ├── study-db              ← 数据库原理
│   ├── study-elasticsearch   ← Elasticsearch（骨架）
│   ├── study-mongodb         ← MongoDB 原理
│   ├── study-hbase           ← HBase 原理
│   └── study-minio           ← MinIO 原理
└── study-distributed         ← STAGE 4：分布式计算
    ├── study-distributed-theory ← 分布式理论（骨架）
    ├── study-dolphinscheduler    ← DolphinScheduler（骨架）
    ├── study-seatunnel           ← SeaTunnel（骨架）
    ├── study-flink               ← Flink 原理
    └── study-spark               ← Spark 原理
```

> 标注"骨架"的模块已建立目录结构，但尚未填充源码和业务依赖。

---

## 3. 技术栈与依赖

### 3.1 BOM 版本管理

项目通过 `custom-study-bom` 集中管理第三方依赖版本：

| 依赖 | 版本 | scope | 说明 |
|------|------|-------|------|
| `spring-boot-dependencies` | 3.3.9 | import (BOM) | Spring Boot 全家桶版本基线 |
| `lombok` | 1.18.40 | provided | 编译期代码生成 |
| `junit-jupiter` | 5.11.4 | test | 单元测试框架 |

### 3.2 构建插件

| 插件 | 版本 | 说明 |
|------|------|------|
| `maven-compiler-plugin` | 3.11.0 | Java 21 编译，`-parameters` 参数名保留 |
| `maven-surefire-plugin` | 3.2.5 | 测试执行 |

### 3.3 子模块依赖特征

当前所有 22 个子模块依赖极简——仅声明 `lombok`（provided），版本由 BOM 统一管理。各模块的核心逻辑均使用纯 Java 标准库手写实现，不引入真实框架依赖。

**例外：** `study-spring-core` 额外引入 `spring-context` 和 `jakarta.annotation-api`；`study-spring-boot` 引入 `spring-boot` 和 `spring-boot-autoconfigure`。

---

## 4. 模块详解

### 4.1 study-jdk-core——JDK 核心原理

> Java 基础能力的底层原理，从泛型到并发到 JVM，构建后端工程师的核心知识底座。

#### 4.1.1 study-overview——项目总览

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `StudyOverview` | 全项目模块总览与学习路线入口 | `main()` 打印四阶段知识体系；`printSection()` 格式化输出模块列表 |
| `StudyRunner` | 交互式 Demo 启动器 | `listAll()` 列出所有 Demo；`runByClassName()` 反射调用指定类；`findClasses()` 关键词搜索；`interactive()` REPL 模式 |

#### 4.1.2 study-generics——泛型原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `GenericDemo` | 泛型基础用法演示 | `withoutGenerics()` 无泛型 ClassCastException；`withGenerics()` 编译期类型检查；`genericMethod()` 泛型方法；`genericInterfaceDemo()` 泛型接口 `Processor<T>` |
| `PECSDemo` | PECS 原则实战 | `transferData()` 生产者 `? extends T` + 消费者 `? super T` 数据转移 |
| `TypeErasureDemo` | 类型擦除深度演示 | `eraseEquality()` 运行时 Class 相同；`bridgeMethodDemo()` 桥方法；`limitationDemo()` 擦除局限性 |
| `WildcardDemo` | 通配符完整演示 | `extendsDemo()` 上界只读；`superDemo()` 下界只写；`pecsSummary()` PECS 综合示例 |

**核心知识点：** 泛型将运行时异常提前到编译期；PECS 原则（Producer Extends, Consumer Super）；类型擦除机制与桥方法；上界/下界通配符的读写限制。

#### 4.1.3 study-collection——集合框架

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `HashMapDemo` | HashMap 原理与碰撞 | `basicUsage()` CRUD + 遍历；`hashAndIndex()` 扰动函数与索引计算；`collisionDemo()` 哈希碰撞；`nullKeyDemo()` null key 行为 |

**核心知识点：** hash 扰动 `h ^ (h >>> 16)`；索引公式 `(n-1) & hash`；链表转红黑树条件（长度 ≥ 8 且数组 ≥ 64）。

#### 4.1.4 study-concurrent——并发编程

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `CASDemo` | CAS 原理 + ABA 问题 | `unsafeIncrementDemo()` 无锁不安全；`atomicIncrementDemo()` AtomicInteger CAS；`casSpinDemo()` 手写自旋；`abaProblemDemo()` ABA 复现；`abaSolutionDemo()` AtomicStampedReference 解决 |
| `AQSDemo` | AQS 原理 + 公平/非公平锁 | `aqsArchitectureExplain()` 三要素架构；`fairLockDemo()` 公平锁 FIFO；`unfairLockDemo()` 非公平锁插队；`reentrantDemo()` 可重入性；`lockInterruptiblyDemo()` 响应中断 |
| `MyLock` | 基于 AQS 手写可重入非公平锁 | `lock()`/`unlock()`/`tryLock()`/`lockInterruptibly()`/`newCondition()`——完整 Lock 接口实现 |

**核心知识点：** CAS 三操作数（V/A/B）；AQS 三要素（volatile state + CLH 队列 + 模板方法）；公平锁检查 `hasQueuedPredecessors()`，非公平锁直接 CAS 抢锁；可重入通过 state 计数实现。

#### 4.1.5 study-jvm——JVM 虚拟机

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `MemoryModelDemo` | 运行时数据区五大块 | `jvmRuntimeDataAreaOverview()` 全景图；`heapMemoryDetail()` 堆分代；`methodAreaAndMetaspace()` 永久代 vs 元空间；`virtualMachineStackDetail()` 栈帧四部分；`stackOverflowDemo()` StackOverflow |
| `ClassLoaderDemo` | 类加载器与双亲委派 | `classLoaderHierarchy()` 三层架构；`parentDelegationMechanism()` 双亲委派源码；`classForNameVsLoadClass()` 两种加载方式；`customClassLoaderDemo()` 自定义类加载器 |
| `GCDemo` | GC 算法与引用类型 | `objectSurvivalJudgment()` 引用计数 vs 可达性分析；`gcRootsEnumeration()` GC Roots；`collectorComparison()` 五种收集器对比；四种引用行为演示 |
| `JVMTuningDemo` | JVM 调优实战 | `jvmParameterReference()` 参数速查；`gcLogParameters()` 日志配置；`tuningFlow()` 调优五步流程；`commonProblems()` OOM/CPU 100%/Full GC 诊断 |

**核心知识点：** 线程私有（PC、虚拟机栈、本地方法栈）vs 线程共享（堆、方法区）；双亲委派先查缓存→委托父加载器→自己加载；Java 采用可达性分析判断对象存活；四种引用强度递减（强 > 软 > 弱 > 虚）。

#### 4.1.6 study-nio——NIO 网络编程

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `NIODemo` | BIO/NIO/AIO 模型对比 + NIO 实现 | `bioModelExplanation()` 一连接一线程；`nioModelExplanation()` Selector 多路复用；`aioModelExplanation()` 异步回调；`nioServerClientDemo()` NIO 服务器/客户端 |
| `StickyPacketDemo` | TCP 粘包/半包问题 | `stickyPacketReasonExplain()` 三大原因；`fixedLengthSolution()` 定长解码；`delimiterSolution()` 分隔符解码；`lengthFieldSolution()` 长度域解码 |
| `ZeroCopyDemo` | 零拷贝原理实测 | `traditionalCopyDemo()` 4 次拷贝；`mmapCopyDemo()` 3 次拷贝；`transferToCopyDemo()` 2 次拷贝；`performanceComparison()` 性能对比 |
| `Q02_ZeroCopy` | 零拷贝面试题专项 | `mmapVsSendfile()` 多维度对比；`kafkaNettyApplication()` Kafka/Netty 应用；`zeroCopyLimitations()` 局限性 |

**核心知识点：** NIO 三大核心（Channel + Buffer + Selector）；粘包根因（Nagle + MSS + 缓冲区积压）；零拷贝演进（传统 4 次 → mmap 3 次 → sendfile 2 次）。

#### 4.1.7 study-reflect——反射与注解

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `ReflectDemo` | 反射核心 API | `getClassThreeWays()` 获取 Class 三种方式；`createInstanceByReflect()` 反射创建对象；`accessPrivateField()` 操作私有字段；`breakSingleton()` 反射破坏单例；`defendSingleton()` 防御反射 |
| `AnnotationDemo` | 自定义注解 + ORM 映射 | `generateDDL()` 读取 @Table/@Column 生成 DDL；`validateAnnotation()` 运行时验证注解约束 |
| `ProxyDemo` | JDK 动态代理深层原理 | `proxyBasicApi()` 基础用法；`proxyClassDeepAnalysis()` $Proxy0 结构分析；`proxyMethodFilter()` 方法选择性拦截（AOP 原形）；`proxySelfCallWarning()` 自调用死循环陷阱 |

**核心知识点：** `setAccessible(true)` 突破访问控制；防御反射破坏单例（构造器检测）；JDK 动态代理只能代理接口；`$Proxy0 extends Proxy implements 接口`。

---

### 4.2 study-middleware——中间件原理

> 从 Redis 缓存到消息队列到 Netty 网络框架，覆盖后端三大核心中间件的底层原理。

#### 4.2.1 study-redis——Redis 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `DataStructureDemo` | 手写四种底层数据结构 | `sdsDemo()` SDS 动态字符串；`ziplistDemo()` Ziplist 压缩列表；`skiplistDemo()` Skiplist 跳跃表；`hashRehashDemo()` 渐进式 rehash |
| `PersistenceDemo` | 四种持久化机制 | `rdbDemo()` fork + COW 快照；`aofDemo()` RESP 协议追加；`aofRewriteDemo()` AOF 重写；`hybridDemo()` RDB 头 + AOF 尾混合 |
| `CacheProblemDemo` | 缓存穿透/击穿/雪崩 | `cachePenetrationDemo()` 空值缓存 + 布隆过滤器；`cacheBreakdownDemo()` 互斥锁 DCL；`cacheAvalancheDemo()` 随机 TTL + 多级缓存 |
| `ClusterDemo` | 集群核心机制 | `crc16SlotDemo()` CRC16 哈希槽；`consistentHashDemo()` 一致性哈希 + 虚拟节点；`masterSlaveReplicationDemo()` PSYNC 同步；`sentinelFailoverDemo()` Sentinel 故障转移 |
| `BigKeyDemo` | 大 Key/热 Key 治理 | `bigKeyDiscoveryDemo()` SCAN + MEMORY USAGE；`bigKeySplitDemo()` String 拆 Hash；`hotKeyDetectionDemo()` 滑动窗口计数；`hotKeySolutionDemo()` 四种方案 |
| `RedisTuningDemo` | Redis 调优 | `slowlogDemo()` 慢查询；`pipelineDemo()` Pipeline 批量；`luaScriptDemo()` Lua 原子脚本；`expirationDemo()` 惰性 + 定期删除 |

**核心知识点：** SDS 预分配策略减少内存重分配；渐进式 rehash 分批迁移避免阻塞；DCL（Double-Check Locking）防缓存击穿；一致性哈希 + 虚拟节点减少数据迁移；Pipeline 不保证原子性需用 Lua 替代。

#### 4.2.2 study-mq——消息队列原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `KafkaCoreDemo` | Kafka 核心架构模拟 | `isrDynamicDemo()` ISR 动态维护；`hwLeoDemo()` HW/LEO 推进；`leaderElectionDemo()` Controller 选主 |
| `KafkaIdempotentDemo` | Kafka 幂等与事务 | 幂等去重（PID + SeqNum）；事务提交/回滚；exactly-once 语义分解 |
| `MessageOrderDemo` | 消息顺序性 | `rebalanceDisorderDemo()` 重平衡导致顺序中断；分区有序 vs 全局有序 |
| `MessageReliabilityDemo` | 消息可靠性保障 | `scenario1_ProducerToBrokerLoss()` 生产端重试；`scenario2_BrokerCrashLoss()` 同步刷盘+复制；`scenario3_ConsumerIdempotent()` 幂等消费 |
| `RocketMQCoreDemo` | RocketMQ 核心架构 | NameServer 路由注册；CommitLog 顺序追加；ConsumeQueue 索引构建；Consumer 拉取 |
| `TransactionDemo` | RocketMQ 事务消息 | `timeoutCheckScenario()` 超时回查；两阶段提交（半消息 → 本地事务 → commit/rollback） |

**核心知识点：** HW = min(ISR 所有副本 LEO)，消费者只能读 HW 之前消息；CommitLog 全局顺序追加写（WAL 思想）；ConsumeQueue 每条 20 字节作为逻辑索引；at-least-once + 幂等消费 = exactly-once 效果；RocketMQ 事务 = 最终一致而非强一致。

#### 4.2.3 study-netty——Netty 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `EventLoopDemo` | EventLoop 反应器模式 | Boss/Worker 分组 + Selector + Channel 注册；Worker 任务队列异步注册 |
| `PipelineDemo` | Pipeline 责任链模式 | Inbound 从 Head→Tail 传播；Outbound 从 Tail→Head 传播；异常传播与处理 |
| `StickyPacketDemo` | 粘包半包解码器 | `demonstrateLineBasedDecoder()` 换行符；`demonstrateFixedLengthDecoder()` 定长；`demonstrateDelimiterBasedDecoder()` 分隔符；`demonstrateLengthFieldBasedDecoder()` 长度字段；`demonstrateCustomProtocolCodec()` 自定义协议 |
| `ZeroCopyDemo` | 零拷贝机制 | `demonstrateTraditionalCopy()` 传统 IO 4 次；`demonstrateSendfileTransferTo()` sendfile 2 次；`demonstrateMmapKafkaStyle()` mmap 3 次；`demonstrateCompositeBufferConcept()` CompositeByteBuf |

**核心知识点：** Boss/Worker Reactor 模式；Channel 始终绑定同一 EventLoop 实现无锁串行化；责任链模式（Inbound/Outbound 双向传播）；LengthFieldBased 最通用解码方案。

---

### 4.3 study-storage——存储引擎原理

> 从关系型数据库到 NoSQL 到对象存储，覆盖后端存储层的核心原理。

#### 4.3.1 study-db——数据库原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `IndexDemo` | MySQL 索引底层原理 | `demoBPlusTree()` B+Tree 结构与范围查询；`demoClusteredVsSecondary()` 聚簇 vs 非聚簇；`demoCoveringIndex()` 覆盖索引；`demoJointIndexLeftmost()` 最左前缀 |
| `SQLOptimizerDemo` | MySQL 优化器原理 | `demoCostBasedOptimizer()` CBO 代价估算；`demoExplain()` EXPLAIN 输出；`demoOptimizerTrace()` 优化器追踪；`demoOrderByLimit()` Order By + Limit 优化 |
| `SQLRewriteDemo` | SQL 改写优化 | `demoSelectStarRewrite()` SELECT * 替换；`demoOrToUnion()` OR 转 UNION ALL；`demoLikeIndex()` LIKE 索引分析；`demoJoin()` 小表驱动大表；`demoSubquery()` 子查询转 JOIN |
| `ShardingJDBCDemo` | 分库分表原理 | `demoHashSharding()` 哈希取模路由；`demoSnowflake()` 雪花算法；`demoGeneMethod()` 基因法；`demoCrossShardAggregation()` 跨分片聚合 |
| `PostgreSQLDemo` | PostgreSQL 核心特性 | `demoMVCC()` MVCC 多版本并发控制；`demoVacuum()` VACUUM 垃圾回收；`demoGIN()` GIN 倒排索引；`demoBRIN()` BRIN 块范围索引 |
| `OracleKingbaseDemo` | Oracle/Kingbase 语法差异 | Oracle ROWNUM vs ROW_NUMBER 分页；MERGE INTO vs ON DUPLICATE KEY upsert；SEQUENCE vs AUTO_INCREMENT |
| `Q01_MySQL_Index` | 面试向索引深度解析 | `simulateICP()` 索引下推；`simulateLeftmostAndICP()` 最左前缀 + ICP |

**核心知识点：** B+Tree 分裂算法；聚簇索引 vs 二级索引的 IO 差异；CBO 代价模型（IO + CPU 成本）；雪花算法（41bit 时间戳 + 10bit 机器 + 12bit 序列）；基因法将 user_id 低 N 位嵌入 order_id 避免跨分片 JOIN。

#### 4.3.2 study-hbase——HBase 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `RowKeyDesignDemo` | RowKey 五种设计策略 | `hashMethodDemo()` 散列法；`saltMethodDemo()` 加盐法；`reverseMethodDemo()` 反转法；`timestampReverseDemo()` 时间戳反转；`preSplitRegionDemo()` 预分区 |
| `RegionSplitDemo` | Region 拆分全流程 | `fullSplitFlowDemo()` MemStore → Flush HFile → Split → Daughter Regions；`splitPolicyComparison()` 五种 Split 策略 |
| `SecondaryIndexDemo` | 三种二级索引方案 | `globalIndexDemo()` 全局索引；`localIndexDemo()` 本地索引；`coprocessorIndexDemo()` 协处理器 Observer |

**核心知识点：** RowKey 设计核心是避免 Region 热点写；IncreasingToUpperBound 策略公式；协处理器 postPut 钩子自动维护索引。

#### 4.3.3 study-mongodb——MongoDB 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `DocumentModelDemo` | 文档模型核心原理 | `bsonVsJsonDemo()` BSON vs JSON 编码；`nestedVsRefDemo()` 嵌套 vs 引用；`objectIdDemo()` ObjectId 生成与解码 |
| `IndexDemo` | 六种索引类型 | `singleFieldIndexDemo()` 单字段；`compoundIndexDemo()` 复合索引 + ESR 规则；`multiKeyIndexDemo()` 多键索引；`textIndexDemo()` 文本索引；`coveringIndexDemo()` 覆盖索引；`explainDemo()` explain 输出 |
| `ReplicaShardDemo` | 副本集与分片 | `replicaSetDemo()` 3 节点故障转移 + 选举；`oplogSyncDemo()` Oplog 增量同步；`shardingDemo()` Chunk 分裂与迁移 |

**核心知识点：** ESR 规则（Equality-Sort-Range）索引字段排列；嵌套 vs 引用的选型原则（高频一起读用嵌套，独立更新用引用）；Oplog capped collection 增量同步。

#### 4.3.4 study-minio——MinIO 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `ErasureCodingDemo` | 纠删码编解码原理 | `storageOverheadComparison()` RS vs 多副本开销；`writeFlowDemo()` EC 写流程；`readRecoveryDemo()` EC 读恢复 |
| `ObjectStorageDemo` | S3 对象存储核心功能 | `bucketDemo()` 桶 CRUD；`objectCRUDDemo()` 对象 CRUD；`multipartUploadDemo()` 分片上传三步骤；`presignedUrlDemo()` 临时签名链接；`metadataETagDemo()` ETag 校验 |
| `PolicyVersionDemo` | 桶策略与版本控制 | `policyDemo()` IAM Policy 权限评估；`versioningDemo()` 版本控制 + DeleteMarker；`lifecycleDemo()` 生命周期规则；`objectLockDemo()` WORM 锁定 |

**核心知识点：** 范德蒙德矩阵编码 + 高斯消元解码恢复；IAM Policy 评估逻辑（显式 Deny > 显式 Allow > 隐式拒绝）；Multipart Upload 三步骤；DeleteMarker 软删除。

---

### 4.4 study-distributed——分布式计算原理

> Flink 流处理与 Spark 批处理两大计算引擎的核心原理。

#### 4.4.1 study-flink——Flink 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `CheckpointDemo` | Checkpoint 机制 | `rdChandyLamportDemo()` Chandy-Lamport 算法；`rdCheckpointFlowDemo()` Barrier 对齐流程；`rdExactlyOnceSemantics()` 端到端两阶段提交 |
| `StateBackendDemo` | State 管理 | `rdKeyedStateDemo()` 四大 KeyedState 类型；`rdStateBackendCompare()` 三种后端对比；`rdRocksDBWriteAmplificationDemo()` LSM Tree 写放大；`rdStateTTLDemo()` TTL 惰性清理 |
| `WatermarkDemo` | 水位线原理 | `rdBoundedOutOfOrdernessDemo()` 允许乱序延迟；`rdMultiStreamWatermarkMerge()` 多流合并取最小；`rdIdleSourceHandling()` 空闲分区检测 |
| `WindowDemo` | 窗口机制 | `rdTumblingWindowDemo()` 滚动窗口；`rdSlidingWindowDemo()` 滑动窗口；`rdSessionWindowDemo()` 会话窗口；`rdCountWindowDemo()` 计数窗口；`rdSideOutputLateDataDemo()` 侧输出处理延迟数据 |

**核心知识点：** Barrier 对齐（多输入 Channel 等待）；Chandy-Lamport 异步快照；Watermark = maxTimestamp - outOfOrderness；ESR 规则（等值-排序-范围）；RocksDB LSM Tree 写放大（10-30x）。

#### 4.4.2 study-spark——Spark 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `RDDDemo` | RDD 核心原理 | `rdTransformationDemo()` 转换算子；`rdActionDemo()` 行动算子；`rdLazyEvaluationDemo()` 惰性求值；`rdNarrowVsWideDependencyDemo()` 窄依赖 vs 宽依赖；`rdLineageVsCheckpointDemo()` 血统 vs 截断 |
| `DAGShuffleDemo` | DAG 调度与 Shuffle | `dagStageDivisionDemo()` DAG 作业划分；`manualDAGSchedulerDemo()` 手写 DAGScheduler；`hashShuffleVsSortShuffleDemo()` HashShuffle vs SortShuffle |
| `DataFrameDemo` | DataFrame 与优化器 | `rdVsDataFrameVsDataset()` 三者对比；`catalystOptimizerDemo()` Catalyst 四阶段优化；`tungstenDemo()` Tungsten 堆外内存；`predicatePushdownAndColumnPruning()` 谓词下推与列裁剪 |
| `BroadcastJoinDemo` | 共享变量与 Join 策略 | `broadcastVsAccumulator()` Broadcast vs Accumulator；`mapJoinDemo()` Broadcast Hash Join；`sortMergeJoinDemo()` SortMerge Join；`bucketJoinDemo()` Bucket Join |

**核心知识点：** 惰性求值（Transformation 不触发计算，Action 才触发）；DAG 逆向遍历划分 Stage；宽依赖（ShuffleDependency）作为 Stage 边界；Catalyst 四阶段（Analysis → Logical Optimize → Physical Plan → CodeGen）；Broadcast Hash Join 小表 < 10MB 自动触发。

---

### 4.5 study-spring-eco——Spring 生态原理

> 从 Spring IoC/AOP 到 MyBatis ORM 再到 DevOps 工具链，覆盖 Spring 生态核心原理。

#### 4.5.1 study-spring-core——Spring IoC/AOP

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `IoCDemo` | 手写简易 IoC 容器 | `SimpleBeanFactory` 模拟 BeanDefinition 注册 + ConcurrentHashMap 单例池 + 反射实例化；对照 Spring AnnotationConfigApplicationContext |
| `AOPDemo` | JDK 代理 vs CGLIB 代理 | `LogInvocationHandler` JDK 动态代理；对比无接口类只能用 CGLIB 代理 |

**核心知识点：** 工厂模式（BeanFactory）；单例模式（ConcurrentHashMap 单例池）；IoC 容器核心骨架（BeanDefinition → 注册 → 实例化 → 缓存）；JDK 代理基于接口 + 反射，CGLIB 基于 ASM 字节码生成子类。

#### 4.5.2 study-mybatis——MyBatis 原理

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `MyBatisCoreDemo` | 手写 MyBatis 核心组件 | `demoFullFlow()` 完整调用链：SqlSessionFactory → SqlSession → Executor → StatementHandler → ResultSetHandler + 插件拦截器；一级/二级缓存 |
| `DynamicSQLDemo` | 手写动态 SQL 引擎 | `demoIfWhere()` if + where；`demoChooseWhen()` choose/when；`demoForeach()` foreach；`demoOGNL()` OGNL 表达式；`demoSQLInjectionDefense()` #{} vs ${} |
| `MyBatisPlusDemo` | 手写 MyBatis Plus 功能 | `demoBaseMapper()` BaseMapper CRUD；`demoPagination()` 分页插件；`demoOptimisticLock()` 乐观锁 CAS；`demoLogicDelete()` 逻辑删除 |

**核心知识点：** 代理模式（MapperProxy JDK 动态代理）；责任链模式（Interceptor → Plugin.wrap）；组合模式（SqlNode 树形结构）；缓存策略（一级 SqlSession 级 + 二级 Mapper 级）；CAS 乐观锁版本号比对。

#### 4.5.3 study-devops——DevOps 工具链

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `MavenDemo` | Maven 构建核心概念 | `printLifecycleTable()` 生命周期；`printScopeTable()` Scope 范围；`simulateDependencyConflict()` 依赖冲突解决；`simulateMultiModule()` 多模块聚合 |
| `GitDemo` | Git 版本控制核心 | `printWorkflowComparison()` GitFlow/GitHub Flow/TrunkBased；`simulateThreeWayMerge()` 3-Way Merge；`printResetModes()` reset 四种模式；`simulateReflogRecovery()` reflog 恢复 |
| `DockerDemo` | Docker 容器化核心 | `printDockerfileInstructions()` Dockerfile 指令；`simulateOverlay2Layers()` Overlay2 分层 + COW；`simulateDockerCompose()` Compose 编排；`printNetworkModes()` 网络模式 |
| `K8sDemo` | Kubernetes 核心概念 | `simulatePodPattern()` Sidecar + InitContainer；`simulateRollingUpdate()` 滚动更新；`printServiceTypes()` Service 类型；`simulateHPA()` HPA 扩缩容 |
| `LinuxDemo` | Linux 故障排查 | `printTopFields()` top 字段详解；`printFreeMemory()` 内存分析；`simulateTimeWaitAnalysis()` TIME_WAIT 排查；`simulateDeadlockDetection()` jstack 死锁检测 |

**核心知识点：** 依赖仲裁三原则（最短路径 → 最先声明 → exclusions）；3-Way Merge 算法；Overlay2 分层存储 + copy-on-write；HPA 扩缩公式 `ceil(replicas * currentCPU / targetCPU)`。

---

## 5. 模块依赖关系图

```
                          ┌─────────────────┐
                          │  custom-study   │
                          │    (root POM)   │
                          └────────┬────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
             ┌──────▼──────┐ ┌────▼─────┐ ┌──────▼──────┐
             │custom-study │ │          │ │             │
             │    -bom     │ │  ...     │ │    ...      │
             │ (版本管理)   │ │          │ │             │
             └─────────────┘ └──────────┘ └─────────────┘

知识依赖关系（学习先后顺序）：

  STAGE 1              STAGE 2              STAGE 3/4
┌──────────┐      ┌──────────────┐     ┌──────────────────┐
│  JDK     │      │  Spring      │     │  中间件/存储/     │
│  Core    │─────▶│  Eco         │────▶│  分布式           │
│          │      │              │     │                  │
│·泛型     │      │·IoC/AOP      │     │·Redis/MQ/Netty   │
│·集合     │      │·MyBatis      │     │·DB/HBase/MongoDB │
│·并发     │      │·Spring Boot  │     │·MinIO/ES         │
│·JVM      │      │·DevOps       │     │·Flink/Spark      │
│·NIO      │      │              │     │                  │
│·反射     │      │              │     │                  │
└──────────┘      └──────────────┘     └──────────────────┘
```

**Maven 依赖关系：**

- 所有子模块 → `custom-study-bom`（版本管理）
- `study-spring-core` → `spring-context`、`jakarta.annotation-api`
- `study-spring-boot` → `spring-boot`、`spring-boot-autoconfigure`
- 其余模块 → 仅 `lombok`（provided），纯 Java 标准库实现

---

## 6. 设计模式索引

| 设计模式 | 出现位置 | 具体应用 |
|---------|---------|---------|
| **代理模式** | `ProxyDemo`、`MyLock`、`MyBatisCoreDemo`、`AOPDemo` | JDK 动态代理、CGLIB 代理、MapperProxy、Plugin.wrap |
| **工厂模式** | `IoCDemo`、`MyBatisCoreDemo` | BeanFactory、SqlSessionFactory |
| **单例模式** | `IoCDemo`、`ReflectDemo` | ConcurrentHashMap 单例池、防御反射破坏单例 |
| **模板方法** | `MyLock`、`AQSDemo` | AQS acquire() 调用子类 tryAcquire() |
| **责任链** | `PipelineDemo`、`MyBatisCoreDemo` | Netty Pipeline 双向链表、MyBatis Interceptor 链 |
| **组合模式** | `DynamicSQLDemo` | SqlNode 树形结构 |
| **策略模式** | `WindowDemo`、`StateBackendDemo` | WindowAssigner/Trigger 可替换、ReducingState BinaryOperator |
| **观察者** | `SecondaryIndexDemo` | HBase 协处理器 postPut 钩子 |
| **构建器** | `MyBatisCoreDemo` | SqlSessionFactoryBuilder |
| **解释器** | `DynamicSQLDemo` | OGNL 表达式解析 |
| **CAS 自旋** | `CASDemo`、`MyLock`、`WatermarkDemo` | 无锁并发控制 |

---

## 7. 项目运行方式

### 7.1 环境要求

- **JDK** ≥ 21
- **Maven** ≥ 3.9
- **IDE** 推荐 IntelliJ IDEA

### 7.2 编译项目

```bash
# 克隆项目
git clone <repository-url>
cd custom-study

# 编译全部模块（跳过测试）
mvn clean compile -DskipTests

# 编译指定模块
mvn clean compile -pl study-jdk-core/study-concurrent -am
```

### 7.3 运行单个 Demo

**方式一：IDE 直接运行**

在 IntelliJ IDEA 中打开任意 Demo 类，右键 `main()` 方法 → Run。

**方式二：Maven exec 插件**

```bash
mvn exec:java -pl study-jdk-core/study-concurrent \
    -Dexec.mainClass="base.concurrent.CASDemo"
```

**方式三：StudyRunner 交互式启动**

```bash
# 先编译 study-overview 模块
mvn compile -pl study-jdk-core/study-overview

# 运行 StudyRunner
mvn exec:java -pl study-jdk-core/study-overview \
    -Dexec.mainClass="study.StudyRunner"
```

StudyRunner 支持以下命令：

| 命令 | 说明 |
|------|------|
| `list` | 列出所有可用 Demo |
| `run <类名>` | 通过反射运行指定 Demo |
| `find <关键词>` | 按关键词搜索 Demo |
| `interactive` | 进入交互式 REPL 模式 |

### 7.4 运行测试

```bash
# 运行全部测试
mvn test

# 运行指定模块测试
mvn test -pl study-jdk-core/study-concurrent
```

---

## 8. 学习路线建议

### STAGE 1：JDK 核心原理（基础底座）

| 顺序 | 模块 | 重点 Demo | 核心收获 |
|------|------|-----------|---------|
| 1 | study-generics | `TypeErasureDemo`、`PECSDemo` | 理解类型擦除机制与 PECS 原则 |
| 2 | study-collection | `HashMapDemo` | 掌握 HashMap 扰动函数与扩容机制 |
| 3 | study-concurrent | `CASDemo` → `AQSDemo` → `MyLock` | 从 CAS 到 AQS 到手写锁，递进理解并发 |
| 4 | study-jvm | `MemoryModelDemo` → `GCDemo` → `JVMTuningDemo` | 内存模型 → GC 算法 → 调优实战 |
| 5 | study-nio | `NIODemo` → `StickyPacketDemo` → `ZeroCopyDemo` | IO 模型演进与零拷贝 |
| 6 | study-reflect | `ReflectDemo` → `ProxyDemo` → `AnnotationDemo` | 反射 → 代理 → 注解，Spring 底层基础 |

### STAGE 2：Spring 生态（框架原理）

| 顺序 | 模块 | 重点 Demo | 核心收获 |
|------|------|-----------|---------|
| 1 | study-spring-core | `IoCDemo` → `AOPDemo` | IoC 容器核心骨架与代理机制 |
| 2 | study-mybatis | `MyBatisCoreDemo` → `DynamicSQLDemo` → `MyBatisPlusDemo` | ORM 框架完整调用链 |
| 3 | study-devops | `MavenDemo` → `GitDemo` → `DockerDemo` → `K8sDemo` | 工程化与运维知识体系 |

### STAGE 3/4：中间件与存储（进阶实战）

| 顺序 | 模块 | 重点 Demo | 核心收获 |
|------|------|-----------|---------|
| 1 | study-redis | `DataStructureDemo` → `CacheProblemDemo` → `ClusterDemo` | Redis 从底层到集群 |
| 2 | study-mq | `KafkaCoreDemo` → `MessageReliabilityDemo` → `TransactionDemo` | 消息可靠性保障全链路 |
| 3 | study-netty | `EventLoopDemo` → `PipelineDemo` → `StickyPacketDemo` | 网络编程模型与编解码 |
| 4 | study-db | `IndexDemo` → `SQLOptimizerDemo` → `ShardingJDBCDemo` | 数据库从索引到分库分表 |
| 5 | study-flink | `CheckpointDemo` → `WatermarkDemo` → `WindowDemo` | 流处理核心机制 |
| 6 | study-spark | `RDDDemo` → `DAGShuffleDemo` → `DataFrameDemo` | 批处理核心机制 |

---

> 本文档基于项目源码自动分析生成，最后更新时间：2026-05-16。
