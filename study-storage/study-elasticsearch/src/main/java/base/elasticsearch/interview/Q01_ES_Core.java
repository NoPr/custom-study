package base.elasticsearch.interview;

/**
 * 面试5问：倒排索引 vs B+Tree, ES 为什么快, refresh/flush/translog, ES vs Solr vs Lucene, ES 集群脑裂。
 *
 * <p>ES 面试高频核心问题，涵盖数据存储原理、性能核心、写入可靠性、
 * 技术栈对比、集群运维痛点。每个问题包含标准答案和追问应对。</p>
 *
 * @author study-tuling
 */
public class Q01_ES_Core {

    // ======================== Q1: 倒排索引 vs B+Tree ========================

    /**
     * <h3>Q1: ES 的倒排索引和 MySQL 的 B+Tree 索引有什么区别？分别适用于什么场景？</h3>
     *
     * <pre>
     * 倒排索引(Inverted Index):
     *   - 结构: Term → Posting List(DocId列表)
     *   - 定位: 全文搜索, "文档中包含哪些词"
     *   - 场景: 搜索引擎、日志分析、商品搜索
     *   - 优势: 海量文本的任意关键词毫秒级检索
     *   - 劣势: 不擅长精确等值查询和范围排序
     *   - 核心: 分词 → 词典(FST压缩) → 倒排表(FOR/RBM压缩)
     *
     * B+Tree:
     *   - 结构: 多路平衡树, 数据在叶子, 叶子链表
     *   - 定位: 精确查找和范围扫描
     *   - 场景: 关系型数据库索引、OLTP
     *   - 优势: 等值/范围查询 O(logN), 支持排序
     *   - 劣势: 全文搜索需全表扫描, 不支持分词
     *   - 核心: 二分查找, 页分裂, 预读缓存
     *
     * 关键区别:
     *   倒排索引 = 词 → 文档 (反向映射)
     *   B+Tree   = 主键 → 行   (正向映射)
     *
     * 追问: "ES 为什么不用 B+Tree?"
     *   答: 全文搜索的关键是"通过词找文档", 不是"通过主键找行"。
     *   如果用 B+Tree, 每个词建一棵树 → 几百万棵树, 不可能。
     * </pre>
     */
    static void q1_InvertedIndex_vs_BPlusTree() {
        System.out.println("=" .repeat(60));
        System.out.println("Q1: 倒排索引 vs B+Tree");
        System.out.println("=".repeat(60));
        System.out.println("""
                
                倒排索引 (Inverted Index):
                  结构:  Term → Posting List(DocId列表)
                  定位:  全文搜索, "文档中包含哪些词"
                  场景:  搜索引擎、日志分析、商品搜索
                  优势:  海量文本任意关键词毫秒级检索
                  劣势:  不擅长精确等值查询
                  核心:  分词 → FST词典 → 倒排表(FOR/RBM压缩)
                                
                B+Tree:
                  结构:  多路平衡树, 数据在叶子, 叶子链表
                  定位:  精确查找和范围扫描
                  场景:  关系型数据库索引、OLTP
                  优势:  等值/范围查询 O(logN), 排序
                  劣势:  全文搜索需全表扫描
                  核心:  二分查找, 页分裂, 预读缓存
                                
                关键区别:
                  倒排索引 = 词 → 文档 (反向映射)
                  B+Tree   = 主键 → 行   (正向映射)
                                
                追问: "ES 为什么不用 B+Tree?"
                  答: 全文搜索关键是"通过词找文档"不是"通过主键找行"。
                  如果用 B+Tree 每个词建一棵树 → 几百万棵树, 不可能。
                """);
    }

    // ======================== Q2: ES 为什么快 ========================

