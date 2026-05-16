package base.seatunnel.interview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seatunnel 面试高频问题：Seatunnel vs DataX vs Flink CDC，插件执行原理，分片策略对比，断点续传机制。
 *
 * <p>问题覆盖：
 * <ol>
 *   <li>Seatunnel 与 DataX / Flink CDC 的核心区别</li>
 *   <li>插件 SPI 加载与执行原理</li>
 *   <li>JDBC 分片策略对比（Range vs Mod vs Hash）</li>
 *   <li>断点续传 Checkpoint/Savepoint 机制</li>
 *   <li>Seatunnel 架构中的 Source/Transform/Sink 协同原理</li>
 * </ol>
 *
 * <p>运行方式：直接执行 main()，输出面试标准答案。
 *
 * @author study-tuling
 */
public class Q01_Seatunnel_Architecture {

    public static void main(String[] args) {
        System.out.println("+--------------------------------------------------------------+");
        System.out.println("|     Seatunnel 面试 -- 架构原理与对比                          |");
        System.out.println("+--------------------------------------------------------------+\n");

        q01SeatunnelVsDataXVsFlinkCDC();
        q02PluginExecutionPrinciple();
        q03ShardingStrategyComparison();
        q04CheckpointSavepointMechanism();
        q05SourceTransformSinkSynergy();

        System.out.println("+--------------------------------------------------------------+");
        System.out.println("|     面试问题解答完成                                          |");
        System.out.println("+--------------------------------------------------------------+");
    }


    // ==================== Q1: Seatunnel vs DataX vs Flink CDC ====================

    /**
     * Q1: Seatunnel 与 DataX / Flink CDC 的核心区别。
     *
     * <p>考察点：候选人对主流数据集成工具的定位理解。
     */
    static void q01SeatunnelVsDataXVsFlinkCDC() {
        System.out.println("---------------------------------------------------------------");
        System.out.println("Q1: Seatunnel vs DataX vs Flink CDC 有什么区别？");
        System.out.println("---------------------------------------------------------------\n");

        System.out.println("【标准答案】\n");

        System.out.println("1. DataX（阿里巴巴开源）");
        System.out.println("   |-- 定位：离线全量/增量数据同步工具");
        System.out.println("   |-- 架构：Reader -> Framework -> Writer，不支持 Transform");
        System.out.println("   |-- 执行模型：单机多线程，一个 Job 拆分为多个 Task 并行执行");
        System.out.println("   |-- 同步方式：基于 JDBC/SDK 拉取数据再写入，非实时流");
        System.out.println("   |-- 分片策略：仅支持 Range 分片（按主键范围拆分）");
        System.out.println("   |-- 适用场景：T+1 离线数据同步，数据量大但对实时性要求不高\n");

        System.out.println("2. Flink CDC（Apache Flink + CDC Connector）");
        System.out.println("   |-- 定位：实时 CDC 增量数据捕获，流处理引擎");
        System.out.println("   |-- 架构：基于 Flink 分布式流处理 + Debezium 引擎解析 Binlog");
        System.out.println("   |-- 执行模型：分布式流处理，支持 exactly-once 语义");
        System.out.println("   |-- 同步方式：伪装成 MySQL Slave，订阅 Binlog 实时捕获变更");
        System.out.println("   |-- 分片策略：全量阶段支持 Snapshot Split + Binlog Split 混合模式");
        System.out.println("   |-- 适用场景：实时数仓、实时 ETL、需要低延迟(秒级)的数据同步\n");

        System.out.println("3. Seatunnel（Apache 孵化器项目，原名 Waterdrop）");
        System.out.println("   |-- 定位：高性能、分布式、海量数据同步 & 集成引擎");
        System.out.println("   |-- 架构：Source -> Transform(可选) -> Sink，插件化架构");
        System.out.println("   |-- 执行模型：支持离线批(Spark/Flink 引擎)和实时流(Flink/自研 Zeta)");
        System.out.println("   |-- 同步方式：支持 JDBC 全量/CDC 增量，统一 API 接入多种数据源");
        System.out.println("   |-- 分片策略：Range / Mod / Hash / 自定义分区，比 DataX 更灵活");
        System.out.println("   |-- 核心优势：");
        System.out.println("   |   |-- 多引擎兼容：可切换 Spark / Flink / Zeta(自研) 作为执行引擎");
        System.out.println("   |   |-- 全场景覆盖：离线同步 + CDC 实时同步 + 多表同步");
        System.out.println("   |   |-- 断点续传：内置 Checkpoint/Savepoint，支持从失败点恢复");
        System.out.println("   |   |-- 易扩展：SPI 插件机制，新增连接器只需实现接口");
        System.out.println("   |-- 适用场景：全场景数据集成，离线+实时，异构数据源互通\n");

        System.out.println("+-------------------------+----------------+------------------+---------------------+");
        System.out.println("| 维度         | DataX          | Flink CDC        | Seatunnel            |");
        System.out.println("+-------------------------+----------------+------------------+---------------------+");
        System.out.println("| 实时性       | T+1 离线       | 秒级实时         | 离线+实时            |");
        System.out.println("| 执行引擎     | 单机多线程     | Flink 分布式流   | Spark/Flink/Zeta     |");
        System.out.println("| 插件数量     | 20+            | 10+ (CDC为主)    | 100+ (覆盖广)        |");
        System.out.println("| Transform    | 不支持         | SQL + DataStream | SQL + 插件配置       |");
        System.out.println("| 断点续传     | 不支持         | Checkpoint       | Savepoint            |");
        System.out.println("| 分片策略     | Range          | Snapshot Split   | Range/Mod/Hash       |");
        System.out.println("| 社区活跃度   | 停止维护       | 活跃             | 活跃(Apache孵化)     |");
        System.out.println("+-------------------------+----------------+------------------+---------------------+\n");
    }


