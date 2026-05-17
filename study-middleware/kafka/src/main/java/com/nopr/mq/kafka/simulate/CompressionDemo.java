package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】消息压缩 —— gzip·snappy·lz4·zstd 压缩率与速度对比
 * 【描述】模拟 Kafka 消息压缩：Producer 端压缩 → Broker 原样存储 → Consumer 端解压
 *         （端到端压缩）。演示四种压缩算法的选择策略：gzip（高压缩率/慢）、
 *         snappy（低压缩率/快）、lz4（均衡）、zstd（新一代/可调级别）。
 * 【关键概念】消息压缩、compression.type、端到端压缩、gzip/snappy/lz4/zstd、
 *             batch 压缩效率、compression.level、CPU vs 带宽权衡
 * 【关联类】@see com.nopr.mq.kafka.simulate.BatchSendDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class CompressionDemo {

    record CompressionResult(String algorithm, int originalSize, int compressedSize,
                              double ratio, double speedMBps, String useCase) {}

    private static final Random RANDOM = new Random(42);

    static String generateMessage(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    static String generateJsonMessage() {
        String[] names = {"Alice", "Bob", "Charlie", "Diana", "Eve", "Frank"};
        String[] cities = {"Beijing", "Shanghai", "Shenzhen", "Hangzhou", "Chengdu"};
        return String.format("""
            {"userId":%d,"name":"%s","age":%d,"city":"%s","amount":%.2f,"items":["item-%d","item-%d","item-%d"]}""",
                RANDOM.nextInt(10000), names[RANDOM.nextInt(names.length)],
                20 + RANDOM.nextInt(50), cities[RANDOM.nextInt(cities.length)],
                RANDOM.nextDouble() * 1000,
                RANDOM.nextInt(100), RANDOM.nextInt(100), RANDOM.nextInt(100));
    }

    static int simulateGzip(int originalSize) {
        return (int) (originalSize * 0.25);
    }

    static int simulateSnappy(int originalSize) {
        return (int) (originalSize * 0.50);
    }

    static int simulateLz4(int originalSize) {
        return (int) (originalSize * 0.40);
    }

    static int simulateZstd(int originalSize, int level) {
        double ratio = level <= 3 ? 0.35 : 0.20;
        return (int) (originalSize * ratio);
    }

    static double compressSpeed(String algo) {
        return switch (algo) {
            case "gzip" -> 25.0;
            case "snappy" -> 250.0;
            case "lz4" -> 500.0;
            case "zstd-default" -> 400.0;
            case "zstd-max" -> 100.0;
            default -> 100.0;
        };
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 消息压缩 Demo");
        System.out.println("=".repeat(60));

        System.out.println("\n--- 纯文本日志压缩 ---");
        String logMsg = generateMessage(4096);
        int logSize = logMsg.getBytes().length;
        System.out.printf("  原始大小: %s%n", formatBytes(logSize));

        List<CompressionResult> results = new ArrayList<>();
        results.add(new CompressionResult("gzip", logSize, simulateGzip(logSize),
                (double) simulateGzip(logSize) / logSize, compressSpeed("gzip"),
                "高压缩率，适合长期归档"));
        results.add(new CompressionResult("snappy", logSize, simulateSnappy(logSize),
                (double) simulateSnappy(logSize) / logSize, compressSpeed("snappy"),
                "低 CPU 开销，适合延迟敏感"));
        results.add(new CompressionResult("lz4", logSize, simulateLz4(logSize),
                (double) simulateLz4(logSize) / logSize, compressSpeed("lz4"),
                "均衡之选，Kafka 默认推荐"));
        results.add(new CompressionResult("zstd (level=3)", logSize, simulateZstd(logSize, 3),
                (double) simulateZstd(logSize, 3) / logSize, compressSpeed("zstd-default"),
                "新一代算法，自适应"));
        results.add(new CompressionResult("zstd (level=19)", logSize, simulateZstd(logSize, 19),
                (double) simulateZstd(logSize, 19) / logSize, compressSpeed("zstd-max"),
                "极限压缩，适合带宽紧张"));

        System.out.printf("%n  %-18s %-10s %-8s %-10s %s%n",
                "算法", "压缩后", "压缩率", "速度", "适用场景");
        System.out.println("  " + "-".repeat(80));
        for (CompressionResult r : results) {
            System.out.printf("  %-18s %-10s %-8s %-10s %s%n",
                    r.algorithm(),
                    formatBytes(r.compressedSize()),
                    String.format("%.0f%%", r.ratio() * 100),
                    r.speedMBps() + " MB/s",
                    r.useCase());
        }

        System.out.println("\n--- JSON 批量消息压缩效果 ---");
        int batchSize = 100;
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < batchSize; i++) {
            batch.append(generateJsonMessage()).append("\n");
        }
        int batchBytes = batch.toString().getBytes().length;
        System.out.printf("  %d 条 JSON 消息批量: %s%n", batchSize, formatBytes(batchBytes));
        System.out.printf("  gzip 压缩后: %s (%.0f%% 压缩率，批量效果更好)%n",
                formatBytes(simulateGzip(batchBytes)),
                (double) simulateGzip(batchBytes) / batchBytes * 100);
        System.out.printf("  lz4  压缩后: %s (%.0f%% 压缩率)%n",
                formatBytes(simulateLz4(batchBytes)),
                (double) simulateLz4(batchBytes) / batchBytes * 100);

        System.out.println("\n💡 压缩是 CPU 与带宽的权衡：vtradeoff");
        System.out.println("   推荐: 延迟敏感 → lz4 | 带宽紧张 → zstd | 兼容性优先 → gzip");
        System.out.println("   Kafka producer 端压缩，Broker 原样存储，Consumer 端解压");
    }

    static String formatBytes(int bytes) {
        if (bytes >= 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