    /**
     * <h3>Q2: ES 为什么查询这么快？</h3>
     *
     * <pre>
     * 快的原因是多层叠加, 不是单一技术:
     *
     * 1. 倒排索引 (核心)
     *    - Term → DocId 映射, 不需要扫描全表
     *    - 词典用 FST 压缩, 内存占用极小, 可全部加载到内存
     *
     * 2. 分词 + 相关性排序
     *    - 对查询词和文档做相同分词处理, 匹配更精准
     *    - TF-IDF / BM25 算法确保最相关的结果排前面
     *
     * 3. 分片并行计算
     *    - 索引被分成多个 Shard, 每个 Shard 独立搜索
     *    - 协调节点合并各 Shard 结果, 类似 MapReduce
     *
     * 4. 文件系统缓存 (OS Page Cache)
     *    - ES 大量依赖 OS 的文件系统缓存
     *    - 热数据常驻内存, 减少磁盘 IO
     *
     * 5. 数据结构优化
     *    - SkipList 加速 Posting List 求交
     *    - Bitset 做过滤条件缓存
     *    - DocValues 列式存储加速聚合/排序
     *
     * 6. 近实时搜索 (NRT)
     *    - refresh 间隔默认 1s, 写入后最快 1s 可被搜索到
     *    - Segment 机制, 新数据写入新 Segment, 不阻塞查询
     *
     * 总结: 倒排索引 + 内存级词典 + 分片并行 + OS Cache + 数据结构优化
     *       五个层面共同作用, 缺一不可
     * </pre>
     */
    static void q2_Why_ES_Fast() {
        System.out.println("=".repeat(60));
        System.out.println("Q2: ES 为什么查询这么快?");
        System.out.println("=".repeat(60));
        System.out.println("""
                
                快的原因是多层叠加, 不是单一技术:
                                
                1. 倒排索引 (核心)
                   Term → DocId 映射, 不需要扫描全表
                   词典 FST 压缩, 内存占用极小
                                
                2. 分词 + 相关性排序
                   对查询词和文档做相同分词处理
                   TF-IDF / BM25 确保最相关的结果排前面
                                
                3. 分片并行计算
                   索引被分成多个 Shard, 每个 Shard 独立搜索
                   协调节点合并各 Shard 结果, 类似 MapReduce
                                
                4. 文件系统缓存 (OS Page Cache)
                   ES 大量依赖 OS 的文件系统缓存
                   热数据常驻内存, 减少磁盘 IO
                                
                5. 数据结构优化
                   SkipList 加速 Posting List 求交
                   Bitset 做过滤条件缓存
                   DocValues 列式存储加速聚合/排序
                                
                6. 近实时搜索 (NRT)
                   refresh 默认 1s, 写入后最快 1s 可搜索
                   Segment 机制, 新 Segment 不阻塞查询
                                
                总结: 倒排索引 + 内存级词典 + 分片并行 + OS Cache
                      五个层面共同作用, 缺一不可
                """);
    }

    // ======================== Q3: refresh / flush / translog ========================

