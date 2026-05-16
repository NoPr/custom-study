package base.flink.interview;

import java.util.*;

/**
 * Flink 面试 5 题自测：Watermark 乱序窗口触发、Checkpoint vs Savepoint、
 * 怎么保证 Exactly-Once、反压(backpressure)机制、Flink vs Spark Streaming。
 *
 * <p>每条问题给出面试回答框架 + 关键得分点。
 *
 * @author study-tuling
 */
public class Q01_Flink_Core {

    /* ======================== 表格模型 ======================== */

    record CompareRow(String name, String col1, String col2, String col3, String col4, String col5) {}

    /* ======================== main ======================== */

    public static void main(String[] args) {
        q01_watermarkLateData();
        q02_checkpointVsSavepoint();
        q03_exactlyOnceGuarantee();
        q04_backpressure();
        q05_flinkVsSparkStreaming();
        summary();
    }

    /* ======================== Q1: Watermark 乱序窗口触发 ======================== */

    static void q01_watermarkLateData() {
        System.out.println("═".repeat(70));
        System.out.println("Q1: Watermark 如何解决乱序数据 + 延迟窗口触发条件？");
        System.out.println("═".repeat(70));

        System.out.println("""
                
                📌 回答框架：Watermark 定义 → BoundedOutOfOrderness → 窗口触发 → 延迟数据处理

                1. Watermark 定义
                   Watermark = maxEventTimestamp - outOfOrdernessMs
                   含义: "时间戳小于 Watermark 的数据都已到达"
                   作用: 告诉窗口系统"截止到此 watermark，数据被认为完整"

                2. BoundedOutOfOrdernessWatermarkGenerator
                   允许最大乱序延迟, 例如 3s
                   watermark = max(eventTimestamp) - 3000
                   即使数据乱序到达(比如 t=1000 在 t=5000 之后到)，
                   只要在 watermark 推进前到达，仍可被窗口接收。

                3. 窗口触发条件
                   窗口 [00:00, 00:05) 的触发:
                     watermark >= window.end (5000ms)
                     即: watermark >= 5000
                   该窗口计算完成后，晚于 watermark 的新数据 → 延迟数据处理

                4. 延迟数据处理 (SideOutput)
                   - allowedLateness: 允许窗口等待一段时间再关闭
                   - SideOutput: 迟于 allowedLateness 的数据 → 侧输出流
                   - 默认丢弃(不推荐)

                5. 窗口生命周期
                   创建: 第一条数据到达 (Flink 自动创建窗口)
                   触发: watermark >= window.end
                   清理: watermark >= window.end + allowedLateness
                """);
    }

    /* ======================== Q2: Checkpoint vs Savepoint ======================== */

    static void q02_checkpointVsSavepoint() {
        System.out.println("═".repeat(70));
        System.out.println("Q2: Checkpoint vs Savepoint 区别及使用场景？");
        System.out.println("═".repeat(70));

        System.out.println();

        String fmt = "| %-26s | %-20s | %-20s |%n";
        System.out.printf(fmt, "维度", "Checkpoint", "Savepoint");
        System.out.println("|----------------------------|----------------------|----------------------|");
        List<CompareRow> rows = List.of(
                new CompareRow("触发方式", "自动(JM定时)", "手动(CLI/REST)", "", "", ""),
                new CompareRow("目的", "故障恢复", "版本升级/代码迁移", "", "", ""),
                new CompareRow("生命周期", "完成后自动清理", "永久(手动删除)", "", "", ""),
                new CompareRow("状态格式", "后端原生格式", "标准化格式(可迁移)", "", "", ""),
                new CompareRow("并行度", "必须匹配原并行度", "可修改并行度", "", "", ""),
                new CompareRow("UID 要求", "可选(但强烈推荐)", "必须设置MAX_WATERMARKS", "", "", ""),
                new CompareRow("增量支持", "支持增量CK", "全量快照", "", "", ""),
                new CompareRow("作业状态", "运行时触发", "需停止/重启作业", "", "", ""),
                new CompareRow("存储位置", "配置的CK目录", "手动指定目录", "", "", "")
        );

        System.out.println("|----------------------------|----------------------|----------------------|");
        for (CompareRow row : rows) {
            System.out.printf(formatCompareRow(row), row.col1(), row.col2(), row.col3(), row.col4(), row.col5());
        }
        System.out.println("|----------------------------|----------------------|----------------------|");

        System.out.println("\n  💡 面试加分回答:");
        System.out.println("    - Checkpoint 配置: env.enableCheckpointing(5000, EXACTLY_ONCE)");
        System.out.println("    - 建议: minPauseBetweenCheckpoints ≥ checkpointInterval");
        System.out.println("    - RocksDB 增量 CK: state.backend.incremental=true");
        System.out.println("    - Savepoint 触发前必须为所有 Operator 设置 uid()");
        System.out.println("    - Savepoint 不可替代 Checkpoint (频率太低)");
        System.out.println("    - 常见坑: 升级 Flink 版本时，并行度变了导致恢复失败\n");
    }

