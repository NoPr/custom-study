package base.mongodb.interview;

import java.util.*;

/**
 * MongoDB 高频面试题: 建模设计 + 选型对比 + 副本集 + 一致性
 *
 * Q1: 嵌入 (Embedded) vs 引用 (Reference) 场景选择?
 * Q2: ObjectId vs UUID 如何选择?
 * Q3: MongoDB vs MySQL 选型决策?
 * Q4: 副本集选举过程?
 * Q5: MongoDB 的 3 种一致性级别?
 */
public class Q01_MongoDB_Design {

    public static void main(String[] args) {
        System.out.println("========== MongoDB 高频面试题 ==========\n");

        q1_embeddedVsReference();
        q2_objectIdVsUuid();
        q3_mongodbVsMysql();
        q4_replicaElection();
        q5_consistencyLevels();

        System.out.println("\n========== 面试题解答完毕 ==========");
    }

    // ==================== Q1: 嵌入 vs 引用 ====================

    /**
     * 嵌入 (Embedded): 子文档直接嵌套在父文档中, 一次查询全量获取
     *   适用: 一对一/一对少量 (如用户-地址, 订单-订单项)
     *   优势: 单次 IO 读取, 原子更新 (单个文档操作是原子的)
     *   劣势: 文档膨胀 16MB 限制, 子文档无法独立查询
     *
     * 引用 (Reference): 仅存关联 ID, 通过 $lookup 或二次查询获取
     *   适用: 多对多/一对大量 (如文章-评论, 商品-分类)
     *   优势: 数据独立, 易更新, 不突破文档大小限制
     *   劣势: 需要多次 IO 或 JOIN 操作
     */
    static void q1_embeddedVsReference() {
        System.out.println("【Q1】嵌入 vs 引用: 如何选择?\n");

        System.out.println("┌─────────────────┬──────────────────────┬──────────────────────────┐");
        System.out.println("│ 维度            │ 嵌入 (Embedded)       │ 引用 (Reference)          │");
        System.out.println("├─────────────────┼──────────────────────┼──────────────────────────┤");
        System.out.println("│ 数据关系        │ 一对一/一对少         │ 一对多/多对多             │");
        System.out.println("│ 读取方式        │ 1 次查询              │ 多次查询 / $lookup       │");
        System.out.println("│ 写入原子性      │ 单文档原子更新        │ 多文档需事务              │");
        System.out.println("│ 文档大小        │ 受 16MB 限制          │ 无限制 (独立文档)         │");
        System.out.println("│ 独立查询        │ 不支持                │ 支持                     │");
        System.out.println("│ 数据冗余        │ 冗余存储              │ 规范化存储               │");
        System.out.println("│ 典型场景        │ 订单-订单项           │ 文章-评论(海量)          │");
        System.out.println("│                 │ 用户-地址             │ 商品-分类(多对多)        │");
        System.out.println("└─────────────────┴──────────────────────┴──────────────────────────┘");

        System.out.println("\n金句: 一起读的放一起 (嵌入), 经常变的独立存 (引用)");
        System.out.println("     不要为了规范化而规范化, MongoDB 的设计哲学是数据随查询而聚合\n");
    }

    // ==================== Q2: ObjectId vs UUID ====================

    /**
     * ObjectId (12 字节):
     *   结构: 4B 时间戳 + 5B 随机值 + 3B 自增计数器
     *   优势: 天然按时间排序 (时间戳在前), 12 字节比 UUID 小得多, 客户端生成
     *   劣势: 可能暴露创建时间, 不适合需要完全无序的场景
     *
     * UUID (36 字符/16 字节):
     *   优势: 全局唯一标准化, 无信息泄露
     *   劣势: 随机性导致 B-Tree 索引页分裂 (碎片化), 存储/传输开销大
     *
     * ULID: UUID + 时间排序 = 26 字符, 取两者所长
     */
    static void q2_objectIdVsUuid() {
        System.out.println("【Q2】ObjectId vs UUID 如何选择?\n");

        System.out.println("┌───────────────┬────────────────────┬──────────────────────────┐");
        System.out.println("│ 维度          │ ObjectId           │ UUID                     │");
        System.out.println("├───────────────┼────────────────────┼──────────────────────────┤");
        System.out.println("│ 大小          │ 12 字节            │ 36 字符 (字符串)         │");
        System.out.println("│ 排序          │ 天然按时间排序     │ 随机, 索引页分裂严重     │");
        System.out.println("│ 生成位置      │ 客户端 (Driver)    │ 应用层 / 数据库          │");
        System.out.println("│ 信息泄露      │ 含创建时间戳       │ 无信息泄露               │");
        System.out.println("│ 索引友好      │ 高 (顺序写入)      │ 低 (随机写入致页分裂)    │");
        System.out.println("│ 适用场景      │ 默认 _id, 日志     │ 对外暴露 ID 的场景       │");
        System.out.println("└───────────────┴────────────────────┴──────────────────────────┘");

        System.out.println("\n最佳实践:");
        System.out.println("  _id 默认用 ObjectId (索引友好 + 省空间)");
        System.out.println("  对外 API 用 UUID 或 ULID (不暴露时间戳, 防遍历)");
        System.out.println("  内部关联用 ObjectId, 外部展示用 UUID (双 ID 策略)\n");
    }