    /**
     * <h3>Q3: ES 的 refresh、flush、translog 分别是什么？写入流程是怎样的？</h3>
     *
     * <pre>
     * ES 写入流程 (一条数据从写入到可搜索):
     *
     * ┌──────────┐    ┌──────────┐    ┌───────────┐    ┌──────────┐
     * │  Memory  │ -> │ Segments │ -> │ OS Cache  │ -> │   Disk   │
     * │  Buffer  │    │ (不可变) │    │ (fsync)   │    │ (commit) │
     * └──────────┘    └──────────┘    └───────────┘    └──────────┘
     *
     * refresh (轻量级):
     *   - 将 Memory Buffer 中的数据生成新 Segment
     *   - 新 Segment 先写入 OS Cache, 此时可被搜索
     *   - 默认每 1s 执行一次(可调 refresh_interval)
     *   - 代价: 产生大量小 Segment, 需后台 merge
     *
     * translog (事务日志, 类似 MySQL redo log):
     *   - 每次写入先写 translog (顺序写, 快)
     *   - 如果节点宕机, 重启后从 translog 恢复未持久化的数据
     *   - 每个 Shard 一个 translog
     *
     * flush (重量级):
     *   - 执行 refresh → 执行 fsync 将 Segment 刷到磁盘
     *   - 清空 translog
     *   - 默认每 30 分钟或 translog 达到 512MB 时触发
     *
     * 完整写入流程:
     *   1. 数据写入 Memory Buffer + 同时写入 translog
     *   2. refresh: Memory Buffer → Segment(OS Cache) → 数据可搜索
     *   3. 持续写入, Segment 越来越多, translog 越来越大
     *   4. flush: Segments fsync 到磁盘 + translog 清空
     *
     * 追问: "写入后多久可搜索?"
     *   答: 默认 refresh_interval=1s, 所以最快 1s, 最慢 1s。
     *   可以调小到 100ms, 但会增加 Segment 数量和 IO 压力。
     *   写入 API 可加 ?refresh=wait_for 强制等待 refresh 完成。
     * </pre>
     */
    static void q3_Refresh_Flush_Translog() {
        System.out.println("=".repeat(60));
        System.out.println("Q3: refresh / flush / translog + 写入流程");
        System.out.println("=".repeat(60));
        System.out.println("""
                                
                ES 写入流程: Memory Buffer → Segment(OS Cache) → Disk
                                
                refresh (轻量级, 默认1s):
                  将 Memory Buffer 数据生成新 Segment 写入 OS Cache
                  此时数据可被搜索 (Near Real Time)
                                
                translog (事务日志, 类似 redo log):
                  每次写入先写 translog (顺序写, 快)
                  节点宕机 → 重启后从 translog 恢复未持久化数据
                                
                flush (重量级, 默认30min或512MB):
                  执行 refresh → fsync Segments 到磁盘 → 清空 translog
                                
                完整流程:
                  1. 数据 → Memory Buffer + translog
                  2. refresh → Segment(OS Cache) → 可搜索
                  3. flush  → fsync 到磁盘 + 清空 translog
                                
                追问: "写入后多久可搜索?"
                  答: refresh_interval 默认 1s, 最快 1s.
                  调小增加 Segment 数量, 可 API 加 ?refresh=wait_for
                """);
    }

    // ======================== Q4: ES vs Solr vs Lucene ========================

