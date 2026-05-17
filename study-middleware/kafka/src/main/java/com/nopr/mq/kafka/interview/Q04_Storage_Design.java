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
