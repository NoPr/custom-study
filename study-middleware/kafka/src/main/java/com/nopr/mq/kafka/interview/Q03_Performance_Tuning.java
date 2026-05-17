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
        System.out.println("  📊 优化公式: 吞吐 ∝ batch.size × (1/linger.ms)，CPU 利用率随压缩类型变化");
    }

    static void consumerTuning() {
        printSection("2. Consumer 端调优");

        System.out.printf("  %-28s %-10s %-30s%n", "参数", "默认值", "调优建议");
        System.out.println("  " + "-".repeat(68));
        printRow3("fetch.min.bytes", "1", "增大到 1MB+ 减少请求次数，延迟会升高");
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
