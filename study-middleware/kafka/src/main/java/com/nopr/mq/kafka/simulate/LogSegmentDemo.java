package com.nopr.mq.kafka.simulate;

import java.util.*;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】日志段管理 —— Segment 切分·offset index·time index·日志清理
 * 【描述】模拟 Kafka Log Segment 管理机制：Topic-Partition 的日志被切分为
 *         多个 segment 文件（.log + .index + .timeindex）。演示 segment 的
 *         创建、滚动（基于 log.segment.bytes）、稀疏索引查找（二分定位 segment
 *         → offset index 查位置）、以及基于时间的消息查找。
 * 【关键概念】LogSegment、.log/.index/.timeindex、稀疏索引、segment.bytes、
 *             log.index.interval.bytes、二分查找、log.segment.bytes
 * 【关联类】@see com.nopr.mq.kafka.simulate.LogCleanupDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class LogSegmentDemo {

    static final int SEGMENT_BYTES = 1024;

    record IndexEntry(long offset, int position) {}

    record Message(long offset, String key, String value, long timestamp) {}

    static class LogSegment {
        final String baseName;
        final long baseOffset;
        final List<Message> messages = new ArrayList<>();
        final List<IndexEntry> offsetIndex = new ArrayList<>();
        final List<IndexEntry> timeIndex = new ArrayList<>();
        int currentSize = 0;

        LogSegment(long baseOffset) {
            this.baseOffset = baseOffset;
            this.baseName = String.format("%020d", baseOffset);
        }

        boolean append(Message msg) {
            int msgSize = msg.key().length() + msg.value().length() + 20;
            if (currentSize + msgSize > SEGMENT_BYTES) return false;

            messages.add(msg);
            currentSize += msgSize;

            if (messages.size() % 3 == 0) {
                offsetIndex.add(new IndexEntry(msg.offset(), currentSize - msgSize));
                timeIndex.add(new IndexEntry(msg.offset(), (int) (msg.timestamp() / 1000)));
            }
            return true;
        }

        int size() { return messages.size(); }
        int bytes() { return currentSize; }
        boolean isFull() { return currentSize >= SEGMENT_BYTES; }
    }

    static class LogManager {
        private final List<LogSegment> segments = new ArrayList<>();
        private long nextOffset = 0;

        void append(String key, String value) {
            Message msg = new Message(nextOffset++, key, value, System.currentTimeMillis());

            if (segments.isEmpty() || segments.get(segments.size() - 1).isFull()) {
                LogSegment newSeg = new LogSegment(msg.offset());
                segments.add(newSeg);
                System.out.printf("  \uD83D\uDCC1 创建新 segment: %s.log (baseOffset=%d)%n",
                        newSeg.baseName, newSeg.baseOffset);
            }

            LogSegment active = segments.get(segments.size() - 1);
            if (!active.append(msg)) {
                LogSegment newSeg = new LogSegment(msg.offset());
                segments.add(newSeg);
                newSeg.append(msg);
                System.out.printf("  \uD83D\uDCC1 Segment 满，滚动到: %s.log%n", newSeg.baseName);
            }
        }

        Message findByOffset(long targetOffset) {
            // 二分定位 segment
            int lo = 0, hi = segments.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) / 2;
                LogSegment seg = segments.get(mid);
                long segEnd = seg.baseOffset + seg.messages.size();
                if (targetOffset < seg.baseOffset) hi = mid - 1;
                else if (targetOffset >= segEnd) lo = mid + 1;
                else {
                    // 在 segment 内用 offset index 快速定位
                    int posInSeg = (int) (targetOffset - seg.baseOffset);
                    for (Message m : seg.messages) {
                        if (m.offset() == targetOffset) return m;
                    }
                    return null;
                }
            }
            return null;
        }

        void printSegments() {
            System.out.printf("%n  Segment 列表 (%d 个):%n", segments.size());
            System.out.printf("  %-22s %-14s %-10s %s%n",
                    "Segment", "BaseOffset", "消息数", "大小");
            System.out.println("  " + "-".repeat(56));
            for (LogSegment seg : segments) {
                System.out.printf("  %-22s %-14d %-10d %s%n",
                        seg.baseName + ".log", seg.baseOffset,
                        seg.size(), formatBytes(seg.bytes()));
            }
        }

        void printIndexDetail() {
            for (LogSegment seg : segments) {
                System.out.printf("%n  Segment %s.log:%n", seg.baseName);
                System.out.println("    offset index (稀疏，每3条一个):");
                for (IndexEntry e : seg.offsetIndex) {
                    System.out.printf("      offset=%d → position=%d%n", e.offset(), e.position());
                }
                System.out.printf("    消息列表 (%d 条):%n", seg.messages.size());
                for (int i = 0; i < Math.min(5, seg.messages.size()); i++) {
                    Message m = seg.messages.get(i);
                    System.out.printf("      offset=%d key=%s value=%s%n",
                            m.offset(), m.key(), m.value());
                }
                if (seg.messages.size() > 5) {
                    System.out.printf("      ... 还有 %d 条%n", seg.messages.size() - 5);
                }
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka LogSegment 日志段管理 Demo");
        System.out.println("=".repeat(60));
        System.out.printf("  segment.bytes = %d (小值演示滚动)%n", SEGMENT_BYTES);

        LogManager log = new LogManager();

        System.out.println("\n--- 写入消息（触发 segment 滚动）---");
        for (int i = 1; i <= 50; i++) {
            String value = "消息内容-" + i + "-" + "x".repeat(20);
            log.append("key-" + i, value);
        }

        log.printSegments();

        System.out.println("\n--- 按 offset 查找 ---");
        Message found = log.findByOffset(5);
        if (found != null) {
            System.out.printf("  查找 offset=5: key=%s value=%s%n",
                    found.key(), found.value());
        }

        found = log.findByOffset(25);
        if (found != null) {
            System.out.printf("  查找 offset=25: key=%s value=%s%n",
                    found.key(), found.value());
        }

        found = log.findByOffset(49);
        if (found != null) {
            System.out.printf("  查找 offset=49: key=%s value=%s%n",
                    found.key(), found.value());
        }

        System.out.println("\n--- 索引详情 ---");
        log.printIndexDetail();

        System.out.println("\n💡 Kafka 稀疏索引：每 log.index.interval.bytes 写入一个条目");
        System.out.println("   查找流程：二分定位 Segment → offset index 定位 approximate → 顺序扫描精确位置");
    }

    static String formatBytes(int bytes) {
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