    /* ======================== Q3: Exactly-Once 保证 ======================== */

    static void q03_exactlyOnceGuarantee() {
        System.out.println("═".repeat(70));
        System.out.println("Q3: Flink 怎么保证 Exactly-Once 语义？");
        System.out.println("═".repeat(70));

        System.out.println("""
                
                📌 回答框架：分两层：
                  A. Flink 内部 Exactly-Once (Checkpoint + Barrier 对齐)
                  B. 端到端 Exactly-Once (Checkpoint + TwoPhaseCommitSink)

                A. Flink 内部 Exactly-Once (状态一致性)
                ────────────────────────────────
                1. Checkpoint 机制:
                   - JM 定期向 Source 注入 Barrier
                   - Barrier 随数据流向下游传递
                   - Operator 收到所有上游 Barrier → 做本地状态快照
                   - Sink 完成 → 通知 JM Checkpoint 完成

                2. Barrier 对齐 (Exactly-Once 核心):
                   - 多输入 Operator 收到 Barrier，阻塞该 Channel 的数据
                   - 等待所有 Channel Barrier 到达
                   - 对齐完成 → 本地状态快照 → 非阻塞恢复数据

                3. 故障恢复:
                   - 从最近 Checkpoint 恢复所有算子状态
                   - Source 重置 Offset (Kafka Partition Offset)
                   - 恢复后继续处理，保证 Exactly-Once (不重不丢)

                B. 端到端 Exactly-Once (Sink 幂等+事务)
                ────────────────────────────────
                1. Sink 幂等写入:
                   - 适用于 HDFS/FileSink → 覆盖写入 + checkpoint 后 rename

                2. TwoPhaseCommitSink (FlinkKafkaProducer EXACTLY_ONCE):
                   Phase 1 (pre-commit):
                     - Flink CK 完成 → Sink 执行 preCommit (kafkaProducer.flush())
                   Phase 2 (commit):
                     - JM 确认 CK 完成 → Sink 执行 commit (kafkaProducer.commitTransaction())
                   Phase 3 (abort):
                     - CK 失败 → Sink 执行 abort (kafkaProducer.abortTransaction())

                3. 前置条件:
                   - Source 支持重置 (Kafka, File)
                   - Sink 支持事务/幂等 (Kafka 0.11+, FileSystem)
                   - env.enableCheckpointing(5000, CheckpointingMode.EXACTLY_ONCE)

                C. At-Least-Once vs Exactly-Once:
                   - At-Least-Once: 不执行 Barrier 对齐，可能重复
                   - Exactly-Once: Barrier 对齐 + State持久化

                💡 加分回答:
                  - 问 Exactly-Once 实现细节 → 补充 Chandy-Lamport 算法
                  - 问 Kafka 端到端 → 补充 Kafka 事务 id = operator-uid + subtaskIdx
                """);
    }

    /* ======================== Q4: 反压机制 ======================== */

    static void q04_backpressure() {
        System.out.println("═".repeat(70));
        System.out.println("Q4: Flink 反压 (backpressure) 机制是怎样的？");
        System.out.println("═".repeat(70));

        System.out.println("""
                
                📌 回答框架：本质 → Netty 水位线 → 逐级传送

                1. Flink 反压的本质
                   下游算子处理慢 → 数据 Channel 缓冲区满 → TCP 滑动窗口归零
                   → 上游 Netty 无法写入 → 上游算子阻塞等待
                   → 逐级向上传递到 Source → Source 暂停拉取数据

                2. Flink 1.5+ 的 Credit-Based Flow Control 机制
                   传统 TCP 反压 (Flink < 1.5):
                     - 底层 TCP 缓冲区满 → 阻塞 Netty 写 → 上游停 → 逐级反压
                     - 问题: 一个慢 Task 阻塞整个链路 (Head-of-Line Blocking)
                     - 效率: O(N) 逐级传递，上游感知慢

                   Credit-Based (Flink ≥ 1.5):
                     - 下游明确通告可用 Buffer 数量 (credit)
                     - 上游仅在有 credit 时发送数据
                     - 优势:
                       ✓ 细粒度控制 (每条链路独立 credit)
                       ✓ 无 Head-of-Line Blocking
                       ✓ 上游立即可感知下游瓶颈

                3. 反压监控与定位
                   - WebUI 显示每个 Operator 的反压状态 (HIGH/LOW/OK)
                   - 底层: 采样 Thread dump + GC 行为
                   - 定位: 从 Sink 往上追踪，第一个 HIGH → 瓶颈算子

                4. 常见反压场景及解决方案
                   ┌────────────────────────────┬──────────────────────────────────┐
                   │ 场景                     │ 解决方案                            │
                   ├────────────────────────────┼──────────────────────────────────┤
                   │ Sink 写入外部存储慢      │ - 增加 Sink 并行度                  │
                   │                          │ - Sink 批量写入 (buffer batch)     │
                   ├────────────────────────────┼──────────────────────────────────┤
                   │ KeyBy 数据倾斜           │ - 加随机前缀 + 两阶段聚合           │
                   │                          │ - 热点 Key 单独处理                │
                   ├────────────────────────────┼──────────────────────────────────┤
                   │ Window 计算慢            │ - 预聚合 (ReduceFunction)          │
                   │                          │ - 减少窗口大小                     │
                   ├────────────────────────────┼──────────────────────────────────┤
                   │ GC 频繁                  │ - RocksDB 后端 + 调大供内存        │
                   │                          │ - 关掉 MemoryBackend               │
                   └────────────────────────────┴──────────────────────────────────┘
                """);
    }

