package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】零拷贝详解 —— Buffer 级数据路径·DMA·sendfile·splice
 * 【描述】深入模拟 Kafka 零拷贝原理：逐步骤对比传统 IO（read+write）
 *         与 sendfile 的数据传输路径。追踪每个 buffer 的数据状态：
 *         Disk → PageCache → UserBuffer → SocketBuffer → NIC。
 *         sendfile 绕过用户态，数据从 PageCache 直接 DMA 到 NIC。
 * 【关键概念】零拷贝、sendfile、splice、DMA 引擎、PageCache、
 *             内核态/用户态、Socket Buffer、scatter-gather
 * 【关联类】@see com.nopr.mq.kafka.simulate.LowLatencyDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class ZeroCopyDemo {

    static class Buffer {
        final String name;
        byte[] data;
        int size;

        Buffer(String name, int capacity) {
            this.name = name;
            this.data = new byte[capacity];
            this.size = 0;
        }
    }

    enum Step {
        DMA_READ("DMA 拷贝: Disk → PageCache"),
        CPU_COPY_K2U("CPU 拷贝: PageCache → UserBuffer"),
        CPU_COPY_U2S("CPU 拷贝: UserBuffer → SocketBuffer"),
        DMA_WRITE("DMA 拷贝: SocketBuffer → NIC"),
        DMA_SENDFILE("DMA 拷贝: PageCache → NIC (sendfile)");

        final String desc;
        Step(String desc) { this.desc = desc; }
    }

    record TraceStep(Step step, String from, String to, int bytes, long costNs) {}

    static List<TraceStep> traceTraditional(int dataSize) {
        List<TraceStep> trace = new ArrayList<>();
        long costPerByte = 10;

        trace.add(new TraceStep(Step.DMA_READ, "Disk", "PageCache", dataSize, dataSize * costPerByte));
        trace.add(new TraceStep(Step.CPU_COPY_K2U, "PageCache", "UserBuffer", dataSize, dataSize * costPerByte * 2));
        trace.add(new TraceStep(Step.CPU_COPY_U2S, "UserBuffer", "SocketBuffer", dataSize, dataSize * costPerByte * 2));
        trace.add(new TraceStep(Step.DMA_WRITE, "SocketBuffer", "NIC", dataSize, dataSize * costPerByte));

        return trace;
    }

    static List<TraceStep> traceSendfile(int dataSize) {
        List<TraceStep> trace = new ArrayList<>();
        long costPerByte = 5;

        trace.add(new TraceStep(Step.DMA_READ, "Disk", "PageCache", dataSize, dataSize * costPerByte));
        trace.add(new TraceStep(Step.DMA_SENDFILE, "PageCache", "NIC", dataSize, dataSize * costPerByte));

        return trace;
    }

    static void printTrace(List<TraceStep> trace, String title) {
        System.out.println("\n--- " + title + " ---");
        System.out.printf("  %-60s %-10s %-12s%n", "步骤", "数据量", "耗时");
        System.out.println("  " + "-".repeat(82));
        long totalNs = 0;
        for (TraceStep step : trace) {
            System.out.printf("  %-60s %-10s %-12s%n",
                    step.step().desc,
                    formatBytes(step.bytes()),
                    formatNs(step.costNs()));
            totalNs += step.costNs();
        }
        System.out.println("  " + "=".repeat(82));
        System.out.printf("  %-60s %-10s %-12s%n", "总计", "", formatNs(totalNs));
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 零拷贝详解：Buffer 级数据路径 Demo");
        System.out.println("=".repeat(60));

        int dataSize = 1_048_576;

        System.out.printf("%n  消息大小: %s%n", formatBytes(dataSize));
        System.out.println();

        List<TraceStep> traditional = traceTraditional(dataSize);
        printTrace(traditional, "传统 IO: read() → write()");

        System.out.println("\n  📊 传统 IO 特点:");
        System.out.println("    - 4 次上下文切换（read/write 各 2 次）");
        System.out.println("    - 4 次数据拷贝（2 次 DMA + 2 次 CPU）");
        System.out.println("    - 数据经过用户态，CPU 参与拷贝，浪费 CPU 资源");

        List<TraceStep> sendfile = traceSendfile(dataSize);
        printTrace(sendfile, "sendfile 零拷贝");

        System.out.println("\n  📊 sendfile 特点:");
        System.out.println("    - 2 次上下文切换（sendfile 调用 + 返回）");
        System.out.println("    - 2 次 DMA 拷贝（数据不经过用户态）");
        System.out.println("    - CPU 零参与数据拷贝，数据从 PageCache 直接到网卡");

        long traditionalNs = traditional.stream().mapToLong(TraceStep::costNs).sum();
        long sendfileNs = sendfile.stream().mapToLong(TraceStep::costNs).sum();

        System.out.printf("%n  🚀 性能提升: sendfile 比传统 IO 快 %.1f 倍%n",
                (double) traditionalNs / sendfileNs);
        System.out.printf("  CPU 拷贝减少: %.0f%%%n",
                (1.0 - (double) sendfileNs / traditionalNs) * 100);

        System.out.println("\n💡 Kafka 在 Linux 上使用 sendfile 系统调用实现零拷贝");
        System.out.println("   数据路径: FileChannel.transferTo() → sendfile() → DMA → NIC");
    }

    static String formatBytes(int bytes) {
        if (bytes >= 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    static String formatNs(long ns) {
        if (ns >= 1_000_000) return String.format("%.1f ms", ns / 1_000_000.0);
        if (ns >= 1_000) return String.format("%.1f μs", ns / 1_000.0);
        return ns + " ns";
    }
}
