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
        System.out.println("     导致已消费消息「回溯消失」。Leader Epoch (KIP-101) 解决此问题。");
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