    // ==================== Q2: 插件执行原理 ====================

    /**
     * Q2: Seatunnel 插件 SPI 加载与执行原理。
     *
     * <p>考察点：对插件化架构的理解深度。
     */
    static void q02PluginExecutionPrinciple() {
        System.out.println("---------------------------------------------------------------");
        System.out.println("Q2: Seatunnel 插件是如何加载和执行的？");
        System.out.println("---------------------------------------------------------------\n");

        System.out.println("【标准答案】\n");

        System.out.println("1. SPI 插件发现机制（基于 Java ServiceLoader 协议）");
        System.out.println("   |-- META-INF/services/ 目录下放置插件描述文件");
        System.out.println("   |-- 文件名为接口全限定名(如 SeaTunnelSource)");
        System.out.println("   |-- 文件内容为具体实现类的全限定名");
        System.out.println("   |-- PluginLoader 运行时通过 ClassLoader 扫描并实例化\n");

        System.out.println("2. 插件生命周期（open -> process -> close）");
        System.out.println("   |-- open(config):   根据配置初始化连接（JDBC/Kafka/HDFS）");
        System.out.println("   |-- process():      核心处理逻辑");
        System.out.println("   |   |-- Source:     pollNext() 拉取数据，封装为 SeaTunnelRow");
        System.out.println("   |   |-- Transform:  对 SeaTunnelRow 进行类型转换/字段映射/过滤");
        System.out.println("   |   |-- Sink:       write(SeaTunnelRow) 写入目标端");
        System.out.println("   |-- close():        释放资源，关闭连接\n");

        System.out.println("3. 插件并行执行模型");
        System.out.println("   |-- Reader 并行度：由 parallelism 参数控制，每个分片对应一个 Reader");
        System.out.println("   |-- Writer 并行度：与 Reader 相同或独立配置");
        System.out.println("   |-- 数据通道：Reader -> (Encode) -> Shuffle -> (Decode) -> Writer");
        System.out.println("   |-- 协调器(Coordinator)：负责任务切分、分片分配、进度汇总\n");

        System.out.println("4. 插件类型体系");
        System.out.println("   |-- Source:    JDBC/Fake/Kafka/HDFS/Pulsar/HTTP/CDC...");
        System.out.println("   |-- Sink:      JDBC/Console/Kafka/HDFS/Hive/ClickHouse/ES...");
        System.out.println("   |-- Transform: Copy/FieldMapper/Filter/Replace/SQL/JsonPath/UUID...");
        System.out.println("   |-- 所有插件通过 SeaTunnelPlugin 抽象基类统一管理\n");
    }


    // ==================== Q3: 分片策略对比 ====================

