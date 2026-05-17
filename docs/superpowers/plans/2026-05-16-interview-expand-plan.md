# interview 包扩充实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 7 个面试题文件：Kafka 5 题（ISR/HW、幂等+事务、性能调优、存储设计、故障排查）+ RocketMQ 2 题（事务深入、5.0新特性）

**Architecture:** 纯 Java main 方法 Q&A 格式——printSection/printRow 表格输出 + 面试要点 Q&A 段落

**Tech Stack:** Java 17, 无外部依赖（纯字符串输出）

---

### Task 1: Kafka Q01_ISR_HW — ISR 与水位线

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q01_ISR_HW.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】ISR 与水位线 —— HW·LEO·acks·Leader Epoch
 * 【描述】深入解析 Kafka 数据可靠性核心机制：ISR(In-Sync Replicas) 收缩/扩张、
 *         HW(High Watermark) vs LEO(Log End Offset) 区别、acks 配置含义、
 *         min.insync.replicas 陷阱、Leader Epoch 解决日志截断问题。
 * 【关键概念】ISR、HW、LEO、acks、min.insync.replicas、Leader Epoch、
 *             unclean.leader.election.enable、日志截断、Follower 拉取
 * 【关联类】@see com.nopr.mq.kafka.simulate.SplitBrainDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q01_ISR_HW {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka ISR 与水位线 (HW/LEO) 面试专辑");
        System.out.println("=".repeat(70));

        isrMechanism();
        hwLeo();
        acksConfig();
        leaderEpoch();
        interviewQA();
    }

    static void isrMechanism() {
        printSection("1. ISR 机制 (In-Sync Replicas)");
        System.out.println("  定义: ISR 是与 Leader 保持同步的副本集合，只有 ISR 内的副本可被选为新 Leader。");
        System.out.println();
        printRow("ISR 收缩条件", "Follower 落后 Leader 超过 replica.lag.time.max.ms (默认 30s)", "");
        printRow("ISR 扩张条件", "Follower 追上线，落后时间 < replica.lag.time.max.ms 后自动加入", "");
        printRow("ISR 最小数量", "min.insync.replicas ≥ 2 时保证至少 2 个副本同步", "");
        printRow("何时触发选举", "Leader 宕机时，Controller 从 ISR 中选新 Leader", "");

        System.out.println();
        System.out.println("  💀 陷阱: 单副本 + min.insync.replicas=1");
        System.out.println("    此时只要 Leader 存活就认为写入成功，但 Leader 宕机后消息可能丢失。");
        System.out.println("    设置 min.insync.replicas=2 但副本数=2 → 一个故障就不可写，需至少 3 副本。");
    }

    static void hwLeo() {
        printSection("2. HW (High Watermark) vs LEO (Log End Offset)");
        printRow("LEO", "Log End Offset — 每个副本下一条消息的 offset（已写入的最后 offset+1）", "");
        printRow("HW", "High Watermark — ISR 中所有副本 LEO 的最小值", "");
        printRow("Consumer 可见范围", "Consumer 只能消费 HW 之前的消息（已提交的）", "");

        System.out.println();
        System.out.println("  示例: 3 副本，Leader(LEO=5) F1(LEO=4) F2(LEO=3)");
        System.out.println("    → HW = min(5,4,3) = 3，Consumer 最多消费到 offset=2");
        System.out.println("    → F2 追上到 LEO=5 后，HW=5，Consumer 可消费到 offset=4");

        System.out.println();
        System.out.println("  💡 HW 截断问题 (旧版本): Leader 切换后新 Leader HW 可能低于旧 Leader HW，");
        System.out.println("     导致已消费消息"回溯消失"。Leader Epoch (KIP-101) 解决此问题。");
    }

    static void acksConfig() {
        printSection("3. acks 配置含义");
        printRow("acks=0", "Producer 不等待任何确认，立即返回 → 最高吞吐，可能丢失", "");
        printRow("acks=1", "Leader 写入本地日志后返回 → 平衡，Leader 宕机可能丢", "");
        printRow("acks=all/-1", "等待 ISR 所有副本确认后返回 → 最强可靠，性能最低", "");

        System.out.println();
        System.out.println("  推荐组合: acks=all + min.insync.replicas=2 + replication.factor=3");
        System.out.println("    → 容忍 1 个副本故障同时保证消息不丢失");
    }

    static void leaderEpoch() {
        printSection("4. Leader Epoch 机制");

        System.out.println("  HW 截断问题场景:");
        System.out.println("    1. Leader A: 写入 m1(offset=0) m2(offset=1)，LEO=2 HW=1");
        System.out.println("    2. Follower B: 同步了 m1，未同步 m2，LEO=1");
        System.out.println("    3. A 宕机，B 当选新 Leader，B 的 LEO=1 → HW=1");
        System.out.println("    4. A 恢复成为 Follower，发现 LEO=2 > Leader.LEO=1 → 截断到 HW=1");
        System.out.println("    5. m2 丢失（即使 Consumer 可能已消费）");
        System.out.println();
        System.out.println("  Leader Epoch 解决方案:");
        System.out.println("    - 每个 Leader 任期分配唯一 epoch 号（单调递增）");
        System.out.println("    - Follower 重启时向 Leader 请求 epoch 起始 offset，而非仅看 HW");
        System.out.println("    - 避免不必要的日志截断，保证数据一致性");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: ISR 收缩后还能自动恢复吗？");
        System.out.println("  A: 能。Follower 追上级 落后时间 < replica.lag.time.max.ms 后自动加入 ISR。");
        System.out.println();
        System.out.println("  Q: unclean.leader.election.enable=true 有什么风险？");
        System.out.println("  A: ISR 为空时允许非 ISR 副本当选 Leader，可能导致消息丢失。");
        System.out.println("     生产环境建议 false，宁可不可写也不丢消息。");
        System.out.println();
        System.out.println("  Q: acks=all 就保证 100% 不丢吗？");
        System.out.println("  A: 不是。还需 min.insync.replicas≥2 防止单点 + Producer 重试 + Consumer 手动提交 offset。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-20s: %s%n", dim, value);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q01_ISR_HW.java
git commit -m "feat(kafka): add interview Q01 ISR/HW/LEO deep dive"
```

---

### Task 2: Kafka Q02_Idempotent_Transaction — 幂等与事务

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q02_Idempotent_Transaction.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】幂等与事务 —— PID·SeqNum·TransactionCoordinator·Exactly Once
 * 【描述】深入解析 Kafka 消息不重不丢机制：幂等性(PID+SeqNum 去重)、
 *         事务(TransactionCoordinator 协调两阶段提交)、Exactly Once 语义
 *         (读-处理-写原子操作)、与 RocketMQ 事务消息的机制差异对比。
 * 【关键概念】enable.idempotence、PID、SeqNum、TransactionCoordinator、
 *             __transaction_state、ProducerIdAndEpoch、isolation.level、
 *             read_committed、Exactly Once、RocketMQ 半消息对比
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaIdempotentDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q02_Idempotent_Transaction {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka 幂等与事务 面试专辑");
        System.out.println("=".repeat(70));

        idempotenceMechanism();
        transactionMechanism();
        exactlyOnceSemantics();
        rocketmqTransactionComparison();
        interviewQA();
    }

    static void idempotenceMechanism() {
        printSection("1. 幂等性 (Idempotence)");

        System.out.println("  动机: Producer 重试导致重复写入 → 开启幂等后 Broker 自动去重");
        System.out.println();
        System.out.println("  实现: enable.idempotence=true 后 Producer 初始化时获得:");
        System.out.println("    - PID (Producer ID): Broker 分配的唯一标识");
        System.out.println("    - SeqNum: 每个 Partition 的消息序列号（单调递增，从 0 开始）");
        System.out.println();
        System.out.println("  去重逻辑:");
        System.out.println("    Broker 为每个 PID 维护最近 5 个 SeqNum 的缓存");
        System.out.println("    收到消息 → 检查 PID + SeqNum:");
        System.out.println("      - SeqNum 比缓存小 1 以上 → 乱序，抛出 OutOfOrderSequenceException");
        System.out.println("      - SeqNum 已存在 → 重复，丢弃");
        System.out.println("      - SeqNum 合法 → 正常写入");
        System.out.println();
        System.out.println("  限制: 单 Producer 单 Partition 单会话内去重（Producer 重启 PID 变化）");
    }

    static void transactionMechanism() {
        printSection("2. 事务 (Transaction)");

        System.out.println("  架构:");
        System.out.println("    - TransactionCoordinator: 某 Broker 兼任，管理事务状态");
        System.out.println("    - __transaction_state: 内部 Topic，存储事务日志");
        System.out.println("    - TransactionalId: Producer 配置，关联 PID 的稳定标识");
        System.out.println("    - ProducerIdAndEpoch: 防止僵尸 Producer（epoch 单调递增）");
        System.out.println();
        System.out.println("  流程:");
        System.out.println("    1. initTransactions() → 向 Coordinator 注册 TransactionalId");
        System.out.println("    2. beginTransaction() → 开启事务");
        System.out.println("    3. send(record) → 消息写入分区（对 Consumer 不可见）");
        System.out.println("    4. sendOffsetsToTransaction() → 提交消费 offset");
        System.out.println("    5. commitTransaction() → Coordinator 写入 PREPARE_COMMIT → 所有分区写入 COMMIT marker");
        System.out.println();
        System.out.println("  Consumer 端:");
        System.out.println("    isolation.level=read_committed → 只读到已 COMMIT 的消息（过滤 ABORT）");
        System.out.println("    isolation.level=read_uncommitted → 读到所有消息（默认，性能更高）");
    }

    static void exactlyOnceSemantics() {
        printSection("3. Exactly Once 语义");

        System.out.println("  Kafka Streams EOS: 读-处理-写 三步原子化");
        System.out.println();
        printRow("At-Most-Once", "acks=0, Consumer 先 commit 后处理 → 可能丢", "");
        printRow("At-Least-Once", "acks=1, Consumer 先处理后 commit → 可能重复（默认）", "");
        printRow("Exactly-Once", "幂等 Producer + 事务 + read_committed Consumer → 不丢不重", "");

        System.out.println();
        System.out.println("  ⚠️ 注意: Kafka 的 Exactly Once 仅限于「Kafka → Kafka」的流处理场景，");
        System.out.println("     如果涉及外部系统(DB/RPC)，仍需引入分布式事务（如 Seata）或最终一致性。");
    }

    static void rocketmqTransactionComparison() {
        printSection("4. Kafka 事务 vs RocketMQ 事务消息");

        System.out.printf("  %-20s %-22s %-22s%n", "维度", "Kafka 事务", "RocketMQ 事务");
        System.out.println("  " + "-".repeat(64));
        printRow("实现方式", "TransactionCoordinator + 两阶段提交 + COMMIT marker", "半消息 + 本地事务 + 回查 CheckListener");
        printRow("API 模型", "begin/commit/abort 主动控制", "sendMessageInTransaction + executeLocalTransaction");
        printRow("消费者感知", "需配 read_committed 过滤", "半消息对 Consumer 不可见（透明）");
        printRow("回查机制", "无（超时 abort）", "✅ CheckListener 定期回查 UNKNOWN 状态");
        printRow("适用场景", "流处理(Kafka→Kafka)", "分布式事务(DB+RPC→MQ)");
        printRow("复杂度", "较高（需管理 TransactionalId）", "中等（实现 CheckListener 即可）");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: 幂等 Producer 重启后 PID 变了怎么办？");
        System.out.println("  A: 幂等性在单会话内有效。跨会话去重需要事务 + TransactionalId，");
        System.out.println("     TransactionalId 关联到 PID，Producer 重启后 Coordinator 分配新 PID 但 epoch+1，");
        System.out.println("     旧 PID 写入会被 Fencing 拒绝。");
        System.out.println();
        System.out.println("  Q: Kafka 事务能替代 RocketMQ 事务消息吗？");
        System.out.println("  A: 不能直接替代。Kafka 事务解决 Kafka→Kafka 的一致性问题，");
        System.out.println("     RocketMQ 事务解决 DB/外部系统→MQ 的分布式事务。场景互补。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-20s: %s%n", dim, value);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q02_Idempotent_Transaction.java
git commit -m "feat(kafka): add interview Q02 idempotence and transaction"
```

---

### Task 3: Kafka Q03_Performance_Tuning — 性能调优

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q03_Performance_Tuning.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】性能调优 —— Producer·Consumer·Broker·OS 参数全解
 * 【描述】系统化梳理 Kafka 性能调优参数：Producer 端(batch.size/linger.ms/
 *         compression/buffer.memory)、Consumer 端(fetch/fetch.min.bytes)、
 *         Broker 端(num.network.threads/log.flush)、OS 层(PageCache/文件描述符)。
 *         附带常见性能瓶颈定位方法。
 * 【关键概念】batch.size、linger.ms、compression.type、buffer.memory、
 *             fetch.min.bytes、fetch.max.bytes、max.partition.fetch.bytes、
 *             num.network.threads、log.flush.interval、PageCache
 * 【关联类】@see com.nopr.mq.kafka.simulate.BatchSendDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q03_Performance_Tuning {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka 性能调优 面试专辑");
        System.out.println("=".repeat(70));

        producerTuning();
        consumerTuning();
        brokerTuning();
        osTuning();
        bottleneckDiagnosis();
        interviewQA();
    }

    static void producerTuning() {
        printSection("1. Producer 端调优");

        System.out.printf("  %-28s %-10s %-30s%n", "参数", "默认值", "调优建议");
        System.out.println("  " + "-".repeat(68));
        printRow3("batch.size", "16KB", "增大→吞吐↑ 延迟↑，建议 32KB~1MB");
        printRow3("linger.ms", "0", "设为 5~100ms 合并微批，减少请求");
        printRow3("compression.type", "none", "lz4(推荐)/snappy(低CPU)/zstd(高压缩)");
        printRow3("buffer.memory", "32MB", "积压时增大，避免 block.on.buffer.full");
        printRow3("max.in.flight", "5", "幂等模式下 ≤5，保证顺序");
        printRow3("acks", "1", "权衡: 0(快但丢) 1(平衡) all(可靠)");
        printRow3("retries", "0", "配合 delivery.timeout.ms 使用");

        System.out.println();
        System.out.println("  📊 优化公式: 吞吐 ∝ batch.size × (1 / linger.ms)，CPU 利用率随压缩类型变化");
    }

    static void consumerTuning() {
        printSection("2. Consumer 端调优");

        System.out.printf("  %-28s %-10s %-30s%n", "参数", "默认值", "调优建议");
        System.out.println("  " + "-".repeat(68));
        printRow3("fetch.min.bytes", "1", "增大到 1MB+ 减少请求，延迟会升高");
        printRow3("fetch.max.bytes", "50MB", "单次拉取上限，网络好的话可增大");
        printRow3("max.partition.fetch.bytes", "1MB", "单分区拉取上限，分区数据量大的场景增大");
        printRow3("max.poll.records", "500", "单次 poll 返回最大条数，过大导致消费超时");
        printRow3("max.poll.interval.ms", "300s", "两次 poll 间隔上限，自定义处理需增大");
        printRow3("fetch.max.wait.ms", "500", "fetch.min.bytes 不满足时的等待时间");
        printRow3("session.timeout.ms", "45s", "过短易触发 Rebalance，建议 30~60s");
    }

    static void brokerTuning() {
        printSection("3. Broker 端调优");

        System.out.printf("  %-28s %-10s %-30s%n", "参数", "默认值", "调优建议");
        System.out.println("  " + "-".repeat(68));
        printRow3("num.network.threads", "3", "网络线程，CPU 核数×1");
        printRow3("num.io.threads", "8", "IO 线程，CPU 核数×2");
        printRow3("log.flush.interval.messages", "Long.MAX", "异步刷盘不建议改，靠 OS PageCache");
        printRow3("log.flush.interval.ms", "Long.MAX", "异步刷盘，依赖 OS pdflush 刷盘");
        printRow3("log.segment.bytes", "1GB", "小 Segment 加快清理，大 Segment 减少文件数");
        printRow3("num.partitions", "1", "分区数 = max(吞吐/单分区吞吐, 并行度)");
        printRow3("num.replica.fetchers", "1", "复制线程数，多副本场景增大到 CPU 核数");
    }

    static void osTuning() {
        printSection("4. OS 层调优");

        printRow3("PageCache", "-", "Kafka 重度依赖，确保足够内存预留给 OS");
        printRow3("文件描述符", "1024", "增大到 100000+ (ulimit -n)");
        printRow3("磁盘", "-", "SSD >> HDD，RAID 10 最佳，禁用 atime 更新");
        printRow3("内核参数", "-", "vm.swappiness=1 减少 swap，vm.dirty_ratio=10");
        printRow3("JVM", "-", "G1GC / ZGC，堆=6~8GB，禁用 CMS/Parallel");

        System.out.println();
        System.out.println("  📊 Kafka 性能瓶颈排序: 磁盘 IO > 网络带宽 > CPU > 内存");
    }

    static void bottleneckDiagnosis() {
        printSection("5. 性能瓶颈定位");

        System.out.println("  延迟高清单:");
        System.out.println("    ① Producer linger.ms/batch.size 是否合理？");
        System.out.println("    ② Consumer fetch.min.bytes 是否过大？");
        System.out.println("    ③ 磁盘是否 HDD？IO await > 10ms？");
        System.out.println("    ④ 网络带宽是否打满？");
        System.out.println("    ⑤ Broker GC 停顿是否频繁？");
        System.out.println("    ⑥ Consumer Rebalance 是否频繁？");
        System.out.println();
        System.out.println("  吞吐低清单:");
        System.out.println("    ① 分区数是否足够（< CPU 核数 × 并发度）？");
        System.out.println("    ② compression 是否开启？");
        System.out.println("    ③ batch.size/linger.ms 是否过小？");
        System.out.println("    ④ Consumer 处理逻辑是否有瓶颈？");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: Kafka 单机吞吐理论上限？");
        System.out.println("  A: 物理机 + SSD + 千兆网卡 → ~100MB/s 写入（约 10万条/秒 x 1KB消息）。");
        System.out.println("     万兆网卡 + RAID10 + 大页内存 → 可达 GB/s 级。瓶颈通常是磁盘 IO 和网络。");
        System.out.println();
        System.out.println("  Q: 如何提升 Consumer 消费速度？");
        System.out.println("  A: ①增加分区数→多 Consumer 并行 ②增大 fetch.min.bytes 减少请求 ③max.poll.records 调大");
        System.out.println("     ④优化消费逻辑（批量处理、异步化）⑤max.poll.interval.ms 调大防 Rebalance");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow3(String param, String defaultVal, String suggestion) {
        System.out.printf("  %-28s %-10s %-30s%n", param, defaultVal, suggestion);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q03_Performance_Tuning.java
git commit -m "feat(kafka): add interview Q03 performance tuning parameters"
```

---

### Task 4: Kafka Q04_Storage_Design — 存储设计原理

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q04_Storage_Design.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】存储设计原理 —— 顺序写·零拷贝·PageCache·Segment·稀疏索引
 * 【描述】深入解析 Kafka 存储层设计：为什么顺序写快、sendfile 零拷贝路径、
 *         PageCache 读缓存依赖与刷盘策略、LogSegment 文件(.log/.index/.timeindex)
 *         与稀疏索引二分查找、对比 RocketMQ CommitLog+ConsumeQueue。
 * 【关键概念】顺序写、sendfile、mmap、PageCache、LogSegment、稀疏索引、
 *             offset index、time index、log.segment.bytes、ConsumeQueue 对比
 * 【关联类】@see com.nopr.mq.kafka.simulate.ZeroCopyDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q04_Storage_Design {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka 存储设计原理 面试专辑");
        System.out.println("=".repeat(70));

        sequentialWrite();
        zeroCopyDesign();
        pageCache();
        segmentIndex();
        rocketmqComparison();
        interviewQA();
    }

    static void sequentialWrite() {
        printSection("1. 顺序写 vs 随机写");

        System.out.println("  Kafka 的核心设计：所有消息追加写入，无更新无删除");
        System.out.println();
        printRow("顺序写 IOPS", "~600MB/s (现代 SATA SSD)", "");
        printRow("随机写 IOPS", "~100MB/s (SSD) / ~1MB/s (HDD)", "");
        printRow("差异倍数", "6~600 倍", "");
        System.out.println();
        System.out.println("  Partition 内消息严格有序追加 → 纯顺序 IO → 磁盘吞吐接近内存");
        System.out.println("  每个 Partition 一个目录，内含 .log(消息) .index(偏移索引) .timeindex(时间索引)");
    }

    static void zeroCopyDesign() {
        printSection("2. 零拷贝 (Zero Copy)");

        System.out.println("  传统 IO (read+write):");
        System.out.println("    Disk →[DMA]→ PageCache →[CPU]→ UserBuffer →[CPU]→ SocketBuffer →[DMA]→ NIC");
        System.out.println("    4 次拷贝 + 4 次上下文切换");
        System.out.println();
        System.out.println("  sendfile 零拷贝:");
        System.out.println("    Disk →[DMA]→ PageCache →[DMA- scatter/gather]→ NIC");
        System.out.println("    2 次 DMA 拷贝 + 2 次上下文切换（数据不经过 CPU 和用户态）");
        System.out.println();
        System.out.println("  Kafka 通过 FileChannel.transferTo() → sendfile → 数据路径极短");
        System.out.println("  ⚠️ sendfile 仅在 Linux 有效，Windows/macOS 回退到传统 IO");
    }

    static void pageCache() {
        printSection("3. PageCache 依赖");

        System.out.println("  Kafka 不主动刷盘，完全依赖 OS PageCache:");
        System.out.println("    写: Producer → PageCache → 返回成功 → OS pdflush 异步刷盘");
        System.out.println("    读: Consumer → 查 PageCache → 命中(μs级) / 未命中(ms级 磁盘读)");
        System.out.println();
        System.out.println("  优势: OS 级预读 + 缓存淘汰，充分利用空闲内存");
        System.out.println("  风险: 掉电丢数据（未刷盘的脏页）→ 需 acks=all + 多副本补偿");
        System.out.println("  内存建议: 物理内存 50%~70% 留给 OS PageCache");
    }

    static void segmentIndex() {
        printSection("4. LogSegment 与稀疏索引");

        System.out.println("  Partition 目录结构:");
        System.out.println("    topic-0/");
        System.out.println("      \u251C\u2500 00000000000000000000.log       ← 消息文件");
        System.out.println("      \u251C\u2500 00000000000000000000.index     ← 偏移索引（稀疏）");
        System.out.println("      \u251C\u2500 00000000000000000000.timeindex  ← 时间戳索引");
        System.out.println("      \u251C\u2500 00000000000000000536.log       ← 下一个 Segment");
        System.out.println("      \u2514\u2500 ...");
        System.out.println();
        System.out.println("  .log: 消息二进制存储（offset+position+size+...+payload）");
        System.out.println("  .index: 稀疏索引，每 log.index.interval.bytes(默认4KB) 写一个条目");
        System.out.println("  .timeindex: 时间→偏移映射，支持按时间戳查找");
        System.out.println();
        System.out.println("  查找流程:");
        System.out.println("    1. 二分查找目标 Segment（根据 offset 范围）");
        System.out.println("    2. 在 Segment .index 中二分找最近 offset → 获取 position");
        System.out.println("    3. 从 position 开始顺序扫描 .log 直到找到目标 offset");
        System.out.println();
        System.out.println("  优化: 稀疏索引 + 二分查找 → O(log N) 定位，MappedByteBuffer 内存映射加速");
    }

    static void rocketmqComparison() {
        printSection("5. Kafka LogSegment vs RocketMQ CommitLog");

        System.out.printf("  %-18s %-22s %-22s%n", "维度", "Kafka", "RocketMQ");
        System.out.println("  " + "-".repeat(62));
        printRow("写入模型", "Partition 独立顺序写", "全局 CommitLog 顺序写");
        printRow("消费索引", ".index(偏移)+.timeindex(时间)", "ConsumeQueue(偏移)");
        printRow("内存映射", "MappedByteBuffer(.index)", "MappedByteBuffer(CommitLog)");
        printRow("刷盘", "依赖 PageCache + 异步", "可选同步/异步刷盘");
        printRow("文件管理", "Segment(.log+.index+.timeindex)", "CommitLog + ConsumeQueue");
        printRow("查找效率", "二分 Segment→二分 index→顺序扫", "ConsumeQueue 二分 → 定位 CommitLog");

        System.out.println();
        System.out.println("  Kafka 优势: Partition 级隔离，不同 Partition 可独立清理/保留");
        System.out.println("  RocketMQ 优势: 全局 CommitLog 顺序写，磁盘利用率更高（无 1GB Segment 碎片）");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: 为什么 Kafka 用磁盘而不是内存存储？");
        System.out.println("  A: 磁盘顺序写接近内存随机写速度(现代 SSD 600MB/s+)，配合 PageCache 读命中率 >90%，");
        System.out.println("     成本远低于内存(1TB 磁盘 vs 1TB 内存)，且数据持久化无需额外机制。");
        System.out.println();
        System.out.println("  Q: mmap 和 sendfile 有什么区别？");
        System.out.println("  A: mmap 将文件映射到用户态内存地址空间，减少一次 CPU 拷贝但数据仍需经过用户态；");
        System.out.println("     sendfile 数据完全不经过用户态，DMA 直接从 PageCache 到网卡，零 CPU 参与。");
        System.out.println("     Kafka 索引用 mmap(小文件)，数据传输用 sendfile(大文件)。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-20s: %s%n", dim, value);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q04_Storage_Design.java
git commit -m "feat(kafka): add interview Q04 storage design principles"
```

---

### Task 5: Kafka Q05_Troubleshooting — 故障排查实战

**Files:**
- Create: `study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q05_Troubleshooting.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.kafka.interview;

/**
 * 【模块】kafka
 * 【分类】interview
 * 【主题】故障排查实战 —— 消息积压·丢失·重复·延迟 定位与处理
 * 【描述】系统化故障排查指南：消息积压（消费慢/分区少/GC/重平衡）、
 *         消息丢失（Producer/Broker/Consumer 三段分析）、重复消费（幂等方案）、
 *         延迟飙高（网络/磁盘/GC/Compaction 排查步骤）。覆盖 RocketMQ 和 Kafka。
 * 【关键概念】消息积压、消息丢失、重复消费、消费延迟、Consumer Lag、
 *             Rebalance Storm、GC 停顿、磁盘 IO 打满、分区数不足
 * 【关联类】@see com.nopr.mq.kafka.simulate.RebalanceDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q05_Troubleshooting {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  Kafka/RocketMQ 故障排查实战 面试专辑");
        System.out.println("=".repeat(70));

        messageBacklog();
        messageLoss();
        duplicateConsumption();
        latencySpike();
        interviewQA();
    }

    static void messageBacklog() {
        printSection("1. 消息积压排查");

        System.out.println("  现象: Consumer Lag 持续增长，消息堆积");
        System.out.println();
        System.out.println("  排查清单:");
        System.out.println("  \u2460 Consumer 消费速度 < Producer 生产速度？");
        System.out.println("     → 增加分区数 + 消费者数（上限 = 分区数）");
        System.out.println("  \u2461 Consumer 处理逻辑耗时过长？");
        System.out.println("     → 批量处理、异步化、优化 DB/缓存查询");
        System.out.println("  \u2462 Consumer 频繁 Rebalance？");
        System.out.println("     → 调大 session.timeout.ms + max.poll.interval.ms");
        System.out.println("  \u2463 Broker GC 频繁导致暂停服务？");
        System.out.println("     → 调大 JVM 堆（推荐 G1GC），减少日志段数量");
        System.out.println("  \u2464 磁盘 IO 打满？");
        System.out.println("     → iostat 确认 await>10ms → 换 SSD 或增加 Broker");

        System.out.println();
        System.out.println("  应急方案:");
        System.out.println("    - 紧急扩容 Consumer 实例（最多到分区数）");
        System.out.println("    - 跳过非关键消息（重置 offset 到最新）");
        System.out.println("    - 临时降级：积压消息转移到死信 Topic 异步处理");
    }

    static void messageLoss() {
        printSection("2. 消息丢失排查");

        System.out.println("  三段定位法:");
        System.out.println();
        System.out.println("  [Producer 端] 可能丢失:");
        System.out.println("    ✗ acks=0（不等待确认）→ 改用 acks=all");
        System.out.println("    ✗ retries=0（不重试）→ 开启重试");
        System.out.println("    ✗ 异步发送未处理异常 → 检查 Callback.onException()");
        System.out.println();
        System.out.println("  [Broker 端] 可能丢失:");
        System.out.println("    ✗ unclean.leader.election.enable=true → 设为 false");
        System.out.println("    ✗ min.insync.replicas=1 + Leader 宕机 → 设为 2");
        System.out.println("    ✗ log.flush 间隔过大 + 掉电 → 异步刷盘，依赖多副本");
        System.out.println();
        System.out.println("  [Consumer 端] 可能丢失:");
        System.out.println("    ✗ enable.auto.commit=true（自动提交未处理完的 offset）→ 改为手动提交");
        System.out.println("    ✗ 先 commit 后处理 → 改为先处理后 commit");

        System.out.println();
        System.out.println("  📌 RocketMQ 不丢配置:");
        System.out.println("    Producer: retryTimesWhenSendFailed=3 + SYNC 发送");
        System.out.println("    Broker: flushDiskType=SYNC_FLUSH + brokerRole=SYNC_MASTER");
        System.out.println("    Consumer: 处理后返回 CONSUME_SUCCESS，不自动确认");
    }

    static void duplicateConsumption() {
        printSection("3. 重复消费排查");

        System.out.println("  根本原因: 分布式系统无法 100% 避免重复（网络超时重试）");
        System.out.println();
        System.out.println("  典型场景:");
        System.out.println("    - Producer 超时重试 → 同一条消息写入两次");
        System.out.println("    - Consumer 处理完但 Commit 超时 → 下次 Rebalance 重新消费");
        System.out.println("    - Rebalance 触发 → 未 Commit 的 offset 被回退");
        System.out.println();
        System.out.println("  解决方案:");
        System.out.println("    \u2460 Kafka: enable.idempotence=true（Producer 去重）");
        System.out.println("    \u2461 Consumer 端幂等处理:");
        System.out.println("       - DB 唯一索引（INSERT ... ON DUPLICATE KEY）");
        System.out.println("       - Redis SETNX 记录 msgId，已处理则跳过");
        System.out.println("       - 业务幂等（UPDATE user SET cnt=cnt+1 → UPDATE user SET cnt=具体值 WHERE version=旧值）");
        System.out.println("    \u2462 RocketMQ: 消费返回 CONSUME_SUCCESS（不触发重投）");
    }

    static void latencySpike() {
        printSection("4. 延迟飙高排查");

        System.out.println("  排查路径:");
        System.out.println("  \u2460 Producer 端延迟 (RecordAccumulator 等待):");
        System.out.println("     batch.size 过大 → 消息在缓冲区等待积累");
        System.out.println("     linger.ms > 0 → 等待超时合并，检查是否设置过大");
        System.out.println("  \u2461 Broker 端延迟:");
        System.out.println("     - 磁盘 IO 飙升（iostat %util > 90%）");
        System.out.println("     - GC 停顿（jstat -gc → 调 G1/ZGC）");
        System.out.println("     - Compaction 清理（__consumer_offsets 等内部 Topic）");
        System.out.println("  \u2462 Consumer 端延迟:");
        System.out.println("     - fetch.min.bytes 过大 → 等待积累");
        System.out.println("     - Consumer 处理逻辑耗时 → 异步 + 批处理");
        System.out.println("  \u2463 网络延迟:");
        System.out.println("     - 跨机房部署 → 改为同机房");
        System.out.println("     - 网卡打满 → iperf 测试带宽");

        System.out.println();
        System.out.println("  📌 监控指标: Consumer Lag（核心）、副本落后字节数、磁盘 IO await、GC 频率");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: 线上 Kafka 消费突然变慢，如何排查？");
        System.out.println("  A: ① Consumer Lag → 积压确认  ② Consumer 日志 → 是否有慢业务");
        System.out.println("     ③ Broker 监控 → GC / 磁盘 IO / 网络  ④ 是否频繁 Rebalance");
        System.out.println("     ⑤ 分区数是否够（≤ Consumer 数 → 无法横向扩容）");
        System.out.println();
        System.out.println("  Q: 如何保证消息绝对不丢？");
        System.out.println("  A: Producer(acks=all+retry) + Broker(min.insync.replicas≥2+3副本) + Consumer(手动commit)");
        System.out.println("     但这是代价（延迟+吞吐），通常 At-Least-Once + 幂等消费已满足业务需求。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/kafka
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/kafka/src/main/java/com/nopr/mq/kafka/interview/Q05_Troubleshooting.java
git commit -m "feat(kafka): add interview Q05 troubleshooting guide"
```

---

### Task 6: RocketMQ Q05_Transaction_Deep — 事务消息深度剖析

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q05_Transaction_Deep.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】事务消息深度剖析 —— 半消息状态机·回查机制·故障场景
 * 【描述】深度解析 RocketMQ 事务消息：半消息(PREPARE)的完整生命周期、
 *         CheckListener 回查时序与频率、事务状态(UNKNOWN/COMMIT/ROLLBACK)
 *         转换规则、与 Kafka 事务的机制/场景/限制差异对比、
 *         典型故障（半消息堆积/回查风暴/CheckListener 异常）。
 * 【关键概念】半消息、事务状态机、CheckListener、回查机制、超时策略、
 *             TRANSACTION_CHECK_TIMES、TransactionStatus.Unknown
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.TransactionDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q05_Transaction_Deep {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ 事务消息深度剖析 面试专辑");
        System.out.println("=".repeat(70));

        halfMessageLifecycle();
        checkListenerDetails();
        failureScenarios();
        kafkaComparison();
        interviewQA();
    }

    static void halfMessageLifecycle() {
        printSection("1. 半消息生命周期");

        System.out.println("  阶段 1: 发送半消息（PREPARE）");
        System.out.println("    Producer.sendMessageInTransaction(msg, txListener)");
        System.out.println("    → Broker 写入半消息（Topic=%RETRY%ConsumeGroup%），Consumer 不可见");
        System.out.println();
        System.out.println("  阶段 2: 执行本地事务");
        System.out.println("    → txListener.executeLocalTransaction(msg, arg)");
        System.out.println("    → 返回 LocalTransactionState:");
        System.out.println("        - COMMIT_MESSAGE → 半消息正式投递，Consumer 可见");
        System.out.println("        - ROLLBACK_MESSAGE → 半消息丢弃");
        System.out.println("        - UNKNOW → 半消息挂起，等待回查");
        System.out.println();
        System.out.println("  阶段 3: 回查（Check）");
        System.out.println("    → Broker 定时扫描超时的 UNKNOW 半消息");
        System.out.println("    → 回调 txListener.checkLocalTransaction(msgExt)");
        System.out.println("    → 最多回查 TRANSACTION_CHECK_TIMES（默认 15 次）");
        System.out.println("    → 超次后自动 ROLLBACK");
    }

    static void checkListenerDetails() {
        printSection("2. CheckListener 回查机制详解");

        printRow("触发条件", "executeLocalTransaction 返回 UNKNOW + 超时", "");
        printRow("首次回查延迟", "transaction.timeout（默认 6s）后触发", "");
        printRow("回查间隔", "递增: 6s → 10s → 30s → 1m → 2m → ... → 2h", "");
        printRow("最大回查次数", "TRANSACTION_CHECK_TIMES=15 次", "");
        printRow("超次处理", "自动 ROLLBACK → 半消息丢弃", "");
        System.out.println();
        System.out.println("  ⚠️ 注意事项:");
        System.out.println("    - checkLocalTransaction 必须幂等（可能被多次回调）");
        System.out.println("    - 回查期间 Consumer 仍然看不到该消息");
        System.out.println("    - 回查在 Broker 端异步线程池执行（checkExecutorService）");
    }

    static void failureScenarios() {
        printSection("3. 典型故障场景");

        System.out.println("  故障 1: 半消息堆积");
        System.out.println("    原因: executeLocalTransaction 大量返回 UNKNOW，回查线程池饱和");
        System.out.println("    排查: 检查 checkListener 是否超时/异常");
        System.out.println("    解决: 增大 checkRequestHoldMax(默认 2000)、优化 checkListener 逻辑");
        System.out.println();
        System.out.println("  故障 2: 回查风暴");
        System.out.println("    原因: Broker 宕机恢复后，堆积的半消息集中回查");
        System.out.println("    影响: checkExecuteQueueSize 打满，正常消息投递变慢");
        System.out.println("    解决: 限制单次回查数量、分批处理回查任务");
        System.out.println();
        System.out.println("  故障 3: CheckListener 持续 Unknown");
        System.out.println("    原因: 依赖的外部系统（DB/Redis/RPC）不可用");
        System.out.println("    后果: 15 次回查后自动 ROLLBACK，实际本地事务可能已完成（数据不一致）");
        System.out.println("    解决: CheckListener 需查询本地事务状态，不可依赖外部系统");
        System.out.println();
        System.out.println("  故障 4: Consumer 消费重复");
        System.out.println("    原因: COMMIT 后 Consumer 消费成功，但 offset 未提交→重试");
        System.out.println("    解决: Consumer 端确保幂等消费（从根源解决）");
    }

    static void kafkaComparison() {
        printSection("4. RocketMQ 事务 vs Kafka 事务");

        System.out.printf("  %-22s %-23s %-23s%n", "对比维度", "RocketMQ 事务消息", "Kafka 事务");
        System.out.println("  " + "-".repeat(68));
        printRow("核心 API", "TransactionMQProducer + CheckListener", "KafkaProducer.initTransactions + commit");
        printRow("协调方式", "Broker 定时回查 checkLocalTransaction", "TransactionCoordinator 协调 + COMMIT marker");
        printRow("外部系统", "天然支持 DB→MQ（本地事务+半消息+回查）", "不支持（仅 Kafka→Kafka）");
        printRow("超时处理", "回查 15 次后自动 ROLLBACK", "transaction.timeout.ms 到期自动 ABORT");
        printRow("消费者透明", "✅ 半消息不可见，COMMIT 后可见", "需配 isolation.level=read_committed");
        printRow("实现复杂度", "中等（CheckListener + 业务幂等）", "较高（TransactionalId + epoch 管理）");
        printRow("典型场景", "订单状态→MQ 通知（DB+MQ 一致性）", "流处理一致(Kafka Streams EOS)");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: CheckListener 返回 Unknown 会发生什么？");
        System.out.println("  A: Broker 按递增间隔定时回调 checkLocalTransaction，最多 15 次。");
        System.out.println("     超次后自动 ROLLBACK。Unknown 期间 Consumer 不可见该消息。");
        System.out.println();
        System.out.println("  Q: 事务消息的可靠性边界在哪？");
        System.out.println("  A: 单向保证——本地事务成功 → 消息必然投递。反方向不保证：");
        System.out.println("     消息投递成功 ≠ Consumer 消费成功，需 Consumer 端幂等兜底。");
        System.out.println();
        System.out.println("  Q: 什么场景不适合事务消息？");
        System.out.println("  A: ① 超高吞吐(万级 TPS)→半消息回查开销大");
        System.out.println("     ② 外部系统弱依赖 → 回查可能因外部不可用而一直 Unknown");
        System.out.println("     ③ 非事务型消息（普通消息不需要半消息机制）");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-22s: %s%n", dim, value);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/rocketmq
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q05_Transaction_Deep.java
git commit -m "feat(rocketmq): add interview Q05 transaction message deep dive"
```

---

### Task 7: RocketMQ Q06_5x_NewFeatures — 5.0 新特性

**Files:**
- Create: `study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q06_5x_NewFeatures.java`

- [ ] **Step 1: 创建文件**

```java
package com.nopr.mq.rocketmq.interview;

/**
 * 【模块】rocketmq
 * 【分类】interview
 * 【主题】5.0 新特性 —— Controller·POP·Proxy·轻量级队列
 * 【描述】介绍 RocketMQ 5.0 重大架构变更：Controller 模式（替代 DLedger，内置 Raft）、
 *         POP 消费（解决客户端负载不均衡）、Proxy 代理（gRPC 多语言支持）、
 *         轻量级消息队列（topic→queue 自动扩展）、与 4.x 的关键差异。
 * 【关键概念】Controller 5.0、POP 消费、Proxy、gRPC、DLedger 淘汰、
 *             Raft 元数据、轻量级 Queue、消息类型分离、4.x→5.0 迁移
 * 【关联类】@see Q03_HighAvailabilityComparison
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class Q06_5x_NewFeatures {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("  RocketMQ 5.0 新特性 面试专辑");
        System.out.println("=".repeat(70));

        controllerMode();
        popConsumption();
        proxyGateway();
        lightweightQueue();
        versionDiff();
        interviewQA();
    }

    static void controllerMode() {
        printSection("1. Controller 模式（替代 DLedger）");

        System.out.println("  4.x 架构: Master-Slave → DLedger (Raft) → 仅数据同步使用 Raft");
        System.out.println("  5.x 架构: Controller → 内置 Raft → 元数据 + 数据统一管理");
        System.out.println();
        System.out.println("  Controller 核心变化:");
        printRow("元数据管理", "Broker 注册到 Controller（替代 NameServer 部分职责）", "");
        printRow("Leader 选举", "Controller 基于 Raft 管理，替代 DLedger 选举", "");
        printRow("高可用", "Controller 集群 (3/5 奇数节点) 自管理 Raft", "");
        printRow("脑裂防护", "Term + LeaderEpoch，类似 Kafka KRaft", "");
        printRow("NameServer", "仍需 NameServer 做路由发现（未完全去 NS）", "");

        System.out.println();
        System.out.println("  📌 Controller 尚未完全替代 NameServer，架构演进仍在进行中");
    }

    static void popConsumption() {
        printSection("2. POP 消费（解决负载不均衡）");

        System.out.println("  4.x Push/Pull 消费的痛点:");
        System.out.println("    - Push: 客户端负载均衡(AllocateMessageQueueStrategy)，");
        System.out.println("      单 Queue 只能被单 Consumer 消费");
        System.out.println("    - 问题: Consumer 数量 > Queue 数量 → 部分 Consumer 空闲");
        System.out.println("    - 解决方法: 增加 Queue 数（上限 65536，但增加 OPS 开销）");
        System.out.println();
        System.out.println("  5.0 POP 消费:");
        System.out.println("    - Broke 端排队：Consumer 不绑定 Queue，向 Broker 发起 POP 请求");
        System.out.println("    - Broker 从多个 Queue 聚合消息返回 → 解决负载不均");
        System.out.println("    - Consumer 无状态弹性扩缩：100 个 Consumer 共享 4 个 Queue");
        System.out.println();
        System.out.println("  优势: Consumer 扩缩容零感知，消息分发更均匀");
        System.out.println("  代价: Broker 端多一次 POP 聚合，轻微增加延迟");
    }

    static void proxyGateway() {
        printSection("3. Proxy 代理（多语言 + 协议演进）");

        System.out.println("  4.x 协议: Remoting（自研 Java 序列化）→ Client 强耦合 Java");
        System.out.println("  5.0 Proxy:");
        System.out.println("    - 协议层: gRPC + Protobuf（语言无关）");
        System.out.println("    - 部署: Proxy 独立进程 → 负载均衡 → 后端 Broker");
        System.out.println("    - Client: Go / Python / C++ / Rust 等原生 SDK");
        System.out.println();
        System.out.println("  架构:");
        System.out.println("    Client (gRPC) → Proxy (名称发现) → Namesrv → Broker");
        System.out.println();
        System.out.println("  优势: 多语言 SDK 成本降低 90%，协议标准化便于云原生集成");
    }

    static void lightweightQueue() {
        printSection("4. 轻量级消息队列");

        System.out.println("  4.x Topic 与 Queue 的关系:");
        System.out.println("    Topic → 固定 Queue 数（需预先规划，改 Queue 数需重建 Topic）");
        System.out.println();
        System.out.println("  5.0 轻量级 Queue:");
        printRow("动态扩展", "Topic 读写负载 → Broker 自动增减 Queue 数", "");
        printRow("单分区消费", "Queue 支持单 Consumer 读（收拢→POP 聚合分发）", "");
        printRow("资源隔离", "不同 Topic 间 Queue 物理隔离，互不影响", "");
        printRow("适用场景", "不需要严格有序的场景（90% 场景适用）", "");
    }

    static void versionDiff() {
        printSection("5. 4.x vs 5.0 关键差异");

        System.out.printf("  %-22s %-23s %-23s%n", "维度", "4.x (DLedger)", "5.0 (Controller)");
        System.out.println("  " + "-".repeat(68));
        printRow("选主", "DLedger(Raft 选主)", "Controller(Raft 选主+元数据管理)");
        printRow("消费", "Push/Pull(客户端负载均衡)", "新增 POP(Broker 端聚合分发)");
        printRow("协议", "Remoting(Java私协议)", "新增 gRPC proxy(多语言)");
        printRow("Queue", "固定数量", "新增轻量级 Queue(动态扩展)");
        printRow("架构", "NameServer + DLedger + Broker", "NameServer + Controller + Proxy + Broker");
        printRow("迁移路径", "-", "4.x → 5.0 平滑升级（兼容 4.x Remoting)");

        System.out.println();
        System.out.println("  \u26A0\uFE0F 注意: 5.0 仍兼容 4.x Remoting 协议，可渐进迁移");
    }

    static void interviewQA() {
        printSection("面试高频 Q&A");
        System.out.println("  Q: POP 消费和 Push 消费如何选择？");
        System.out.println("  A: Consumer 经常扩缩容 → POP（Broker 端聚合分发，无绑定限制）");
        System.out.println("     Consumer 数量 ≤ Queue 数且稳定 → Push（成熟稳定 + 低延迟）");
        System.out.println();
        System.out.println("  Q: RocketMQ 5.0 Controller 是否完全替代 NameServer？");
        System.out.println("  A: 否。Controller 管理元数据(Broker 注册/Topic 配置)，NameServer 负责路由发现。");
        System.out.println("     未来方向是 Controller 统一管理 + 去除 NameServer，但 5.0 尚未实现。");
        System.out.println();
        System.out.println("  Q: 4.x DLedger 迁移到 5.0 Controller 是否需要改业务代码？");
        System.out.println("  A: Controller 模式需要 Broker 端重新配置（controllerAddr），");
        System.out.println("     但 Producer/Consumer 连接方式不变（仍连 NameServer），业务代码零改动。");
    }

    static void printSection(String title) {
        System.out.printf("%n  \u250C%s\u2510%n", "\u2500".repeat(66));
        System.out.printf("  \u2502 %-64s \u2502%n", title);
        System.out.printf("  \u2514%s\u2518%n", "\u2500".repeat(66));
    }

    static void printRow(String dim, String value, String extra) {
        System.out.printf("  %-22s: %s%n", dim, value);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl study-middleware/rocketmq
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add study-middleware/rocketmq/src/main/java/com/nopr/mq/rocketmq/interview/Q06_5x_NewFeatures.java
git commit -m "feat(rocketmq): add interview Q06 5.0 new features"
```

---

## 最终验证

- [ ] **Step: 全量编译 + 运行 Demo**

```bash
mvn compile -pl study-middleware/rocketmq,study-middleware/kafka
```
Expected: BUILD SUCCESS — rocketmq 17 文件 + kafka 21 文件 = 38 文件

## 自检清单

- ✅ 7 个 Task 全覆盖（Kafka Q01-Q05 + RocketMQ Q05-Q06）
- ✅ 每个 Task 含完整代码、编译命令、commit message
- ✅ 无 TBD/TODO/占位符
- ✅ 文件命名遵循 Q 系列编号规则
- ✅ Javadoc 头部统一【模块】【分类】【主题】【描述】【关键概念】模板
