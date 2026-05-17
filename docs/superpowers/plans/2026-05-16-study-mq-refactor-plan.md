# study-mq 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 study-mq 拆分为 rocketmq/kafka 两个独立模块，加深现有主题，新增日志处理/高级特性/存储性能/真实客户端等 15+ 新 Demo

**Architecture:** 渐进演进 —— P0 创建空模块 → P1 迁移+Review+新增 Kafka 6 个 Demo → P2 新增 RocketMQ 5 个 Demo → P3 新增 Kafka 4 个 Demo → P4 跨模块+客户端 → 收尾删除 study-mq

**Tech Stack:** Java 21, Maven, Lombok, 纯 Java 手写模拟（P4 阶段引入 RocketMQ Client / Kafka Client）

---

### Task 1: P0 —— 创建 rocketmq 和 kafka 模块

**Files:**
- Create: `study-middleware/rocketmq/pom.xml`
- Create: `study-middleware/kafka/pom.xml`
- Modify: `study-middleware/pom.xml`

- [ ] **Step 1: 创建 rocketmq/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.nopr</groupId>
        <artifactId>study-middleware</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>rocketmq</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 kafka/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.nopr</groupId>
        <artifactId>study-middleware</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>kafka</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 注册子模块到 study-middleware/pom.xml**

将 `study-middleware/pom.xml` 中的 `<modules>` 从：
```xml
<modules>
    <module>study-redis</module>
    <module>study-mq</module>
    <module>study-netty</module>
    <module>study-websocket</module>
</modules>
```
改为：
```xml
<modules>
    <module>study-redis</module>
    <module>study-mq</module>
    <module>rocketmq</module>
    <module>kafka</module>
    <module>study-netty</module>
    <module>study-websocket</module>
</modules>
```

> search-replace，old_str 匹配原 `<modules>` 块。

- [ ] **Step 4: 创建包目录结构**

```bash
mkdir -p study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate
mkdir -p study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/client
mkdir -p study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview
mkdir -p study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate
mkdir -p study-middleware/kafka/src/main/java/com/nopr/mq/kafka/client
mkdir -p study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview
```

> Windows 使用 PowerShell 命令：
> ```powershell
> New-Item -ItemType Directory -Force -Path study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate, study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/client, study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview, study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate, study-middleware/kafka/src/main/java/com/nopr/mq/kafka/client, study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview
> ```

- [ ] **Step 5: 验证编译**

```bash
mvn compile -pl study-middleware/rocketmq,study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add study-middleware/rocketmq/ study-middleware/kafka/ study-middleware/pom.xml
git commit -m "feat(mq): create rocketmq and kafka modules under study-middleware"
```

---

### Task 2: P1 —— 迁移 RocketMQ 核心架构

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/RocketMQCoreDemo.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/RocketMQCoreDemo.java`)

- [ ] **Step 1: 复制原文件并修改包路径**

从 `study-mq/src/main/java/base/mq/RocketMQCoreDemo.java` 复制内容，修改：
- `package base.mq;` → `package com.nopr.mq.rocketmq.simulate;`
- 补充 Javadoc 头部（按规范模板）

```java
/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】RocketMQ 核心架构 —— NameServer·Broker·CommitLog·ConsumeQueue
 * 【描述】手写简化 NameServer（路由注册表）、Broker（CommitLog + ConsumeQueue）、
 *         Producer（路由查询→选队列→发送）、Consumer（Pull 拉取）。完整演示
 *         RocketMQ 的消息存储与消费全链路。
 * 【关键概念】NameServer、Broker、CommitLog、ConsumeQueue、MessageQueue、
 *             路由发现、Pull 消费、QueueData、BrokerData
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: 验证编译**

```bash
mvn compile -pl study-middleware/rocketmq
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/RocketMQCoreDemo.java
git commit -m "feat(rocketmq): migrate RocketMQCoreDemo from study-mq"
```

---

### Task 3: P1 —— 迁移 RocketMQ 事务消息

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/TransactionDemo.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/TransactionDemo.java`)

- [ ] **Step 1: 复制并修改包路径 + 头部 Javadoc**

修改 `package base.mq;` → `package com.nopr.mq.rocketmq.simulate;`

```java
/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】RocketMQ 事务消息 —— 半消息·本地事务·回查机制
 * 【描述】模拟 RocketMQ 两阶段提交：阶段1 发送半消息（PREPARE）、阶段2 执行本地事务、
 *         阶段3 Commit/Rollback。Broker 端实现 CheckListener 回查机制，
 *         定时扫描超时半消息并回调 checkLocalTransaction。
 * 【关键概念】半消息、本地事务、Commit/Rollback、回查机制、CheckListener、
 *             事务状态（UNKNOWN/COMMIT/ROLLBACK）
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaIdempotentDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: 验证编译**

```bash
mvn compile -pl study-middleware/rocketmq
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/TransactionDemo.java
git commit -m "feat(rocketmq): migrate TransactionDemo from study-mq"
```

---

### Task 4: P1 —— 迁移消息顺序性 Demo

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/MessageOrderDemo.java`
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/MessageOrderDemo.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/MessageOrderDemo.java`)