    /**
     * Q3: JDBC 分片策略对比。
     *
     * <p>考察点：对并行读取的性能调优理解。
     */
    static void q03ShardingStrategyComparison() {
        System.out.println("---------------------------------------------------------------");
        System.out.println("Q3: JDBC 分片策略有哪些？各有什么优缺点？");
        System.out.println("---------------------------------------------------------------\n");

        System.out.println("【标准答案】\n");

        System.out.println("1. RANGE 分片（按主键范围均匀拆分）");
        System.out.println("   |-- 原理：按 split_column 的 min/max 值等分为 N 个区间");
        System.out.println("   |   生成: SELECT * FROM t WHERE id >= 1   AND id < 250001");
        System.out.println("   |         SELECT * FROM t WHERE id >= 250001 AND id < 500001");
        System.out.println("   |-- 优点：简单高效，单次查询区间确定，SQL 可走主键索引(range scan)");
        System.out.println("   |-- 缺点：");
        System.out.println("   |   |-- 数据倾斜：主键分布不均匀时各分片数据量差异大");
        System.out.println("   |   |-- 自增 ID 空洞：DELETE 过的 ID 空洞会导致空扫描");
        System.out.println("   |   |-- 仅适用于数值型自增主键");
        System.out.println("   |-- 适用：自增 ID 分布均匀的表\n");

        System.out.println("2. MOD_INT 分片（按数值取模）");
        System.out.println("   |-- 原理：MOD(split_column, total_split) = N");
        System.out.println("   |   生成: SELECT * FROM t WHERE MOD(id, 4) = 0");
        System.out.println("   |-- 优点：数据分布非常均匀（取模运算天然均匀）");
        System.out.println("   |-- 缺点：");
        System.out.println("   |   |-- 无法走主键索引，是 TABLE SCAN（全表扫描每个分片）");
        System.out.println("   |   |-- MySQL 中 MOD() 函数无法利用索引，性能差");
        System.out.println("   |-- 适用：数据量较小的表，或数值主键但分布极不均匀时\n");

        System.out.println("3. MOD_HASH 分片（按哈希取模，适用于字符串主键）");
        System.out.println("   |-- 原理：MOD(CRC32(split_column), total_split) = N");
        System.out.println("   |   生成: SELECT * FROM t WHERE MOD(CRC32(uuid), 8) = 0");
        System.out.println("   |-- 优点：适用于 UUID/字符串等非数值主键");
        System.out.println("   |-- 缺点：");
        System.out.println("   |   |-- CRC32 函数计算开销，全表扫描");
        System.out.println("   |   |-- 碰撞风险：CRC32 是 32 位哈希，值域有限");
        System.out.println("   |-- 适用：字符串 UUID 主键的表\n");

        System.out.println("4. 分片数据倾斜度对比（模拟）");
        Map<String, String> skew = new LinkedHashMap<>();
        skew.put("RANGE",     "倾斜度: *** (依赖主键分布)");
        skew.put("MOD_INT",   "倾斜度: *   (天然均匀)");
        skew.put("MOD_HASH",  "倾斜度: *   (哈希均匀)");
        skew.forEach((k, v) -> System.out.printf("   %-12s %s%n", k, v));

        System.out.println("\n5. Seatunnel 分片优化建议");
        System.out.println("   |-- 优先使用 RANGE 分片（能走索引，性能最优）");
        System.out.println("   |-- 主键分布不均匀时，使用 MOD_INT 或 MOD_HASH");
        System.out.println("   |-- 可以指定 partition_column 为非主键字段（如有索引的创建时间列）");
        System.out.println("   |-- 适当调整 split_num 参数：一般设为并发度的 2-4 倍");
        System.out.println("   |-- 超大表建议自定义分片 SQL：WHERE id BETWEEN ? AND ?\n");
    }


    // ==================== Q4: 断点续传机制 ====================

    /**
     * Q4: 断点续传 Checkpoint/Savepoint 机制。
     *
     * <p>考察点：对数据一致性保障机制的理解。
     */
    static void q04CheckpointSavepointMechanism() {
        System.out.println("---------------------------------------------------------------");
        System.out.println("Q4: Seatunnel 的断点续传机制是怎么实现的？");
        System.out.println("---------------------------------------------------------------\n");

        System.out.println("【标准答案】\n");

        System.out.println("1. Savepoint 核心概念");
        System.out.println("   |-- 定义：任务执行过程中定期保存的快照，记录每个 Reader 的消费进度");
        System.out.println("   |-- 内容：pipelineId + 各 Reader 的分片信息(主键/offset/时间戳)");
        System.out.println("   |-- 存储：可配置存储到本地文件/HDFS/S3\n");

        System.out.println("2. Savepoint 生成时机");
        System.out.println("   |-- 定时触发：checkpoint.interval 参数配置（如 30000ms = 30 秒）");
        System.out.println("   |-- Reader 汇报：每个 Reader 完成当前批次后上报自己的分片进度");
        System.out.println("   |-- Coordinator 汇总：所有 Reader 的进度汇总为一个 Savepoint");
        System.out.println("   |-- 正常退出：任务正常完成时生成最终 Savepoint\n");

        System.out.println("3. Savepoint 恢复流程（断点续传）");
        System.out.println("   |-- Step 1: 检测到任务失败（Reader 异常 / 网络超时 / 节点宕机）");
        System.out.println("   |-- Step 2: 读取最近的 Savepoint 文件");
        System.out.println("   |-- Step 3: 解析 Savepoint -> 提取每个分片的偏移信息");
        System.out.println("   |   示例: partition_0: offset=12345, partition_1: offset=23456");
        System.out.println("   |-- Step 4: 重新生成分片 SQL，WHERE 条件加上起始位置");
        System.out.println("   |   示例: SELECT * FROM t WHERE id >= 12345 AND id < 250001");
        System.out.println("   |-- Step 5: 继续执行，避免数据重复消费（At-Least-Once 语义）\n");

        System.out.println("4. 与 Flink Checkpoint 的对比");
        System.out.println("+--------------------+----------------------------+----------------------+");
        System.out.println("| 维度               | Seatunnel Savepoint        | Flink Checkpoint     |");
        System.out.println("+--------------------+----------------------------+----------------------+");
        System.out.println("| 触发方式           | 定时 + Reader 上报         | Barrier 对齐         |");
        System.out.println("| 存储内容           | Reader 进度 + 分片信息     | State 全量快照       |");
        System.out.println("| 语义保证           | At-Least-Once (默认)      | Exactly-Once (可选)  |");
        System.out.println("| 故障恢复时间       | 秒级 (读文件 -> 重切分)     | 毫秒级 (State 恢复)  |");
        System.out.println("| 实现复杂度         | 低 (分片级偏移)            | 高 (分布式快照)      |");
        System.out.println("+--------------------+----------------------------+----------------------+\n");
    }


