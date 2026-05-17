package com.nopr.mq.kafka.simulate;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】低延迟优化 —— 零拷贝·PageCache·mmap·sendfile
 * 【描述】模拟 Kafka 低延迟实现原理：传统 read/write（4次拷贝+4次上下文切换）
 *         vs sendfile 零拷贝（2次拷贝+2次上下文切换）。
 *         演示数据从磁盘到网络的传输路径差异。
 * 【关键概念】零拷贝、sendfile、mmap、DMA、PageCache、上下文切换、
 *             内核态/用户态、Socket Buffer、DMA 拷贝
 * 【关联类】@see com.nopr.mq.kafka.simulate.BatchSendDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class LowLatencyDemo {

    static long simulateTraditionalSend(long dataSize) {
        long copies = 4;
        long contextSwitches = 4;
        long latency = dataSize / 1024;
        System.out.printf("  传统 IO: %d 次拷贝 + %d 次上下文切换, ~%d μs%n",
                copies, contextSwitches, latency * copies);
        return latency * copies;
    }

    static long simulateSendfile(long dataSize) {
        long dmaCopies = 2;
        long contextSwitches = 2;
        long latency = dataSize / 2048;
        System.out.printf("  sendfile: %d 次 DMA 拷贝 + %d 次上下文切换, ~%d μs%n",
                dmaCopies, contextSwitches, latency * dmaCopies);
        return latency * dmaCopies;
    }

    static long simulateMmap(long dataSize) {
        long copies = 3;
        long contextSwitches = 4;
        long latency = dataSize / 1536;
        System.out.printf("  mmap+write: %d 次拷贝 + %d 次上下文切换, ~%d μs%n",
                copies, contextSwitches, latency * copies);
        return latency * copies;
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 低延迟优化：零拷贝 Demo");
        System.out.println("=".repeat(60));

        long dataSize = 1_048_576;

        System.out.println("\n--- 传输 1MB 消息到网络 ---");
        long traditional = simulateTraditionalSend(dataSize);
        long sendfile = simulateSendfile(dataSize);
        long mmap = simulateMmap(dataSize);

        System.out.printf("%n  性能对比：%n");
        System.out.printf("  sendfile 比传统 IO 快约 %.1f 倍%n", (double) traditional / sendfile);
        System.out.printf("  零拷贝减少了 %.0f%% 的 CPU 拷贝开销%n",
                (1.0 - (double) sendfile / traditional) * 100);

        System.out.println("\n\uD83D\uDCA1 Kafka 通过 sendfile 实现零拷贝，数据直接从 PageCache → NIC");
        System.out.println("  配合 PageCache 命中率 > 90%，尾部延迟可控制在个位数毫秒");
    }
}
