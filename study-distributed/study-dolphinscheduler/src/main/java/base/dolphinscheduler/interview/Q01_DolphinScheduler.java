package base.dolphinscheduler.interview;

/**
 * 面试：DolphinScheduler DAG 执行流程 + 任务容错机制 + 任务分片 vs 定时调度 +
 * DolphinScheduler vs Azkaban vs Airflow 对比。
 *
 * <p>本类为面试自测工具，覆盖 DolphinScheduler 高频面试题：
 * <ul>
 *   <li><b>DAG 执行流程 5 步</b>：提交 -> 解析 -> 拓扑排序 -> 调度分发 -> 状态回调</li>
 *   <li><b>任务容错 4 层</b>：重试 / 超时 Kill / 手动恢复 / Worker 心跳故障转移</li>
 *   <li><b>任务分片 vs 定时调度</b>：批量并行 vs 按时间触发</li>
 *   <li><b>DolphinScheduler vs Azkaban vs Airflow</b>：架构/容错/扩展/易用性</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，逐题输出解答。
 *
 * @author study-tuling
 */
public class Q01_DolphinScheduler {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("        DolphinScheduler 面试高频问题自测");
        System.out.println("=".repeat(60));

        question1_dagFlow();
        question2_faultTolerance();
        question3_shardingVsSchedule();
        question4_vsAzkabanVsAirflow();
        question5_rowKeyDesign();
    }

    /* ==================== 第 1 题：DAG 执行流程 5 步 ==================== */

    static void question1_dagFlow() {
        System.out.println("\n【Q1】DolphinScheduler 的 DAG 执行流程是怎样的？（5 步）");

        System.out.println(
            "DAG 执行流程分为 5 个核心步骤：\n" +
            "\n" +
            "1. **工作流提交 (Workflow Submit)**\n" +
            "   - UI 拖拽定义 DAG, 序列化为 JSON\n" +
            "   - API Server 做合法性校验(无环、节点有效、参数合法)\n" +
            "   - 创建 ProcessInstance, ZooKeeper 分布式锁防止重复提交\n" +
            "\n" +
            "2. **DAG 解析 (DAG Parse)**\n" +
            "   - Master 反序列化 JSON -> 内存 DAG 图\n" +
            "   - 提取 TaskDefinitions、依赖边、优先级、超时配置\n" +
            "   - 构建邻接表(入度表 + 后继表)\n" +
            "\n" +
            "3. **拓扑排序 + 分层 (Topological Sort & Layering)**\n" +
            "   - Kahn 算法 (BFS + 入度表), O(V+E) 复杂度\n" +
            "   - 分层输出: 同一层(入度同时为 0)的任务无依赖\n" +
            "   - 同一层可并行 -> 第一层 3 个节点 -> 3 个 Worker 并行执行\n" +
            "\n" +
            "4. **调度分发 (Dispatch to Workers)**\n" +
            "   - Master 通过 Netty RPC 分发 TaskInstance\n" +
            "   - Worker 策略: 轮询(Round-Robin)、最少负载(Least-Load)\n" +
            "   - Worker -> TaskProcessor -> 执行 Shell/SQL/Python 等任务类型\n" +
            "   - 分片模式: 一个逻辑任务 -> N 个物理 TaskInstance -> N 个 Worker\n" +
            "\n" +
            "5. **状态回调 + 触发下游 (Status Callback & Trigger)**\n" +
            "   - SUCCESS: 遍历后继节点, 入度-1; 入度=0 的节点加入就绪队列\n" +
            "   - FAILED: 根据 FailureStrategy 决定 CONTINUE/END/PENDING\n" +
            "   - 重试: 在 Master 端更新 retryTimes, 延迟后重新入就绪队列");
    }

    /* ==================== 第 2 题：任务容错 4 层 ==================== */

    static void question2_faultTolerance() {
        System.out.println("\n【Q2】DolphinScheduler 的容错机制有哪些？（4 层）");

        System.out.println(
            "DolphinScheduler 任务容错分为 4 层：\n" +
            "\n" +
            "**第 1 层：任务级重试 (Task-Level Retry)**\n" +
            "- 配置: retryTimes(重试次数) + retryInterval(重试间隔)\n" +
            "- 指数退避: 1s -> 2s -> 4s -> 8s -> ...\n" +
            "- 重试次数耗尽后标记 FAILED, 等待手动恢复\n" +
            "- 适用: 网络波动、临时资源不足等瞬时故障\n" +
            "\n" +
            "**第 2 层：超时告警与 Kill (Timeout Alert & Kill)**\n" +
            "- 配置: timeout(超时时长, 分钟级) + timeoutStrategy(WARN/FAILED)\n" +
            "- FAILED: 超时自动 Kill 任务、释放 Worker 线程、标记 FAILED\n" +
            "- WARN: 仅告警不中断, 让任务继续执行\n" +
            "- 实现: Master 端 ScheduledExecutorService 定时检查 runningTasks\n" +
            "\n" +
            "**第 3 层：手动恢复 (Manual Recovery)**\n" +
            "- FAILED 任务不会自动重新调度\n" +
            "- 管理员通过 UI 点击「恢复执行」-> Master 重新调度该 TaskInstance\n" +
            "- 支持从失败节点开始恢复 (跳过已成功的上游节点)\n" +
            "- 实现: processInstance.restore() -> re-submit failed TaskInstances\n" +
            "\n" +
            "**第 4 层：Worker 心跳 + 故障转移 (Worker Heartbeat & Failover)**\n" +
            "- Worker 每隔 10s 向 ZooKeeper 上报心跳 (ephemeral node)\n" +
            "- Master 监控 /nodes/worker/* 节点变化 (Watcher)\n" +
            "- Worker 宕机 -> ZK ephemeral 节点过期 -> Master 感知\n" +
            "- 容错-故障转移:\n" +
            "  1. 将该 Worker 上的 RUNNING TaskInstances 标记为 NEED_FAULT_TOLERANCE\n" +
            "  2. Master 重新将这些任务分配到其他健康 Worker\n" +
            "  3. 通过「任务实例杀死接口」确保旧 Worker 上的任务不会重复执行\n" +
            "- Master 自身也通过 ZK 实现 HA (Active-Standby):\n" +
            "  - Active Master 持有分布式锁\n" +
            "  - Active 宕机 -> Standby 抢锁 -> 升级为 Active -> 接管所有调度\n" +
            "\n" +
            "各层覆盖的故障类型：\n" +
            "| 容错层        | 覆盖故障类型              | 恢复时间   |\n" +
            "|---------------|--------------------------|-----------|\n" +
            "| 任务重试      | 瞬时故障(网络闪断/OOM)    | 秒级      |\n" +
            "| 超时Kill      | 死循环/死锁/资源耗尽      | 分钟级     |\n" +
            "| 手动恢复      | 需人工介入(数据异常)      | 人工决定   |\n" +
            "| Worker容错    | Worker节点宕机/网络分区   | 10s+      |");
    }

    /* ==================== 第 3 题：任务分片 vs 定时调度 ==================== */

    static void question3_shardingVsSchedule() {
        System.out.println("\n【Q3】任务分片 vs 定时调度有什么区别？");

        System.out.println(
            "| 维度           | 任务分片 (Sharding)                      | 定时调度 (Cron Schedule)            |\n" +
            "|----------------|------------------------------------------|-------------------------------------|\n" +
            "| **触发方式**   | 手动触发 / 上游完成触发 (事件驱动)        | Cron 表达式触发 (时间驱动)            |\n" +
            "| **并行度**     | 分片数 N = 并行实例数 (每个分片一个Task)   | 每次调度创建 1 个 ProcessInstance    |\n" +
            "| **数据分区**   | 按分片字段取模 (e.g., user_id % N)        | 不分片, 处理全量数据                  |\n" +
            "| **适用场景**   | 海量数据并行处理 (10 亿级/day)            | 周期性批量任务 (T+1 日终清理)         |\n" +
            "| **失败处理**   | 单个分片失败可单独重试, 不影响其他分片     | 整个实例失败需全部重跑 (可能产生脏数据)|\n" +
            "| **资源消耗**   | N 个 Worker 同时工作, 峰值负载大           | 1 个 Worker 执行, 资源消耗平缓        |\n" +
            "| **数据倾斜**   | 分片键选择不当可能导致部分分片数据量大     | 不存在倾斜问题                       |\n" +
            "\n" +
            "**任务分片典型举例**:\n" +
            "- 场景: 每天 50 亿条日志清洗 (单 Worker 需要 8 小时)\n" +
            "- 方案: 分片数=20, 每个分片仅处理日志 hash % 20\n" +
            "- SQL: WHERE MOD(CRC32(user_id), 20) = #{shardIndex}\n" +
            "- 效果: 20 个 Worker 并行, 耗时降至 30 分钟\n" +
            "\n" +
            "**Cron 定时调度典型举例**:\n" +
            "- Cron: \"0 2 * * * ?\" (每天凌晨 2:00)\n" +
            "- 场景: T+1 日报生成、过期数据清理、例行备份\n" +
            "- 特点: 固定时间点触发, 处理全量数据, 不依赖外部事件");
    }

    /* ==================== 第 4 题：DolphinScheduler vs Azkaban vs Airflow ==================== */

    static void question4_vsAzkabanVsAirflow() {
        System.out.println("\n【Q4】DolphinScheduler vs Azkaban vs Airflow 对比？");

        System.out.println(
            "| 维度           | DolphinScheduler | Azkaban           | Airflow            |\n" +
            "|----------------|-------------------|--------------------|---------------------|\n" +
            "| **架构**       | Master-Worker+ZK  | Solo/Two-Server    | Scheduler+Worker    |\n" +
            "| **HA**         | Master/Worker双活 | 无(依赖外部DB)     | 元数据库作协调        |\n" +
            "| **DAG定义**    | 可视化拖拽+JSON   | .job 文件 zip 包   | Python DAG 定义     |\n" +
            "| **调度方式**   | Cron + 依赖触发   | Cron 定时          | Cron + Sensor      |\n" +
            "| **任务类型**   | 开箱即用 20+ 类型 | Shell/Java/Command | 需Operator插件       |\n" +
            "| **容错机制**   | 4层(重试/超时Kill/手动恢复/Worker容错) | 任务级重试 + 人工恢复 | 重试 + DAG恢复     |\n" +
            "| **分片能力**   | 内置分片(并行实例化) | 不支持              | 不支持               |\n" +
            "| **条件分支**   | 支持(Success/Failure/And/Or) | Flow 定义分支      | Python 条件判断      |\n" +
            "| **分布式**     | 去中心化(无单点)    | 单 Master(少节点)   | 依赖Celery/RabbitMQ |\n" +
            "| **资源管理**   | WorkerGroup 隔离   | Properties 配置    | Pool Slot 限制       |\n" +
            "| **学习成本**   | 低(Web UI 全配)    | 低(.job文件)       | 高(需Python编程)     |\n" +
            "| **二次开发**   | Java+Maven(标准)   | Java(老旧)         | Python(灵活但非标)   |\n" +
            "| **适用规模**   | 数万级/天          | 数千级/天          | 数十万级/天(若配Celery)|\n" +
            "\n" +
            "选型建议:\n" +
            "- **DolphinScheduler**: 国产优先, 大厂支撑(易观开源, Apache 顶级), UI 友好 + 分片能力 + 多租户\n" +
            "- **Azkaban**: 传统 Hadoop 生态(LinkedIn 开源), 轻量级, .job 定义简单, 适合中小规模\n" +
            "- **Airflow**: Python 生态强劲, DAG 定义灵活(CI/CD 友好), Sensor 丰富, 适合数据工程团队");
    }

    /* ==================== 第 5 题：RowKey 设计 5 原则 ==================== */

    static void question5_rowKeyDesign() {
        System.out.println("\n【Q5】RowKey 设计原则 + 二级索引 + Compaction + MemStore 刷写条件");

        System.out.println(
            "**RowKey 设计 5 原则**:\n" +
            "1. **长度原则**: RowKey 越短越好 (减少存储开销, 提升比较效率)\n" +
            "   - 建议 < 100 字节, 极端场景 < 64KB (HBase 限制)\n" +
            "2. **散列原则**: 将时间戳/递增序列打散, 避免单 Region 热点\n" +
            "   - 散列法: MD5(suffix).substring(0,4) + 反转\n" +
            "   - 加盐法: Random(N) + 原始 RowKey (牺牲区间查询, 需 N 次 Scan)\n" +
            "   - 反转法: 手机号 13812345678 -> 87654321831\n" +
            "3. **唯一原则**: RowKey 在表中必须唯一 (HBase 的 key-value 主键)\n" +
            "4. **业务含义(前缀分组)**: 将高频查询的维度放在 RowKey 前缀\n" +
            "   - e.g., {user_id}_{timestamp} -> 方便按 user_id 前缀 Scan 查询\n" +
            "5. **避免热点**: 避免单调递增或递减序列 (时间戳反转/随机前缀)\n" +
            "\n" +
            "**二级索引方案对比**:\n" +
            "- 全局索引(Global Index): 索引表独立存储 -> 先查索引表获取 RowKey -> 再查主表 (两次查询)\n" +
            "- 本地索引(Local Index): 索引与主表数据共存 -> Scan 全表, 效率低, 无回表\n" +
            "- 协处理器(Coprocessor): Observer 钩子 -> 写主表时同步更新索引表, 强一致\n" +
            "   (Phoenix 实现方案)\n" +
            "\n" +
            "**Compaction 触发时机**:\n" +
            "- MemStore Flush 后 HFile 数量达到 compactionRatio 阈值\n" +
            "- Minor Compaction: 合并部分小的相邻 HFile (多路归并, 删除过期版本)\n" +
            "- Major Compaction: 合并 Store 下所有 HFile, 清理 deleted/ttl 数据\n" +
            "   (低峰期手动触发 major_compact, 默认 7 天自动执行 1 次)\n" +
            "\n" +
            "**MemStore 刷写条件 (4 种)**:\n" +
            "1. MemStore 大小 >= hbase.hregion.memstore.flush.size (128MB)\n" +
            "2. RegionServer 全局 MemStore 大小 >= heap * 0.4 -> 阻塞写, 强制刷写\n" +
            "3. WAL(HLog) 数量 > maxLogs -> 触发刷写以减少 WAL (早版本)\n" +
            "4. 手动触发 flush 命令");
    }
}