    // ==================== Q5: Source/Transform/Sink 协同 ====================

    /**
     * Q5: Seatunnel 架构中 Source/Transform/Sink 协同原理。
     *
     * <p>考察点：对整体架构和流水线执行模型的理解。
     */
    static void q05SourceTransformSinkSynergy() {
        System.out.println("---------------------------------------------------------------");
        System.out.println("Q5: Source / Transform / Sink 三者是如何协同工作的？");
        System.out.println("---------------------------------------------------------------\n");

        System.out.println("【标准答案】\n");

        System.out.println("1. 整体执行流程（流水线模型 Pipeline）");
        System.out.println("   +---------+   SeaTunnelRow   +-----------+   SeaTunnelRow   +---------+");
        System.out.println("   | Source  | ---------------> | Transform | ---------------> |  Sink   |");
        System.out.println("   | (Reader)|  (轮询拉取数据)   | (可选链路) |   (经过转换后)   | (Writer)|");
        System.out.println("   +---------+                  +-----------+                  +---------+");
        System.out.println("   |-- 按分片并行启动 -----------|-- 可多个串联 ------------|-- 写入目标端|");
        System.out.println("   |-- 维护 offset --------------|-- 可分支分流 -------------|-- 二阶段提交|");
        System.out.println();

        System.out.println("2. Source -> Transform 过程");
        System.out.println("   |-- Source 的 pollNext() 拉取数据，封装 SeaTunnelRow");
        System.out.println("   |-- SeaTunnelRow 包含: tableId + RowKind(INSERT/UPDATE/DELETE) + 字段数组");
        System.out.println("   |-- Transform 逐行处理: 类型转换 -> 字段映射 -> 过滤 -> 加密...");
        System.out.println("   |-- 中间数据在内存中传递，无磁盘落地（除非配置 Checkpoint）\n");

        System.out.println("3. Transform -> Sink 过程");
        System.out.println("   |-- Sink 按批次(Batch)聚合写入（batch_size 参数控制）");
        System.out.println("   |-- 支持二阶段提交(2PC)：先 prepare -> commit，保证 exactly-once");
        System.out.println("   |-- Sink 支持多路输出：同一条数据经过不同 Transform 写入不同 Sink");
        System.out.println("   |-- Sink 异常时，通过 Savepoint 机制实现断点续写\n");

        System.out.println("4. Seatunnel 的一次数据流转（端到端）");
        System.out.println("   Step 1: Driver 解析配置文件 conf -> 生成执行计划 LogicPlan");
        System.out.println("   Step 2: LogicPlan -> 切分为多个 Split（分片）");
        System.out.println("   Step 3: 为每个 Split 分配一个 Reader 实例");
        System.out.println("   Step 4: Reader 通过 JDBC/Kafka/CDC 拉取数据 -> Row");
        System.out.println("   Step 5: Row 经过 Transform 链 pipeline 处理");
        System.out.println("   Step 6: 最终 Row 通过 Sink Writer 写入目标端");
        System.out.println("   Step 7: 定期生成 Savepoint，记录各 Reader 消费进度");
        System.out.println("   Step 8: 全部 Split 消费完毕后，驱动端汇总统计信息\n");

        System.out.println("5. 关键性能优化点");
        System.out.println("   |-- 并行度设置：parallelism = CPU 核数 * 2 (IO 密集型)");
        System.out.println("   |-- 批次大小：batch_size 默认 10000，内存充足可调大");
        System.out.println("   |-- 数据倾斜：通过合理分片策略避免单 Reader 数据过多");
        System.out.println("   |-- 反压机制：Sink 写入慢时 Source 自动降低拉取速率");
        System.out.println("   |-- 零拷贝优化：Row 在 Transform 链中引用传递，避免数据拷贝\n");
    }
}