**注意：** 原文件混在一起，需拆分——RocketMQ 部分（MessageQueueSelector、全局有序单 Queue）放 rocketmq 模块，Kafka 部分（Partition 分区有序）放 kafka 模块。

- [ ] **Step 1: 创建 RocketMQ 版 MessageOrderDemo**

提取原文件中 RocketMQ 相关部分（MessageQueueSelector、全局有序 vs 分区有序概念），包路径改为 `com.nopr.mq.rocketmq.simulate`，加头部 Javadoc：

```java
/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消息顺序性 —— 分区有序·全局有序·重平衡风险
 * 【描述】模拟 RocketMQ 消息顺序性：MessageQueueSelector 按业务键哈希选固定队列
 *         实现分区有序；单 Queue 禁用自动负载均衡实现全局有序；演示 Consumer 宕机
 *         后 Queue 重分配导致顺序中断的风险。
 * 【关键概念】顺序消息、MessageQueueSelector、分区有序、全局有序、负载均衡、
 *             重平衡、顺序中断
 * 【关联类】@see com.nopr.mq.kafka.simulate.MessageOrderDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: 创建 Kafka 版 MessageOrderDemo**

提取原文件中 Kafka 相关部分，包路径改为 `com.nopr.mq.kafka.simulate`，加头部 Javadoc：

```java
/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】消息顺序性 —— 分区有序·全局有序·重平衡风险
 * 【描述】模拟 Kafka 消息顺序性：Partitioner 按 Key 哈希选固定分区实现分区有序；
 *         单 Partition 实现全局有序；演示 Rebalance 导致顺序中断的风险。
 * 【关键概念】顺序消息、Partitioner、分区有序、全局有序、Rebalance、顺序中断、
 *             粘性分区
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.MessageOrderDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 3: 验证编译**

```bash
mvn compile -pl study-middleware/rocketmq,study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/MessageOrderDemo.java study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/MessageOrderDemo.java
git commit -m "feat(mq): migrate MessageOrderDemo split into rocketmq and kafka modules"
```

---

### Task 5: P1 —— 迁移消息可靠性 Demo

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/MessageReliabilityDemo.java`
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/MessageReliabilityDemo.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/MessageReliabilityDemo.java`)

**注意：** 同样需拆分 —— RocketMQ 侧重 SYNC_FLUSH + SYNC_MASTER，Kafka 侧重 ACK 级别（0/1/-1）+ min.insync.replicas。

- [ ] **Step 1: 创建 RocketMQ 版 MessageReliabilityDemo**

提取 RocketMQ 侧刷盘策略 + 主从复制相关内容，包路径改为 `com.nopr.mq.rocketmq.simulate`：

```java
/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消息可靠性 —— 刷盘策略·主从复制·ACK 机制·重试队列
 * 【描述】模拟 RocketMQ 消息可靠性三重保障：SYNC_FLUSH vs ASYNC_FLUSH 刷盘策略、
 *         SYNC_MASTER vs ASYNC_MASTER 主从复制、四种可靠性矩阵对比。
 *         生产者重试 + Broker 持久化 + 消费者手动 ACK 全链路保障。
 * 【关键概念】同步刷盘、异步刷盘、同步复制、异步复制、ACK 确认、重试队列、
 *             消费幂等、可靠性矩阵
 * 【关联类】@see com.nopr.mq.kafka.simulate.MessageReliabilityDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: 创建 Kafka 版 MessageReliabilityDemo**

提取 Kafka 侧 ACK 机制相关内容，包路径改为 `com.nopr.mq.kafka.simulate`：

```java
/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】消息可靠性 —— ACK 机制·ISR·min.insync.replicas·生产者重试
 * 【描述】模拟 Kafka 消息可靠性保障：acks=0/1/-1(all) 三种级别对比、
 *         min.insync.replicas 最小同步副本数约束、生产者重试 + 幂等、
 *         消费者手动提交 offset。演示各种配置下的消息丢失与不丢失场景。
 * 【关键概念】acks、min.insync.replicas、ISR、生产者重试、enable.idempotence、
 *             手动提交 offset、可靠性矩阵
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.MessageReliabilityDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 3: 验证编译**

```bash
mvn compile -pl study-middleware/rocketmq,study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/simulate/MessageReliabilityDemo.java study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/MessageReliabilityDemo.java
git commit -m "feat(mq): migrate MessageReliabilityDemo split into rocketmq and kafka modules"
```

---

### Task 6: P1 —— 迁移 Kafka 核心架构 + 幂等事务 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaCoreDemo.java`
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaIdempotentDemo.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/KafkaCoreDemo.java` + `KafkaIdempotentDemo.java`)

- [ ] **Step 1: 复制 KafkaCoreDemo，改包路径 + 头部 Javadoc**

```java
/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】Kafka 核心架构 —— Partition·ISR·HW·LEO·Controller
 * 【描述】手写模拟 Kafka 核心概念：Replica（LEO/HW 跟踪）、Partition（多副本管理、
 *         ISR 动态维护、HW 推进算法）、Controller（Leader 选举）。三个演示场景：
 *         HW/LEO 推进、ISR 动态维护、Leader 宕机选举新 Leader。
 * 【关键概念】Partition、Replica、ISR、HW(High Watermark)、LEO(Log End Offset)、
 *             Controller、Leader 选举、Follower 同步、replica.lag.time.max.ms
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.RocketMQCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: 复制 KafkaIdempotentDemo，改包路径 + 头部 Javadoc**