    /**
     * <h3>Q4: ES vs Solr vs Lucene 的关系和区别？</h3>
     *
     * <pre>
     * Lucene:
     *   - Apache 顶级项目, Java 全文搜索库
     *   - 提供倒排索引、分词、查询、评分等核心能力
     *   - 但是: 只是一个 Jar 包, 不是完整产品
     *   - 需要自己处理分布式、高可用、运维、API
     *
     * Elasticsearch:
     *   - 基于 Lucene 构建的分布式搜索引擎
     *   - 封装了 Lucene 的复杂性, 提供 RESTful API
     *   - 内置: 集群管理、分片、副本、故障转移、监控
     *   - 生态: ELK Stack(Logstash + Kibana), Beats
     *
     * Solr:
     *   - 同样是基于 Lucene 构建的搜索引擎
     *   - 比 ES 更早(2004 vs 2010)
     *   - 传统上对静态数据和复杂查询支持更好
     *   - SolrCloud 也支持分布式
     *
     * 对比:
     *   ┌──────────┬───────────┬──────────┬──────────┐
     *   │   特性   │  Lucene   │  Solr    │    ES    │
     *   ├──────────┼───────────┼──────────┼──────────┤
     *   │ 定位     │ 核心库    │ 搜索平台 │ 搜索平台 │
     *   │ 分布式   │ 无        │ SolrCloud│ 原生     │
     *   │ API      │ Java API  │ REST/XML │ REST JSON│
     *   │ 实时性   │ 无        │ 较弱     │ NRT      │
     *   │ 生态     │ 无        │ 一般     │ ELK Stack│
     *   │ 社区活跃 │ 稳定      │ 下降     │ 非常活跃 │
     *   └──────────┴───────────┴──────────┴──────────┘
     *
     * 追问: "为什么现在都用 ES?"
     *   答: ES 在分布式、实时性、生态、开发体验上全面领先 Solr。
     *   Solr 在一些传统企业(大数据量静态索引)场景仍有使用。
     * </pre>
     */
    static void q4_ES_vs_Solr_vs_Lucene() {
        System.out.println("=".repeat(60));
        System.out.println("Q4: ES vs Solr vs Lucene");
        System.out.println("=".repeat(60));
        System.out.println("""
                                
                Lucene:
                  Apache 顶级项目, Java 全文搜索库
                  提供倒排索引、分词、查询、评分等核心能力
                  只是一个 Jar 包, 不是完整产品
                                
                Elasticsearch:
                  基于 Lucene 构建的分布式搜索引擎
                  RESTful JSON API, 原生分布式
                  内置: 集群管理、分片、副本、故障转移
                  生态: ELK Stack(Logstash+Kibana+Beats)
                                
                Solr:
                  同样基于 Lucene 构建
                  比 ES 更早(2004 vs 2010)
                  SolrCloud 也支持分布式
                                
                对比:
                  Lucene: 核心库, 只有 Java API
                  Solr:   搜索平台, REST/XML, 分布式较弱
                  ES:     搜索平台, REST JSON, 原生分布式+生态
                                
                追问: "为什么现在都用 ES?"
                  答: ES 在分布式、实时性、生态、开发体验上全面领先 Solr.
                  Solr 在一些传统企业(静态索引)场景仍有使用.
                """);
    }

    // ======================== Q5: ES 集群脑裂 ========================

    /**
     * <h3>Q5: 什么是 ES 集群脑裂(Brain Split)？如何避免？</h3>
     *
     * <pre>
     * 脑裂(Split-Brain):
     *   集群中部分节点因网络故障无法通信, 各自选举出不同的 Master,
     *   导致集群分裂为多个独立的子集群, 各自写入产生数据不一致。
     *
     * 场景:
     *   3 节点集群: node-1(Master), node-2, node-3
     *   node-1 网络故障与其他节点断开:
     *     node-1 认为 node-2, node-3 挂了, 自己继续当 Master
     *     node-2 和 node-3 发现 node-1 失联, 选举 node-2 为 Master
     *     → 出现两个 Master, 各自接受写入 → 数据脑裂
     *   node-1 恢复后回到集群, 可能出现数据冲突
     *
     * 预防方案 (ES 7.x+):
     *
     * 1. discovery.seed_hosts: 集群候选节点列表
     *    - 配置所有可能成为 Master 的节点地址
     *
     * 2. cluster.initial_master_nodes: 初始化时的 Master 节点
     *    - 集群首次启动时指定, 防止脑裂
     *
     * 3. discovery.zen.minimum_master_nodes (ES 6.x 及以前):
     *    - 公式: (master_eligible_nodes / 2) + 1
     *    - 只有超过半数节点同意才能选举 Master
     *    - ES 7.x 之后自动处理, 不需要手动配置
     *
     * 4. 集群规模建议:
     *    - 至少 3 个 Master-eligible 节点 (奇数)
     *    - 推荐 3 或 5 个
     *
     * 追问: "为什么必须是奇数?"
     *   答: 偶数无法打破平局。4 个节点需要至少 3 个同意才能选主,
     *   挂 2 个后无法选举。5 个节点允许挂 2 个, 更稳定。
     * </pre>
     */
    static void q5_Brain_Split() {
        System.out.println("=".repeat(60));
        System.out.println("Q5: ES 集群脑裂(Brain Split)");
        System.out.println("=".repeat(60));
        System.out.println("""
                                
                脑裂 (Split-Brain):
                  部分节点网络故障无法通信, 各自选举不同 Master
                  导致多个子集群独立写入, 数据不一致
                                
                场景 (3节点: node-1[Master], node-2, node-3):
                  1. node-1 网络故障断开
                  2. node-1 自认为还是 Master
                  3. node-2+node-3 选举 node-2 为新 Master
                  4. 两个 Master 各自接受写入 → 数据冲突
                                
                预防方案 (ES 7.x+):
                  1. discovery.seed_hosts: 候选节点列表
                  2. cluster.initial_master_nodes: 初始化Master
                  3. minimum_master_nodes (ES 6.x): (eligible/2)+1
                     ES 7.x 已内置 Raft-like 算法, 自动处理
                  4. 建议: 3 或 5 个 Master-eligible 节点 (奇数)
                                
                追问: "为什么必须是奇数?"
                  答: 偶数无法打破平局。4节点需要至少3个同意选主,
                  挂2个后无法选举。5节点允许挂2个, 更稳定。
                """);
    }

