package base.hbase.interview;

import java.util.*;

/**
 * 面试题: HBase 核心设计原理全景解析。
 * 涵盖 RowKey 设计 5 原则、二级索引方案选型、Compaction 机制、MemStore 刷写触发条件、
 * HBase vs MySQL vs Elasticsearch 对比。
 *
 * <p>这些是 HBase 面试最高频的考察点, 面试官期望候选人能讲清楚设计原理和适用场景。
 */
public class Q01_HBase_Design {

    public static void main(String[] args) {
        System.out.println("========== 面试题: HBase 核心设计问答 ==========\n");

        rowkeyDesignPrinciples();
        secondaryIndexComparison();
        compactionDeepDive();
        memstoreFlushTriggers();
        hbaseVsMysqlVsEs();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /* ===================== 1. RowKey 设计 5 原则 ===================== */

    /**
     * RowKey 设计的五大原则 (面试必考):
     *
     * <p>1. 长度原则: RowKey 不宜过长 (建议 50~100 字节), 因为 HBase 的每个 KeyValue 都存储 RowKey,
     *    RowKey 越长, 存储开销和 IO 开销越大。但也别太短, 太短影响可读性和区分度。
     *
     * <p>2. 散列原则: RowKey 应该均匀分布到各个 Region, 避免热点写入。
     *    方法: MD5/SHA 散列、加盐 (salt)、反转等。
     *
     * <p>3. 唯一原则: RowKey 在表中必须唯一, 同时作为主键。相同 RowKey 的 Put 是覆盖写。
     *
     * <p>4. 排序原则: HBase 按 RowKey 字典序存储, 将高频查询条件放在 RowKey 高位可以高效范围 Scan。
     *    例如: 查询"某用户最近的订单" -> RowKey = userId + (Long.MAX_VALUE - timestamp)
     *
     * <p>5. 避免单调递增: 时间戳等单调递增的 RowKey 会导致所有新写入集中在最后一个 Region (热点),
     *    应使用反转时间戳或加盐来打散。
     */
    static void rowkeyDesignPrinciples() {
        System.out.println("--- Q1. RowKey 设计的 5 大原则 ---");
        System.out.println();

        String[][] principles = {
                {"长度原则", "50~100 字节为宜",
                 "每个 KeyValue 都携带 RowKey, 过长则存储/IO 开销大; 过短则可读性差"},
                {"散列原则", "均匀分布到各 Region",
                 "使用 MD5/SHA 散列、加盐 (salt)、反转等方式打散, 避免热点 Region"},
                {"唯一原则", "RowKey 即主键",
                 "同一 RowKey 的 Put 是覆盖写 (upsert); 设计时确保业务唯一性"},
                {"排序原则", "字典序存储",
                 "高频查询条件放 RowKey 高位, 实现高效范围 Scan; 如 userId+timestamp"},
                {"防单调递增", "避免裸时间戳",
                 "时间戳反转 / Long.MAX_VALUE-timestamp / 加盐, 防止写入 Region 热点"},
        };

        System.out.println("  | 原则        | 方法                  | 原因                                    |");
        System.out.println("  |-------------|-----------------------|-----------------------------------------|");
        for (String[] p : principles) {
            System.out.printf("  | %-12s | %-22s | %-40s |%n", p[0], p[1], p[2]);
        }
        System.out.println();

        /* 实际案例: RowKey 设计 = 散列前缀(4位) + userId(固定宽度) + 反转时间戳 */
        System.out.println("  实战案例: RowKey = hash(userId)[:4] + userId + (Long.MAX_VALUE - ts)");
        System.out.println("    示例: user_10001 在 t=1700000000 的订单:");
        String rowKey = RowKeyBuilder.build("user_10001", 1700000000L);
        System.out.printf("      RowKey = %s%n", rowKey);
        System.out.println("    含义: 散列\"a1b2\"打散写入 + userId定位 + 反时间戳使新数据靠前\n");
    }

    /** RowKey 构造器示例 */
    static class RowKeyBuilder {
        static String build(String userId, long timestamp) {
            /* 散列: 取 userId 哈希前4位 */
            int hash = Math.abs(userId.hashCode());
            String hashPrefix = String.format("%04x", hash % 0xFFFF).substring(0, 4);
            /* 反转时间戳: 新数据字典序靠前 */
            String reversedTs = String.format("%020d", Long.MAX_VALUE - timestamp);
            return hashPrefix + "_" + userId + "_" + reversedTs;
        }
    }

    /* ===================== 2. 二级索引方案对比 ===================== */

    /**
     * 二级索引三种方案对比:
     *
     * <p>全局索引: 索引表独立于数据表。查询先查索引表获取 RowKey, 再回数据表。
     *    优点: 索引独立, 查询效率高 (不需要扫描所有 Region)
     *    缺点: 需要二次查询, 写入需保证双表一致性 (分布式事务或最终一致)
     *
     * <p>本地索引: 索引与数据在同一 Region, 同一行事务写入。
     *    优点: 写入原子性, 不需要分布式事务
     *    缺点: 查询需要扫描所有 Region, 数据量大时性能差
     *
     * <p>协处理器 (Phoenix 方案): 注册 RegionObserver, 写入时自动同步索引。
     *    优点: 自动化, 读写平衡
     *    缺点: 依赖协处理器框架, 维护成本高
     */
    static void secondaryIndexComparison() {
        System.out.println("--- Q2. 二级索引方案对比 ---");
        System.out.println();

        System.out.println("  | 方案       | 写入一致性 | 查询效率    | 适用场景           |");
        System.out.println("  |------------|-----------|------------|--------------------|");
        System.out.println("  | 全局索引   | 需分布式事务 | 高 (O(1)+1) | 读多写少           |");
        System.out.println("  | 本地索引   | 单行原子    | 中 (O(N))   | 写多读少, 数据量小 |");
        System.out.println("  | 协处理器   | 最终一致    | 高 (自动)   | Phoenix 生产级方案 |");
        System.out.println();

        System.out.println("  追问: Phoenix 索引原理?");
        System.out.println("    - 建索引时: 自动创建索引表 + 注册 Indexer RegionObserver 到数据表");
        System.out.println("    - 写入时: postPut 钩子截获, 同步写入索引表");
        System.out.println("    - 查询时: QueryOptimizer 分析 SQL, 命中索引, 生成对应 Scan");
        System.out.println("    - 全局索引 (GLOBAL): 索引表独立 -> 读快写慢");
        System.out.println("    - 本地索引 (LOCAL): 索引与数据同 Region -> 写快读慢");
        System.out.println("    - 覆盖索引 (COVERED): 索引含全部查询列 -> 无需回表\n");
    }

    /* ===================== 3. Compaction 深入 ===================== */

    /**
     * Compaction 机制深入解析:
     *
     * <p>为什么需要 Compaction?
     * LSM-Tree 写入模型下, MemStore Flush 不断生成 HFile,
     * HFile 数量增多导致读放大 (需要扫描多个文件)。
     * Compaction 将多个 HFile 合并, 减少文件数, 加速读。
     *
     * <p>Minor Compaction:
     * - 触发: 当 Store 中 HFile 数量超过阈值 (hbase.hstore.compaction.min, 默认 3)
     * - 策略: 选取相邻的少量小文件合并, 不清理过期和删除标记
     * - 频率: 高频 (后台持续进行)
     *
     * <p>Major Compaction:
     * - 触发: 定时 (hbase.hregion.majorcompaction, 默认 7 天) 或手动触发
     * - 策略: 将 Store 中所有 HFile 合并为一个
     * - 效果: 清理 TTL 过期数据和删除标记, 回收磁盘空间
     * - 风险: IO 和 CPU 开销大, 建议业务低峰期执行
     */
    static void compactionDeepDive() {
        System.out.println("--- Q3. Compaction 机制深入 ---");
        System.out.println();

        System.out.println("  为什么需要 Compaction?");
        System.out.println("    LSM-Tree 写入产生大量 HFile -> 读放大 (多文件扫描) -> Compaction 合并减少文件数");
        System.out.println();

        System.out.println("  Minor vs Major:");
        System.out.println("    Minor Compaction:");
        System.out.println("      触发: hstore.compaction.min (默认3个HFile)");
        System.out.println("      动作: 选取相邻小文件合并");
        System.out.println("      清理: 不清理过期数据, 不清理删除标记");
        System.out.println("      频率: 高频 (后台自动)");
        System.out.println("    Major Compaction:");
        System.out.println("      触发: 定时 (默认7天) 或手动 major_compact");
        System.out.println("      动作: 所有 HFile 合并为一个");
        System.out.println("      清理: 清理 TTL 过期数据 + 删除标记 (tombstone)");
        System.out.println("      风险: IO/CPU 开销大, 建议低峰期执行");
        System.out.println();

        System.out.println("  追问: Compaction 的 IO 优化?");
        System.out.println("    - Compaction Throttling: 限速, 避免影响在线读写");
        System.out.println("    - Stripe Compaction (HBase 2.0+): 按 RowKey 范围分组合并, 减少单次开销");
        System.out.println("    - Date Tiered Compaction: 按时序分层合并, 适合时间序列数据");
        System.out.println("    - MOB Compaction: 大对象 (MOB) 独立 Compaction, 不影响正常数据\n");
    }

    /* ===================== 4. MemStore 刷写触发条件 ===================== */

    /**
     * MemStore Flush 的触发条件 (面试高频):
     *
     * <p>1. Region 级别: 单个 MemStore 大小达到 hbase.hregion.memstore.flush.size (默认 128MB)
     *    时触发该 Region 的 MemStore Flush。
     *
     * <p>2. RegionServer 级别: 所有 MemStore 总大小达到
     *    hbase.regionserver.global.memstore.size (默认 JVM 堆的 40%) 时,
     *    按 MemStore 大小降序触发 Flush, 直到低于 lowerLimit (95%)。
     *
     * <p>3. WAL 数量: 当 WAL (HLog) 文件数超过 hbase.regionserver.maxlogs (默认 32),
     *    触发最早 MemStore Flush, 以便清理旧的 WAL 文件。
     *
     * <p>4. 手动触发: 通过 HBase Shell 命令 flush 'table_name' / flush 'region_name'
     *
     * <p>5. Region 关闭: Region 关闭前 (如 Split) 强制 Flush MemStore。
     */
    static void memstoreFlushTriggers() {
        System.out.println("--- Q4. MemStore 刷写触发条件 ---");
        System.out.println();

        System.out.println("  5 种 MemStore Flush 触发条件:");
        System.out.println();

        String[][] triggers = {
                {"Region 级", "MemStore >= 128MB (flush.size)",
                 "单个 Region 的某个 Store 的 MemStore 达到阈值"},
                {"RS 全局", "全局 MemStore >= 堆 * 40% (lowerLimit)",
                 "RegionServer 全局 MemStore 内存压力, 大到小依次 Flush"},
                {"WAL 上限", "WAL 文件数 > maxlogs (默认32)",
                 "Flush 最早的 MemStore, 以便清理 WAL 文件, 释放 HDFS 空间"},
                {"手动触发", "flush 'table_name'",
                 "管理员通过 Shell 或 API 手动执行"},
                {"Region 关闭", "Split / Balance 前",
                 "Region 下线前必须 Flush 数据, 保证 HFile 完整"},
        };

        System.out.println("  | 触发级别   | 条件                                | 说明                     |");
        System.out.println("  |-----------|-------------------------------------|--------------------------|");
        for (String[] t : triggers) {
            System.out.printf("  | %-10s | %-36s | %-25s |%n", t[0], t[1], t[2]);
        }
        System.out.println();

        System.out.println("  追问: MemStore 太大有什么影响?");
        System.out.println("    - 写阻塞: 全局 MemStore 满 -> 阻塞写入, 直到 Flush 释放空间");
        System.out.println("    - GC 压力: MemStore 在堆内, 过大导致 Full GC 频繁");
        System.out.println("    - Flush 风暴: 大量 MemStore 同时触发 Flush -> 瞬时 IO 压力");
        System.out.println();

        System.out.println("  追问: MemStore Flush 和 Compaction 的区别?");
        System.out.println("    MemStore Flush: 内存 -> 磁盘 (生成 HFile), 写入路径");
        System.out.println("    Compaction:    磁盘 -> 磁盘 (合并 HFile), 维护路径");
        System.out.println("    两者配合: Flush 产生 HFile, Compaction 合并 HFile\n");
    }

    /* ===================== 5. HBase vs MySQL vs ES ===================== */

    /**
     * HBase vs MySQL vs Elasticsearch 对比:
     *
     * <p>HBase: 分布式列式存储, 基于 HDFS。适合海量数据随机读写、稀疏矩阵、时序数据。
     *    优势: 水平扩展能力强 (加节点即可), 写吞吐高, 支持 TB/PB 级数据。
     *    劣势: 不支持 SQL (需要 Phoenix), 不支持事务, 不支持复杂查询 (无二级索引原生支持)。
     *
     * <p>MySQL (InnoDB): 关系型数据库, B+Tree 索引。适合 OLTP 事务、强一致性、复杂查询。
     *    优势: SQL 标准, 事务 ACID, 生态成熟。
     *    劣势: 垂直扩展有限, 单表数据量瓶颈 (通常 2000w 行), 分库分表复杂。
     *
     * <p>Elasticsearch: 分布式搜索引擎, 倒排索引。适合全文搜索、日志分析、聚合分析。
     *    优势: 全文检索能力, 近实时 (1s 延迟), 聚合分析强, Kibana 可视化。
     *    劣势: 不支持事务, 写延迟高于 HBase, 字段更新成本高 (重建索引)。
     */
    static void hbaseVsMysqlVsEs() {
        System.out.println("--- Q5. HBase vs MySQL vs Elasticsearch ---");
        System.out.println();

        System.out.println("  | 特性       | HBase           | MySQL (InnoDB) | Elasticsearch     |");
        System.out.println("  |------------|-----------------|----------------|-------------------|");
        System.out.println("  | 数据模型   | 列族 + 列        | 行 + 列         | 文档 (JSON)       |");
        System.out.println("  | 存储引擎   | LSM-Tree        | B+Tree          | 倒排索引           |");
        System.out.println("  | 扩展性     | 水平扩展 (强)    | 垂直扩展 (为主) | 水平扩展 (强)      |");
        System.out.println("  | 查询能力   | RowKey Scan     | SQL (全部)      | 全文搜索 + 聚合    |");
        System.out.println("  | 事务       | 单行原子         | ACID            | 不支持             |");
        System.out.println("  | 写性能     | 极高 (顺序写)    | 中 (随机写)     | 中 (近实时 1s)     |");
        System.out.println("  | 读性能     | 高 (单 RowKey)   | 高 (索引)       | 高 (倒排索引)      |");
        System.out.println("  | 数据量级   | TB~PB           | GB~TB           | TB~PB              |");
        System.out.println("  | 适用场景   | 海量写入/时序    | OLTP 业务系统   | 全文搜索/日志分析  |");
        System.out.println();

        /* 典型场景选型 */
        System.out.println("  典型场景选型:");
        System.out.println("    1. 电商订单表 (强事务, 复杂查询) -> MySQL");
        System.out.println("    2. 用户行为日志 (海量写入, 简单查询) -> HBase / ClickHouse");
        System.out.println("    3. 商品搜索 (全文检索, 聚合) -> Elasticsearch");
        System.out.println("    4. 时序监控指标 (高吞吐写入, 时间范围查询) -> HBase / TDengine");
        System.out.println();
        System.out.println("  混合架构 (常见):");
        System.out.println("    MySQL (在线业务) + HBase (历史归档/日志) + ES (搜索) + Redis (缓存)");
        System.out.println("    数据同步: 通过 Canal/Binlog 从 MySQL 同步到 ES/HBase/Kafka\n");
    }
}