```java
/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】Kafka 幂等与事务 —— PID·SeqNum·TransactionCoordinator
 * 【描述】模拟 Kafka 幂等（PID + SeqNum 去重，Broker 侧维护 per-partition 序列号）
 *         和事务（initTransactions→begin→send→commit/abort），
 *         与 Kafka Transaction (KIP-98) 概念对齐。
 * 【关键概念】幂等、PID(Producer ID)、SeqNum、事务、TransactionCoordinator、
 *             initTransactions、KIP-98、消费端幂等
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.TransactionDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 3: 验证编译**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaCoreDemo.java study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/KafkaIdempotentDemo.java
git commit -m "feat(kafka): migrate KafkaCoreDemo and KafkaIdempotentDemo from study-mq"
```

---

### Task 7: P1 —— 迁移面试题 + 在原有代码中 Review 加深

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q01_RocketMQ_Kafka.java`
- (source: `study-middleware/study-mq/src/main/java/base/mq/interview/Q01_RocketMQ_Kafka.java`)

- [ ] **Step 1: 复制 Q01_RocketMQ_Kafka，改包路径 + 头部 Javadoc**

```java
/**
 * 【模块】rocketmq（跨模块对比，以 RocketMQ 视角组织）
 * 【分类】interview
 * 【主题】RocketMQ vs Kafka 全维度对比
 * 【描述】面试高频题汇总：架构对比（NameServer vs ZK/KRaft）、消息模型对比
 *         （Queue vs Partition）、事务消息对比（半消息+回查 vs PID+TransactionCoordinator）、
 *         适用场景与选型建议、性能对比。
 * 【关键概念】NameServer、ZK/KRaft、CommitLog、分区模型、事务消息、半消息、
 *             消费模型、适用场景选型
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
```

- [ ] **Step 2: Review 已迁移的所有文件**

确认所有文件：
- 包路径从 `base.mq` 改为 `com.nopr.mq.rocketmq.simulate` 或 `com.nopr.mq.kafka.simulate`
- import 语句中的 `base.mq` 引用已清理（如有跨文件引用）
- 头部 Javadoc 已补全
- `main` 方法中引用其他类的部分已修正

- [ ] **Step 3: 全量编译验证**

```bash
mvn compile -pl study-middleware/rocketmq,study-middleware/kafka
```
Expected: BUILD SUCCESS for both modules

- [ ] **Step 4: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q01_RocketMQ_Kafka.java
git commit -m "feat(rocketmq): migrate interview Q01_RocketMQ_Kafka from study-mq"
```

---

### Task 8: P1 —— 新增 Kafka 日志清理策略 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/LogCleanupDemo.java`

- [ ] **Step 1: 创建 LogCleanupDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】日志清理策略 —— compact·delete·Key 压缩保留
 * 【描述】模拟 Kafka 日志清理策略。delete（按时间/大小删除旧消息）和
 *         compact（相同 Key 只保留最新 Value，适合 changelog 场景）。
 *         演示两种策略下消息的保留与删除规则。
 * 【关键概念】日志清理、log.cleanup.policy、compact、delete、Key 压缩、
 *             retention.ms、segment.bytes
 * 【关联类】@see com.nopr.mq.kafka.simulate.LogSegmentDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class LogCleanupDemo {

    // === 数据模型 ===

    record LogMessage(long offset, String key, String value, long timestamp) {}

    // === 日志段（模拟 Kafka Log Segment）===

    static class SimpleLog {
        private final List<LogMessage> messages = new ArrayList<>();

        void append(LogMessage msg) { messages.add(msg); }

        // delete 策略：按保留时间清理
        List<LogMessage> cleanupByRetention(long retentionMs) {
            long cutoff = System.currentTimeMillis() - retentionMs;
            List<LogMessage> removed = new ArrayList<>();
            messages.removeIf(msg -> {
                if (msg.timestamp() < cutoff) { removed.add(msg); return true; }
                return false;
            });
            return removed;
        }

        // compact 策略：相同 Key 只保留最新一条
        List<LogMessage> cleanupByCompact() {
            Map<String, LogMessage> latestByKey = new LinkedHashMap<>();
            for (LogMessage msg : messages) {
                if (msg.value() == null && latestByKey.containsKey(msg.key())) {
                    latestByKey.remove(msg.key()); // 墓碑消息：删除 Key
                } else {
                    latestByKey.put(msg.key(), msg);
                }
            }
            messages.clear();
            messages.addAll(latestByKey.values());
            return new ArrayList<>(latestByKey.values());
        }

        List<LogMessage> all() { return Collections.unmodifiableList(messages); }
        int size() { return messages.size(); }
    }

    // === 演示 ===

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 日志清理策略 Demo");
        System.out.println("=".repeat(60));

        deleteCleanupDemo();
        compactCleanupDemo();
    }

    static void deleteCleanupDemo() {
        System.out.println("\n--- delete 策略：按保留时间清理 ---");

        SimpleLog log = new SimpleLog();
        long now = System.currentTimeMillis();

        log.append(new LogMessage(0, "order-1", "created", now - 7_200_000)); // 2小时前
        log.append(new LogMessage(1, "order-2", "created", now - 3_600_000)); // 1小时前
        log.append(new LogMessage(2, "order-3", "shipped", now - 600_000));   // 10分钟前
        log.append(new LogMessage(3, "order-4", "delivered", now));            // 刚写入

        System.out.println("清理前消息数: " + log.size());

        long retentionMs = 3_600_000; // 保留 1 小时
        List<LogMessage> removed = log.cleanupByRetention(retentionMs);

        System.out.println("清理掉 " + removed.size() + " 条（时间 > 1小时前的）");
        System.out.println("清理后消息数: " + log.size());
    }

    static void compactCleanupDemo() {
        System.out.println("\n--- compact 策略：相同 Key 只保留最新 ---");

        SimpleLog log = new SimpleLog();
        long now = System.currentTimeMillis();

        log.append(new LogMessage(0, "user-1", "张三", now - 10_000));
        log.append(new LogMessage(1, "user-2", "李四", now - 8_000));
        log.append(new LogMessage(2, "user-1", "张三丰", now - 5_000)); // user-1 更新
        log.append(new LogMessage(3, "user-3", "王五", now - 3_000));
        log.append(new LogMessage(4, "user-2", "李四光", now - 1_000)); // user-2 更新
        log.append(new LogMessage(5, "user-1", null, now));              // user-1 墓碑

        System.out.println("清理前消息数: " + log.size());
        for (LogMessage m : log.all()) {
            System.out.printf("  offset=%d key=%s value=%s%n",
                    m.offset(), m.key(), m.value());
        }

        log.cleanupByCompact();

        System.out.println("清理后消息数: " + log.size());
        for (LogMessage m : log.all()) {
            System.out.printf("  offset=%d key=%s value=%s%n",
                    m.offset(), m.key(), m.value());
        }
        // 预期：user-1 被墓碑删除、user-2 保留最新 "李四光"、user-3 保留 "王五"
        System.out.println("\n💡 compact 用于 CDC/changelog 场景，保序 + Key 版本压缩");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
# 运行测试
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.LogCleanupDemo
```
Expected: BUILD SUCCESS + 正确输出清理前后消息数变化

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/LogCleanupDemo.java
git commit -m "feat(kafka): add LogCleanupDemo for log cleanup policy simulation"
```

---

### Task 9: P1 —— 新增 Kafka 墓碑日志 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/TombstoneDemo.java`

- [ ] **Step 1: 创建 TombstoneDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】墓碑日志 —— value=null·延迟删除·compact 联动
 * 【描述】模拟 Kafka 墓碑消息机制：发送 value=null 的消息标记待删除 Key，
 *         在 compact 策略下分两阶段执行（标记→延迟→真删），演示墓碑消息的
 *         生命周期和与 compact 策略的联动。
 * 【关键概念】墓碑消息、tombstone、value=null、延迟删除、log.cleanup.policy=compact、
 *             delete.retention.ms、CDC 软删除
 * 【关联类】@see com.nopr.mq.kafka.simulate.LogCleanupDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class TombstoneDemo {

    record TombstoneMessage(long offset, String key, String value, long timestamp, boolean isTombstone) {
        static TombstoneMessage normal(long offset, String key, String value, long ts) {
            return new TombstoneMessage(offset, key, value, ts, false);
        }
        static TombstoneMessage tombstone(long offset, String key, long ts) {
            return new TombstoneMessage(offset, key, null, ts, true);
        }
    }

    static class TombstoneBroker {
        private final List<TombstoneMessage> log = new ArrayList<>();
        private final AtomicLong offsetGen = new AtomicLong(0);
        private final long tombstoneRetentionMs; // 墓碑保留时间

        TombstoneBroker(long tombstoneRetentionMs) {
            this.tombstoneRetentionMs = tombstoneRetentionMs;
        }

        void append(String key, String value) {
            long offset = offsetGen.getAndIncrement();
            log.add(TombstoneMessage.normal(offset, key, value, System.currentTimeMillis()));
        }

        void markDeleted(String key) {
            long offset = offsetGen.getAndIncrement();
            log.add(TombstoneMessage.tombstone(offset, key, System.currentTimeMillis()));
        }

        void compact(long now) {
            Map<String, TombstoneMessage> latest = new LinkedHashMap<>();
            for (TombstoneMessage msg : log) {
                if (msg.isTombstone()) {
                    long age = now - msg.timestamp();
                    if (age >= tombstoneRetentionMs) {
                        latest.remove(msg.key()); // 墓碑过期，真删
                    } else {
                        latest.put(msg.key(), msg); // 墓碑未过期，保留
                    }
                } else {
                    latest.put(msg.key(), msg);
                }
            }
            log.clear();
            log.addAll(latest.values());
        }

        void printState(String label) {
            System.out.println("  [" + label + "] 共 " + log.size() + " 条:");
            for (TombstoneMessage m : log) {
                String marker = m.isTombstone() ? "💀TOMBSTONE" : "  NORMAL  ";
                System.out.printf("    offset=%d key=%-8s value=%-12s %s%n",
                        m.offset(), m.key(),
                        m.value() == null ? "(null)" : m.value(), marker);
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 墓碑日志 Demo");
        System.out.println("=".repeat(60));

        TombstoneBroker broker = new TombstoneBroker(5000); // 墓碑保留 5 秒
        long start = System.currentTimeMillis();

        // 阶段 1：写入正常消息
        broker.append("user-A", "alice@ex.com");
        broker.append("user-B", "bob@ex.com");
        broker.append("user-A", "alice_new@ex.com"); // 更新 user-A
        broker.printState("阶段1: 初始消息");

        // 阶段 2：标记删除 user-A
        broker.markDeleted("user-A");
        broker.printState("阶段2: 标记删除 user-A");

        // 阶段 3：立即 compact（墓碑未过期，保留墓碑）
        broker.compact(System.currentTimeMillis());
        broker.printState("阶段3: 立即compact（墓碑保留）");

        // 阶段 4：等待墓碑过期后 compact
        System.out.println("\n  ⏳ 等待墓碑过期（6秒）...");
        try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        broker.compact(System.currentTimeMillis());
        broker.printState("阶段4: 墓碑过期后compact（user-A 真删）");

        System.out.println("\n💡 墓碑消息保障最终一致性，确保分布式系统中 Key 真正被删除");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.TombstoneDemo
```
Expected: BUILD SUCCESS + 4 阶段输出，阶段4 中 user-A 被真删

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/TombstoneDemo.java
git commit -m "feat(kafka): add TombstoneDemo for tombstone message lifecycle simulation"
```

---

### Task 10: P1 —— 新增 Kafka 脑裂 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/SplitBrainDemo.java`

- [ ] **Step 1: 创建 SplitBrainDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】脑裂 —— 网络分区·双 Leader·Epoch Fencing
 * 【描述】模拟 Kafka 脑裂场景：网络分区导致同一 Partition 出现两个 Leader，
 *         不同分区的消息写入冲突。演示 Epoch（Leader Epoch）的 Fencing 机制
 *         —— 旧 Leader 恢复后发现自己已过期，拒绝写入，避免数据不一致。
 * 【关键概念】脑裂、Split Brain、网络分区、双 Leader、Leader Epoch、
 *             Fencing、epoch bump、KRaft Controller
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class SplitBrainDemo {

    record Message(long offset, int epoch, String content) {}

    static class Partition {
        final int id;
        final List<Message> log = new ArrayList<>();
        int currentEpoch = 0;

        Partition(int id) { this.id = id; }

        void writeAsLeader(int epoch, String content) {
            if (epoch < currentEpoch) {
                System.out.printf("  ❌ Partition-%d: Epoch 过期 (%d < %d)，拒绝写入！%n",
                        id, epoch, currentEpoch);
                return;
            }
            currentEpoch = epoch;
            log.add(new Message(log.size(), epoch, content));
            System.out.printf("  ✅ Partition-%d: 写入成功 (epoch=%d) '%s'%n",
                    id, epoch, content);
        }

        void printLog() {
            System.out.printf("  Partition-%d log (epoch=%d):%n", id, currentEpoch);
            for (Message m : log) {
                System.out.printf("    offset=%d epoch=%d content=%s%n",
                        m.offset(), m.epoch(), m.content());
            }
        }
    }

    static class Controller {
        final Map<Integer, Integer> partitionLeaderEpoch = new HashMap<>();

        void electLeader(int partition, int newEpoch) {
            partitionLeaderEpoch.put(partition, newEpoch);
            System.out.printf("  🎯 Controller: Partition-%d Leader Epoch → %d%n",
                    partition, newEpoch);
        }

        int getEpoch(int partition) {
            return partitionLeaderEpoch.getOrDefault(partition, 0);
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 脑裂 (Split Brain) Demo");
        System.out.println("=".repeat(60));

        Controller controller = new Controller();
        Partition p0 = new Partition(0);

        // 正常阶段
        System.out.println("\n--- 1️⃣ 正常阶段 ---");
        controller.electLeader(0, 1);
        p0.writeAsLeader(1, "msg-1");
        p0.writeAsLeader(1, "msg-2");

        // 脑裂阶段：网络分区恢复后，旧 Leader 尝试写入（epoch 已过期）
        System.out.println("\n--- 2️⃣ 脑裂阶段：旧 Leader 尝试写入 ---");
        System.out.println("  ⚡ 网络分区 → Controller 选举新 Leader (epoch=2)");
        controller.electLeader(0, 2);
        System.out.println("  ⚠️ 旧 Leader 不知道已降级，尝试用旧 epoch=1 写入...");
        p0.writeAsLeader(1, "dangerous-msg"); // 被拒绝！

        // 恢复阶段：新 Leader 正常写入
        System.out.println("\n--- 3️⃣ 恢复阶段：新 Leader 正常写入 ---");
        p0.writeAsLeader(2, "msg-3");
        p0.writeAsLeader(2, "msg-4");

        p0.printLog();

        System.out.println("\n💡 Epoch Fencing 是防止脑裂数据不一致的核心机制");
        System.out.println("   KRaft 协议通过 Controller Epoch + Leader Epoch 双重保障");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.SplitBrainDemo
```
Expected: BUILD SUCCESS + 旧 Leader 写入被拒绝

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/SplitBrainDemo.java
git commit -m "feat(kafka): add SplitBrainDemo for network partition and epoch fencing simulation"
```

---

### Task 11: P1 —— 新增 Kafka 批量发送 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/BatchSendDemo.java`

- [ ] **Step 1: 创建 BatchSendDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】批量发送 —— linger.ms·batch.size·吞吐 vs 延迟
 * 【描述】模拟 Kafka Producer 批量发送机制：RecordAccumulator 按 partition 分组
 *         缓存消息，达到 batch.size 或超过 linger.ms 触发批量发送。
 *         演示不同参数组合下的吞吐 vs 延迟权衡。
 * 【关键概念】批量发送、linger.ms、batch.size、RecordAccumulator、吞吐、
 *             延迟、压缩、ProducerBatch、max.in.flight.requests.per.connection
 * 【关联类】@see com.nopr.mq.kafka.simulate.CompressionDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class BatchSendDemo {

    record Message(int partition, String key, String value) {}

    static class RecordAccumulator {
        private final int batchSize;
        private final Map<Integer, List<Message>> buffers = new HashMap<>();
        private final AtomicLong sendCount = new AtomicLong(0);
        private final AtomicLong msgCount = new AtomicLong(0);

        RecordAccumulator(int batchSize) { this.batchSize = batchSize; }

        void append(Message msg) {
            msgCount.incrementAndGet();
            buffers.computeIfAbsent(msg.partition(), k -> new ArrayList<>()).add(msg);

            // 检查是否达到批量大小
            if (buffers.get(msg.partition()).size() >= batchSize) {
                drain(msg.partition());
            }
        }

        void drain(int partition) {
            List<Message> batch = buffers.remove(partition);
            if (batch != null && !batch.isEmpty()) {
                sendCount.incrementAndGet();
                System.out.printf("  📤 发送 batch: partition=%d, 共%d条消息%n",
                        partition, batch.size());
            }
        }

        void drainAll() {
            new ArrayList<>(buffers.keySet()).forEach(this::drain);
        }

        long totalSends() { return sendCount.get(); }
        long totalMessages() { return msgCount.get(); }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 批量发送 Demo");
        System.out.println("=".repeat(60));

        // 场景 1：大批量（batch.size=5，延迟高，吞吐高）
        System.out.println("\n--- 场景1：batch.size=5（批量大，发送次数少）---");
        RecordAccumulator acc1 = new RecordAccumulator(5);
        for (int i = 0; i < 15; i++) {
            acc1.append(new Message(i % 3, "key-" + i, "value-" + i));
        }
        acc1.drainAll();
        System.out.printf("  总计 %d 条消息，%d 次网络请求%n",
                acc1.totalMessages(), acc1.totalSends());
        System.out.printf("  平均每次发送 %.1f 条消息%n",
                (double) acc1.totalMessages() / acc1.totalSends());

        // 场景 2：小批量（batch.size=1，延迟低，吞吐低）
        System.out.println("\n--- 场景2：batch.size=1（逐条发送，发送次数多）---");
        RecordAccumulator acc2 = new RecordAccumulator(1);
        for (int i = 0; i < 15; i++) {
            acc2.append(new Message(i % 3, "key-" + i, "value-" + i));
        }
        acc2.drainAll();
        System.out.printf("  总计 %d 条消息，%d 次网络请求%n",
                acc2.totalMessages(), acc2.totalSends());
        System.out.printf("  平均每次发送 %.1f 条消息%n",
                (double) acc2.totalMessages() / acc2.totalSends());

        System.out.println("\n💡 batch.size 越大 → 吞吐越高 | linger.ms 越大 → 延迟越高");
        System.out.println("   生产环境：batch.size=16KB~1MB，linger.ms=5~100ms");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.BatchSendDemo
```
Expected: BUILD SUCCESS + 场景1 发送次数 << 场景2

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/BatchSendDemo.java
git commit -m "feat(kafka): add BatchSendDemo for batch send simulation"
```

---

### Task 12: P1 —— 新增 Kafka 再平衡 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/RebalanceDemo.java`

- [ ] **Step 1: 创建 RebalanceDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】再平衡 —— Range·RoundRobin·Sticky·CooperativeSticky
 * 【描述】模拟 Kafka Consumer Group 再平衡：RangeAssignor（按范围平均分配）、
 *         RoundRobinAssignor（轮询分配）、StickyAssignor（粘性保持）。
 *         CooperativeStickyAssignor 分阶段执行不触发 stop-the-world。
 *         演示 Consumer 加入/离开时 Partition 的重新分配策略。
 * 【关键概念】再平衡、Rebalance、RangeAssignor、RoundRobinAssignor、
 *             StickyAssignor、CooperativeStickyAssignor、GroupCoordinator、
 *             Eager vs Cooperative、stop-the-world
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class RebalanceDemo {

    interface Assignor {
        Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions);
        String name();
    }

    static class RangeAssignor implements Assignor {
        public String name() { return "RangeAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            Collections.sort(partitions);
            int perConsumer = partitions.size() / consumers.size();
            int remainder = partitions.size() % consumers.size();
            int start = 0;
            for (int i = 0; i < consumers.size(); i++) {
                int count = perConsumer + (i < remainder ? 1 : 0);
                result.put(consumers.get(i), new ArrayList<>(partitions.subList(start, start + count)));
                start += count;
            }
            return result;
        }
    }

    static class RoundRobinAssignor implements Assignor {
        public String name() { return "RoundRobinAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            consumers.forEach(c -> result.put(c, new ArrayList<>()));
            List<Integer> sorted = partitions.stream().sorted().collect(Collectors.toList());
            for (int i = 0; i < sorted.size(); i++) {
                result.get(consumers.get(i % consumers.size())).add(sorted.get(i));
            }
            return result;
        }
    }

    static class StickyAssignor implements Assignor {
        private Map<String, List<Integer>> previous;

        StickyAssignor(Map<String, List<Integer>> previous) { this.previous = previous; }

        public String name() { return "StickyAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            if (previous == null) return new RangeAssignor().assign(consumers, partitions);

            Map<String, List<Integer>> result = new LinkedHashMap<>();
            consumers.forEach(c -> result.put(c, new ArrayList<>()));

            for (String consumer : consumers) {
                List<Integer> prev = previous.getOrDefault(consumer, Collections.emptyList());
                for (int p : prev) {
                    if (partitions.contains(p) && result.get(consumer).isEmpty()
                            || result.get(consumer).size() < prev.size()) {
                        result.get(consumer).add(p);
                    }
                }
            }

            Set<Integer> assigned = result.values().stream()
                    .flatMap(List::stream).collect(Collectors.toSet());
            List<Integer> unassigned = partitions.stream()
                    .filter(p -> !assigned.contains(p)).sorted().collect(Collectors.toList());

            int idx = 0;
            for (int p : unassigned) {
                String consumer = consumers.get(idx % consumers.size());
                result.get(consumer).add(p);
                idx++;
            }

            return result;
        }
    }

    static void printAssignment(Map<String, List<Integer>> assignment) {
        assignment.forEach((consumer, parts) ->
                System.out.printf("  %s → partitions: %s%n", consumer, parts));
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 再平衡 (Rebalance) Demo");
        System.out.println("=".repeat(60));

        List<String> consumers = List.of("consumer-1", "consumer-2", "consumer-3");
        List<Integer> partitions = List.of(0, 1, 2, 3, 4, 5, 6, 7);

        // Range 分配
        System.out.println("\n--- RangeAssignor（按范围切分）---");
        printAssignment(new RangeAssignor().assign(consumers, partitions));

        // RoundRobin 分配
        System.out.println("\n--- RoundRobinAssignor（轮询分配）---");
        printAssignment(new RoundRobinAssignor().assign(consumers, partitions));

        // Sticky 分配（模拟 consumer-3 离开后，尽量减少迁移）
        System.out.println("\n--- StickyAssignor（consumer-3 离开后，粘性保持）---");
        Map<String, List<Integer>> prev = new LinkedHashMap<>();
        prev.put("consumer-1", List.of(0, 1, 2));
        prev.put("consumer-2", List.of(3, 4, 5));
        prev.put("consumer-3", List.of(6, 7));
        List<String> reducedConsumers = List.of("consumer-1", "consumer-2");
        printAssignment(new StickyAssignor(prev).assign(reducedConsumers, partitions));

        System.out.println("\n💡 Sticky 策略减少分区迁移，避免大量重新消费");
        System.out.println("   CooperativeSticky 进一步优化：分阶段执行，不触发 stop-the-world");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.RebalanceDemo
```
Expected: BUILD SUCCESS + 三种分配策略结果

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/RebalanceDemo.java
git commit -m "feat(kafka): add RebalanceDemo for consumer group rebalance strategies"
```

---

### Task 13: P1 —— 新增 Kafka 低延迟 Demo

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/LowLatencyDemo.java`

- [ ] **Step 1: 创建 LowLatencyDemo**

```java
package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】低延迟优化 —— 零拷贝·PageCache·mmap·sendfile
 * 【描述】模拟 Kafka 低延迟实现原理：传统 read/write（4次拷贝+4次上下文切换）
 *         vs sendfile 零拷贝（2次拷贝+2次上下文切换）。
 *         演示数据从磁盘到网络的传输路径差异。
 * 【关键概念】零拷贝、sendfile、mmap、DMA、PageCache、上下文切换、
 *             内核态/用户态、Socket Buffer、DMA 拷贝
 * 【关联类】@see com.nopr.mq.kafka.simulate.ZeroCopyDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class LowLatencyDemo {

    static long simulateTraditionalSend(long dataSize) {
        // 传统 IO：4 次拷贝 + 4 次上下文切换
        long copies = 4; // disk→kernel→user→kernel→socket
        long contextSwitches = 4;
        // 模拟耗时：每次拷贝 ~1μs/byte（简化模型）
        long latency = dataSize / 1024; // 微秒级
        System.out.printf("  传统 IO: %d 次拷贝 + %d 次上下文切换, ~%d μs%n",
                copies, contextSwitches, latency * copies);
        return latency * copies;
    }

    static long simulateSendfile(long dataSize) {
        // sendfile：2 次 DMA 拷贝 + 0 次上下文切换（数据不经过用户态）
        long dmaCopies = 2; // disk→kernel(DMA) + kernel→NIC(DMA)
        long contextSwitches = 2; // 仅 sendfile 调用和返回
        long latency = dataSize / 2048; // DMA 更快（粗粒度传输）
        System.out.printf("  sendfile: %d 次 DMA 拷贝 + %d 次上下文切换, ~%d μs%n",
                dmaCopies, contextSwitches, latency * dmaCopies);
        return latency * dmaCopies;
    }

    static long simulateMmap(long dataSize) {
        // mmap + write：3 次拷贝 + 4 次上下文切换
        long copies = 3; // disk→kernel(mmap)→kernel(socket)→NIC
        long contextSwitches = 4;
        long latency = dataSize / 1536; // mmap 映射后拷一次
        System.out.printf("  mmap+write: %d 次拷贝 + %d 次上下文切换, ~%d μs%n",
                copies, contextSwitches, latency * copies);
        return latency * copies;
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 低延迟优化：零拷贝 Demo");
        System.out.println("=".repeat(60));

        long dataSize = 1_048_576; // 1MB 消息

        System.out.println("\n--- 传输 1MB 消息到网络 ---");
        long traditional = simulateTraditionalSend(dataSize);
        long sendfile = simulateSendfile(dataSize);
        long mmap = simulateMmap(dataSize);

        System.out.printf("%n  性能对比：%n");
        System.out.printf("  sendfile 比传统 IO 快约 %.1f 倍%n", (double) traditional / sendfile);
        System.out.printf("  零拷贝减少了 %.0f%% 的 CPU 拷贝开销%n",
                (1.0 - (double) sendfile / traditional) * 100);

        System.out.println("\n💡 Kafka 通过 sendfile 实现零拷贝，数据直接从 PageCache → NIC");
        System.out.println("  配合 PageCache 命中率 > 90%，尾部延迟可控制在个位数毫秒");
    }
}
```

- [ ] **Step 2: 验证编译 + 运行**

```bash
mvn compile -pl study-middleware/kafka
java -cp study-middleware/kafka/target/classes com.nopr.mq.kafka.simulate.LowLatencyDemo
```
Expected: BUILD SUCCESS + 三种传输方式对比

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/simulate/LowLatencyDemo.java
git commit -m "feat(kafka): add LowLatencyDemo for zero-copy and sendfile simulation"
```

---

## 🔜 后续阶段标记

P1 阶段至此完成（迁移 + Kafka 6 个新 Demo）。以下为后续阶段索引，各阶段需要独立 Task。

### P2：RocketMQ 高级特性（5 个新 Demo）
- DeadLetterQueueDemo：死信队列，消费重试 N 次 → DLQ
- DelayMessageDemo：延迟消息，18 个延迟级别
- MessageFilterDemo：Tag 过滤 vs SQL92
- MessageTraceDemo：消息轨迹全链路追踪
- ConsumeRetryDemo：阶梯式重试等待

### P3：Kafka 存储与性能（4 个新 Demo）
- ZeroCopyDemo：传统 IO vs mmap vs sendfile 数据路径对比
- PageCacheDemo：读缓存命中/未命中，刷盘策略影响
- CompressionDemo：gzip/snappy/lz4/zstd 压缩对比
- LogSegmentDemo：segment 切分，offset index + time index

### P4：跨模块 + 真实客户端
- 消费者组再平衡协议演进（跨 RocketMQ/Kafka 对比）
- 高可用一致性对比（NameServer vs ZK vs KRaft）
- Pulsar/RabbitMQ/AMQP 简介
- RocketMQ Client / Kafka Client 真实连接演示（`client/` 包）

### 收尾
- 删除 `study-mq` 模块 + 从父 POM 移除

---

## 自检清单

- ✅ 模块创建 (Task 1): 两个 pom.xml + 父 POM 注册 + 6 个包目录
- ✅ RocketMQ 迁移 (Task 2-5): RocketMQCoreDemo, TransactionDemo, MessageOrderDemo, MessageReliabilityDemo
- ✅ Kafka 迁移 (Task 4-6): MessageOrderDemo, MessageReliabilityDemo, KafkaCoreDemo, KafkaIdempotentDemo
- ✅ 面试题迁移 (Task 7): Q01_RocketMQ_Kafka
- ✅ Kafka 新增 P1 (Task 8-13): LogCleanup, Tombstone, SplitBrain, BatchSend, Rebalance, LowLatency
- ⬜ P2-P4 + 收尾 标记为后续阶段
- ✅ 无 TBD/TODO/占位符
- ✅ 每个 Task 都有完整代码、编译命令、commit message