    // ==================== Q3: MongoDB vs MySQL ====================

    /**
     * MongoDB vs MySQL 选型对比
     */
    static void q3_mongodbVsMysql() {
        System.out.println("【Q3】MongoDB vs MySQL: 如何选型?\n");

        System.out.println("┌───────────────────┬──────────────────────────┬──────────────────────────┐");
        System.out.println("│ 维度              │ MongoDB                  │ MySQL                    │");
        System.out.println("├───────────────────┼──────────────────────────┼──────────────────────────┤");
        System.out.println("│ 数据模型          │ 文档 (JSON/BSON)         │ 关系表 (行+列)           │");
        System.out.println("│ Schema            │ 灵活, 无 schema 约束     │ 固定,DDL 定义            │");
        System.out.println("│ 关联查询          │ $lookup (不建议高频使用) │ JOIN (强大且优化成熟)    │");
        System.out.println("│ 事务              │ 4.0+ 多文档事务 (分片)   │ ACID 天然完备             │");
        System.out.println("│ 扩展方式          │ 水平分片 (内置Sharding)  │ 分库分表 (中间件)         │");
        System.out.println("│ 写入性能          │ 高 (默认异步刷盘)        │ 中等 (保证持久化)         │");
        System.out.println("│ 复杂查询          │ 聚合管道 (功能型)        │ SQL (声明式, 优化器成熟)  │");
        System.out.println("│ 数据一致性        │ 最终一致 (可调)          │ 强一致 (默认)             │");
        System.out.println("│ 运维复杂度        │ 副本集/分片运维较复杂    │ 成熟, 文档生态好          │");
        System.out.println("└───────────────────┴──────────────────────────┴──────────────────────────┘");

        System.out.println("\n选型决策树:");
        System.out.println("  MongoDB 优先: schema 多变, 文档嵌套, 高写入吞吐, 快速迭代, 水平扩展");
        System.out.println("  MySQL 优先: 强事务, 复杂 JOIN, 报表分析, 金融/账务, 生态成熟");
        System.out.println("  混合架构: 核心交易 MySQL + 日志/埋点 MongoDB (多语言持久化)\n");
    }

    // ==================== Q4: 副本集选举 ====================

    /**
     * 副本集选举过程 (基于 Raft 变体):
     * 1. 心跳检测: 各节点每 2s 发送心跳, 10s 内无响应的节点标记为不可达
     * 2. 触发选举: Secondary 发现与 Primary 心跳超时, 发起选举
     * 3. 投票: 存活节点投票, 获得多数票 (N/2+1) 的节点当选
     * 4. 优先级: priority 高的节点优先当选 (0 表示永不参选)
     * 5. 新 Primary: 写入 oplog 新 term, 通知所有 Secondary 新 Primary 地址
     */
    static void q4_replicaElection() {
        System.out.println("【Q4】副本集选举过程详解\n");

        System.out.println("选举流程 (P=Primary, S=Secondary):");
        System.out.println("  ┌──────┐    heartbeat /2s   ┌──────┐");
        System.out.println("  │  P   │◄─────────────────►│  S1  │");
        System.out.println("  └──┬───┘                    └──┬───┘");
        System.out.println("     │         heartbeat         │");
        System.out.println("     └──────────────────────────►│");
        System.out.println("                            ┌────┴───┐");
        System.out.println("                            │   S2   │");
        System.out.println("                            └────────┘");

        System.out.println("\n选举步骤:");
        System.out.println("  1) P 宕机, S1 和 S2 在 10s 内未收到 P 心跳 -> 判定 P 不可达");
        System.out.println("  2) S1 发起选举: term++, 投票给自己, 请求 S2 投票");
        System.out.println("  3) S2 收到请求: term 匹配且未投票 -> 投票给 S1");
        System.out.println("  4) S1 获得 2/3 票 (多数派) -> 当选新 Primary");
        System.out.println("  5) 新 Primary 写入 oplog, 通知各节点, 客户端重连");

        System.out.println("\n关键参数:");
        System.out.println("  priority: 0-1000, 越大越容易当选 (hidden/delayed 节点设 0 不参选)");
        System.out.println("  heartbeatIntervalMillis: 心跳间隔, 默认 2000ms");
        System.out.println("  electionTimeoutMillis: 选举超时, 默认 10000ms (10s)");
        System.out.println("  成员要求: 最少 3 个节点 (含 arbiter 投票节点), 必须奇数个");

        System.out.println("\n常见配置:");
        System.out.println("  标准: P + S + S (3 节点)   -- 容忍 1 节点故障");
        System.out.println("  高可用: P + S + S + S + S (5 节点) -- 容忍 2 节点故障");
        System.out.println("  异地: P + S + S(hidden) + arbiter (节省资源的投票节点)\n");
    }

