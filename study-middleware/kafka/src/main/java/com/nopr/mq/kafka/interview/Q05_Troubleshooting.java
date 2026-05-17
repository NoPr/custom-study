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
        System.out.println("  ① Consumer 消费速度 < Producer 生产速度？");
        System.out.println("     → 增加分区数 + 消费者数（上限 = 分区数）");
        System.out.println("  ② Consumer 处理逻辑耗时过长？");
        System.out.println("     → 批量处理、异步化、优化 DB/缓存查询");
        System.out.println("  ③ Consumer 频繁 Rebalance？");
        System.out.println("     → 调大 session.timeout.ms + max.poll.interval.ms");
        System.out.println("  ④ Broker GC 频繁导致暂停服务？");
        System.out.println("     → 调大 JVM 堆（推荐 G1GC），减少日志段数量");
        System.out.println("  ⑤ 磁盘 IO 打满？");
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
        System.out.println("    ① Kafka: enable.idempotence=true（Producer 去重）");
        System.out.println("    ② Consumer 端幂等处理:");
        System.out.println("       - DB 唯一索引（INSERT ... ON DUPLICATE KEY）");
        System.out.println("       - Redis SETNX 记录 msgId，已处理则跳过");
        System.out.println("       - 业务幂等（UPDATE user SET cnt=cnt+1 → UPDATE user SET cnt=具体值 WHERE version=旧值）");
        System.out.println("    ③ RocketMQ: 消费返回 CONSUME_SUCCESS（不触发重投）");
    }

    static void latencySpike() {
        printSection("4. 延迟飙高排查");

        System.out.println("  排查路径:");
        System.out.println("  ① Producer 端延迟 (RecordAccumulator 等待):");
        System.out.println("     batch.size 过大 → 消息在缓冲区等待积累");
        System.out.println("     linger.ms > 0 → 等待超时合并，检查是否设置过大");
        System.out.println("  ② Broker 端延迟:");
        System.out.println("     - 磁盘 IO 飙升（iostat %util > 90%）");
        System.out.println("     - GC 停顿（jstat -gc → 调 G1/ZGC）");
        System.out.println("     - Compaction 清理（__consumer_offsets 等内部 Topic）");
        System.out.println("  ③ Consumer 端延迟:");
        System.out.println("     - fetch.min.bytes 过大 → 等待积累");
        System.out.println("     - Consumer 处理逻辑耗时 → 异步 + 批处理");
        System.out.println("  ④ 网络延迟:");
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