    /* ======================== Q5: Flink vs Spark Streaming ======================== */

    static void q05_flinkVsSparkStreaming() {
        System.out.println("═".repeat(70));
        System.out.println("Q5: Flink vs Spark Streaming vs MR 对比");
        System.out.println("═".repeat(70));

        String fmt = "| %-24s | %-20s | %-22s | %-22s |%n";
        System.out.printf(fmt, "维度", "Flink", "Spark Streaming", "MapReduce");
        System.out.println("|--------------------------|----------------------|------------------------|------------------------|");

        List<CompareRow> rows = List.of(
                new CompareRow("计算模型", "流式计算(实时)", "微批处理(MicroBatch)", "批处理(Batch)", "", ""),
                new CompareRow("延迟", "毫秒级(< 100ms)", "秒级(亚秒-几秒)", "分钟级~小时级", "", ""),
                new CompareRow("数据抽象", "Stream(无界)", "DStream(微批RDD)", "HDFS文件(有界)", "", ""),
                new CompareRow("状态管理", "KeyedState/OperatorState", "updateStateByKey(RDD)", "无状态", "", ""),
                new CompareRow("Checkpoint", "Chandy-Lamport异步", "WAL/lineage重算", "无", "", ""),
                new CompareRow("Exactly-Once", "✅ (2PC Sink)", "⚠ (需外部幂等)", "❌", "", ""),
                new CompareRow("窗口机制", "EventTime/P-Time", "P-Time only", "无", "", ""),
                new CompareRow("反压机制", "Credit-Based", "Rate Limiter", "无", "", ""),
                new CompareRow("SQL 支持", "✅ ANSI SQL", "✅ Spark SQL", "❌ (HiveQL)", "", ""),
                new CompareRow("应用场景", "实时大屏/风控", "准实时 ETL/ML", "离线日志分析", "", "")
        );

        System.out.println("|--------------------------|----------------------|------------------------|------------------------|");
        for (CompareRow row : rows) {
            System.out.printf(fmt, row.col1(), row.col2(), row.col3(), row.col4());
        }
        System.out.println("|--------------------------|----------------------|------------------------|------------------------|");
        print("");
        print("💡 核心区别一句话总结:");
        print("  - Flink: 真正的流处理，来一条处理一条，毫秒延迟");
        print("  - Spark Streaming: 微批处理，N 秒一批，不是真正的实时");
        print("  - MapReduce: 批处理，分 Map 和 Reduce 两个阶段，离线计算");
        print("");
        print("扩展：Flink vs Storm");
        print("  Storm: 纯流处理（Tuple by Tuple），无状态管理，At-Most-Once");
        print("  Flink:  真正的流处理，有状态，Exactly-Once，更现代");
        print("  → 2020年 Flink 基本替代 Storm\n");
    }

    /* ======================== 总结 ======================== */

    static void summary() {
        System.out.println("═".repeat(70));
        System.out.println("💯 面试自测 Checklist");
        System.out.println("═".repeat(70));

        print("☐ Q1: Watermark = maxTimestamp - outOfOrderness, 窗口触发 condition");
        print("☐ Q2: CK(自动+故障恢复) vs SP(手动+升级迁移), UID 必须设");
        print("☐ Q3: Barrier 对齐 + Chandy-Lamport + TwoPhaseCommitSink");
        print("☐ Q4: Credit-Based Flow Control + 反压定位从 Sink 往上追踪");
        print("☐ Q5: Flink(毫秒) > Spark(秒) > MR(分钟), Exactly-Once 有/无");

        print("\n📝 额外高频问题:");
        print("  Q6: Flink 支持哪些时间语义？（EventTime / ProcessingTime / IngestionTime）");
        print("  Q7: Flink 如何处理迟到数据？（SideOutput / AllowedLateness）");
        print("  Q8: Flink 的并行度怎么设置？（Source并行=Kafka分区数，下游逐算子调优）");
        print("  Q9: Flink 的状态后端怎么选？（RocksDB 海量，HashMap 低延迟）");
        print("  Q10: Flink 的 Slot 与 Task 的关系？（1 Slot = 1 Pipeline，Task 共享 Slot）");
    }

    /* ======================== 工具 ======================== */

    static void print(String s) {
        System.out.println(s);
    }

    static String formatCompareRow(CompareRow row) {
        return "  " + row.col1() + "  |  " + row.col2() + "  |  " + row.col3() + "  |  " + row.col4() + "  |  "
                + row.col5() + "  |";
    }
}