    // ==================== Q5: 3 种一致性级别 ====================

    /**
     * MongoDB 一致性级别:
     *
     * writeConcern (写关注):
     *   w: 1  -- Primary 确认即返回 (默认, 可能丢失)
     *   w: majority  -- 多数节点确认才返回 (推荐, 数据安全)
     *   w: <N>  -- 指定 N 个节点确认
     *   j: true  -- 写入 journal (WAL) 后才返回 (最安全, 最慢)
     *
     * readConcern (读关注):
     *   local -- 读 Primary 最新数据 (默认, 可能读到未多数确认的)
     *   majority -- 只读已多数节点确认的数据 (不会回滚, 推荐)
     *   linearizable -- 线性一致性, 读时检查 Primary 仍是 Primary (最严格)
     *
     * readPreference (读偏好):
     *   primary -- 只读 Primary (默认, 强一致)
     *   secondary -- 只读 Secondary (可能滞后, 分担读压力)
     *   nearest -- 读网络延迟最低的节点
     */
    static void q5_consistencyLevels() {
        System.out.println("【Q5】MongoDB 3 种一致性级别\n");

        // --- writeConcern ---
        System.out.println("一、writeConcern (写关注): 控制写操作何时返回");
        System.out.println("┌──────────────────────┬───────────────────────────────────────────┐");
        System.out.println("│ 配置                 │ 含义                                      │");
        System.out.println("├──────────────────────┼───────────────────────────────────────────┤");
        System.out.println("│ {w: 0}               │ 不等待确认, 最快但数据可能丢失            │");
        System.out.println("│ {w: 1}               │ Primary 确认即返回 (默认)                 │");
        System.out.println("│ {w: \"majority\"}      │ 多数节点确认才返回 (推荐, 防回滚)        │");
        System.out.println("│ {w: \"majority\", j:1} │ 多数节点 journal 持久化 (最安全, 最慢)   │");
        System.out.println("└──────────────────────┴───────────────────────────────────────────┘");

        System.out.println("\n二、readConcern (读关注): 控制读到什么版本的数据");
        System.out.println("┌──────────────────────┬───────────────────────────────────────────┐");
        System.out.println("│ 配置                 │ 含义                                      │");
        System.out.println("├──────────────────────┼───────────────────────────────────────────┤");
        System.out.println("│ \"local\"              │ 读节点最新数据 (默认, 可能读到未提交)     │");
        System.out.println("│ \"majority\"           │ 只读已多数确认的数据 (安全, 不会回滚)    │");
        System.out.println("│ \"linearizable\"       │ 线性一致: 读时验证 Primary 仍是 Primary  │");
        System.out.println("│ \"available\"          │ 读分片中任意数据 (可能脏读, 最宽松)      │");
        System.out.println("└──────────────────────┴───────────────────────────────────────────┘");

        System.out.println("\n三、readPreference (读偏好): 控制从哪个节点读");
        System.out.println("┌──────────────────────┬───────────────────────────────────────────┐");
        System.out.println("│ 配置                 │ 含义                                      │");
        System.out.println("├──────────────────────┼───────────────────────────────────────────┤");
        System.out.println("│ \"primary\"            │ 只读 Primary (默认, 强一致)               │");
        System.out.println("│ \"secondary\"          │ 只读 Secondary (分担读压力, 可能滞后)    │");
        System.out.println("│ \"nearest\"            │ 读延迟最低节点 (可能读到 stale 数据)     │");
        System.out.println("│ \"primaryPreferred\"   │ 优先 Primary, 不可用时读 Secondary        │");
        System.out.println("└──────────────────────┴───────────────────────────────────────────┘");

        System.out.println("\n组合建议:");
        System.out.println("  金融/交易: writeConcern=majority + readConcern=majority + readPreference=primary");
        System.out.println("  日志/埋点: writeConcern=1 + readPreference=secondary (高吞吐, 容忍少量不一致)");
        System.out.println("  通用业务: writeConcern=majority + readPreference=primary (平衡性能与安全)\n");
    }
}