    // ======================== 汇总 ========================

    static void printSummary() {
        System.out.println("=".repeat(60));
        System.out.println("ES 面试 5 问速记");
        System.out.println("=".repeat(60));
        System.out.println("""
                
                Q1 倒排索引 vs B+Tree
                   -> 倒排 = 词→文档 (全文搜索), B+Tree = 主键→行 (精确查询)
                                
                Q2 ES 为什么快
                   -> 倒排索引 + 内存FST + 分片并行 + OS Cache + SkipList/Bitset
                                
                Q3 refresh/flush/translog
                   -> refresh(1s, 可搜索) → flush(30min/512MB, 持久化)
                   -> translog = redo log, 宕机恢复
                                
                Q4 ES vs Solr vs Lucene
                   -> Lucene=核心库, Solr=老牌平台, ES=现代分布式 + ELK生态
                                
                Q5 脑裂
                   -> 多Master同时存在 → minimum_master_nodes
                   -> 奇数节点(3/5), ES 7.x 内置 Raft-like
                """);
    }

    static void printMindMap() {
        System.out.println("=".repeat(60));
        System.out.println("ES 知识体系思维导图");
        System.out.println("=".repeat(60));
        System.out.println("""
                
                ES 核心知识
                ├── 基础原理
                │   ├── 倒排索引: Term → PostingList
                │   │   ├── FST 词典(前缀压缩)
                │   │   ├── FOR/RBM 压缩
                │   │   └── SkipList 加速求交
                │   ├── 分词器: Standard/IK/拼音
                │   └── 相关性评分: TF-IDF → BM25
                │
                ├── 集群架构
                │   ├── Node: Master / Data / Coordinating / Ingest
                │   ├── Shard: Primary + Replica
                │   ├── 路由: hash(routing) % shardCount
                │   └── 写入: coordinating → primary → replica
                │
                ├── 查询体系
                │   ├── Query DSL: term/match/range/bool
                │   ├── 搜索: Query-Then-Fetch 两阶段
                │   ├── 聚合: Bucket + Metrics + Pipeline
                │   └── 高亮 / suggest / percolate
                │
                ├── 写入流程
                │   ├── Memory Buffer → refresh(1s) → Segment
                │   ├── translog → flush(30min/512MB) → Disk
                │   └── Segment Merge (后台合并)
                │
                └── 运维
                    ├── 脑裂: minimum_master_nodes
                    ├── 容量规划: shard=10~50GB, node≤20 shards
                    ├── 监控: _cat + _cluster/health
                    └── 调优: 堆内存≤32GB, 关闭swap
                """);
    }

    public static void main(String[] args) {
        q1_InvertedIndex_vs_BPlusTree();
        q2_Why_ES_Fast();
        q3_Refresh_Flush_Translog();
        q4_ES_vs_Solr_vs_Lucene();
        q5_Brain_Split();
        printSummary();
        printMindMap();
    }
}