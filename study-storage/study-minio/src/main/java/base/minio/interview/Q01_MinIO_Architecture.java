package base.minio.interview;

import java.util.*;

/**
 * 面试题: MinIO 架构核心 -- ER vs 多副本计算、MinIO 为何轻量(Go写/单文件/无ZB)、
 * S3 最终一致性原因、Multipart Upload 原理、MinIO vs Ceph vs HDFS
 *
 * <p>五大核心面试题解答, 配合手写模拟演示.
 *
 * @author study-tuling
 */
public class Q01_MinIO_Architecture {

    public static void main(String[] args) {
        System.out.println("========== 面试题: MinIO 架构核心 ==========\n");

        q1_erasureVsReplica();
        q2_whyMinioLightweight();
        q3_s3EventualConsistency();
        q4_multipartUploadPrinciple();
        q5_minioVsCephVsHdfs();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /**
     * Q1: 纠删码(EC) vs 多副本 存储开销计算
     *
     * <p>给定 1TB 原始数据, 分别计算:
     * <ul>
     *   <li>3副本: 1TB × 3 = 3TB, 开销 300%, 容错 2 副本</li>
     *   <li>RS(8+4): 3TB × (8+4)/8 = 1.5TB, 开销 150%, 容错 4 块</li>
     *   <li>RS(12+4): 1TB × (12+4)/12 = 1.33TB, 开销 133%, 容错 4 块</li>
     * </ul>
     *
     * <p>结论: 相同容错能力下, EC 存储开销远小于多副本, 但需要 CPU 计算.
     * MinIO 默认 RS(8+4) 或 RS(12+4), 使用 Intel ISA-L 汇编加速库.
     */
    static void q1_erasureVsReplica() {
        System.out.println("--- Q1: EC vs 多副本 存储开销计算 ---");

        final double RAW = 1024.0; // 1TB = 1024GB

        System.out.println("| 方案          | 原始数据 | 存储占用 | 开销比 | 容错数 | CPU开销 |");
        System.out.println("|--------------|---------|---------|--------|--------|---------|");

        double replica3 = RAW * 3;
        System.out.printf("| 3副本         | %.0f GB  | %.0f GB  | %.0f%%   | 2      | 无      |%n",
                RAW, replica3, 300.0);

        double ec84 = RAW * (8 + 4) / 8.0;
        System.out.printf("| RS(8+4)       | %.0f GB  | %.0f GB  | %.0f%%   | 4      | 低      |%n",
                RAW, ec84, ec84 / RAW * 100);

        double ec124 = RAW * (12 + 4) / 12.0;
        System.out.printf("| RS(12+4)      | %.0f GB  | %.0f GB  | %.0f%%   | 4      | 低      |%n",
                RAW, ec124, ec124 / RAW * 100);

        System.out.println("\n  计算公式: 存储空间 = 原始大小 × (k+m)/k");
        System.out.println("  MinIO 默认: 每对象 EC 编码, 12 个数据块 + 4 个校验块 = 16 个驱动器");
        System.out.println("  性能: Intel ISA-L 汇编加速, 单核可达 10GB/s 编码速度\n");
    }

    /**
     * Q2: MinIO 为何轻量?
     *
     * <p>核心原因:
     * <ol>
     *   <li><b>Go 语言编写:</b> 编译为单一原生二进制, 无 JVM/解释器依赖, 启动 <1s</li>
     *   <li><b>单文件部署:</b> minio 二进制仅 ~100MB, 无外部数据库 (元数据存储在对象本身)</li>
     *   <li><b>无 ZAB/Raft:</b> 不像 ZooKeeper/Kafka 需要共识协议, 各节点独立运行</li>
     *   <li><b>直接磁盘 I/O:</b> 绕过文件系统缓存 (O_DIRECT), 减少内存拷贝</li>
     *   <li><b>无 SPOF:</b> 无 NameNode/元数据节点, 元数据与数据共存于磁盘</li>
     * </ol>
     *
     * <p>对比: HDFS 需要 NameNode + DataNode + ZooKeeper + JournalNode, 组件繁多.
     */
    static void q2_whyMinioLightweight() {
        System.out.println("--- Q2: MinIO 为何轻量? ---");

        System.out.println("| 维度         | MinIO                    | HDFS                     |");
        System.out.println("|--------------|--------------------------|--------------------------|");
        System.out.println("| 语言         | Go (编译为单一二进制)    | Java (依赖 JVM)           |");
        System.out.println("| 部署         | 单文件 ~100MB            | 多组件 + 配置              |");
        System.out.println("| 启动时间     | <1s                      | 30s~数分钟               |");
        System.out.println("| 元数据管理   | 与数据共存磁盘 (无外部DB)| NameNode 内存 (有瓶颈)     |");
        System.out.println("| 共识协议     | 无 (无中心节点)          | ZK + JournalNode         |");
        System.out.println("| 内存占用     | 低 (无 JVM 堆)           | NameNode 需大堆 (GB级)    |");
        System.out.println("| SPOF         | 无                       | NameNode (HA需额外组件)   |");

        System.out.println("\n  关键: MinIO = 简洁到极致, 一招一式皆直击对象存储核心.\n");
    }

    /**
     * Q3: S3 最终一致性的原因
     *
     * <p>S3 采用最终一致性模型 (非强一致性):
     * <ul>
     *   <li><b>分布式写入:</b> 数据先写入多个存储节点, 各节点间同步存在延迟</li>
     *   <li><b>无事务协调:</b> 不像 RDBMS, S3 无分布式事务保证, 追求高可用与分区容错 (AP)</li>
     *   <li><b>Overwrite 行为:</b> 覆盖写时, GET 可能短暂读到旧版本数据</li>
     * </ul>
     *
     * <p><b>2020 年后变化:</b> AWS S3 已对所有 PUT/GET 提供强一致性 (read-after-write),
     * 但对 List 操作仍可能最终一致. MinIO 默认强一致性 (单节点或复制集内).
     *
     * <p><b>为什么之前是最终一致性?</b>
     * CAP 定理: 网络分区(P)发生时, 必须在一致性(C)和可用性(A)之间取舍.
     * S3 选择 AP, 保证全球可用, 牺牲短暂的一致性窗口.
     */
    static void q3_s3EventualConsistency() {
        System.out.println("--- Q3: S3 最终一致性原因 ---");

        System.out.println("  一致性模型对比:");
        System.out.println("| 模型         | 描述                              | 典型系统       |");
        System.out.println("|--------------|-----------------------------------|----------------|");
        System.out.println("| 强一致性     | 写入后立即对所有读可见            | RDBMS, ZooKeeper|");
        System.out.println("| 最终一致性   | 写入后最终会对所有读可见(有窗口)  | S3(旧), DNS    |");
        System.out.println("| Read-after-Write| PUT 新对象后 GET 立即可见      | S3(2020后)     |");
        System.out.println("| 单调读一致性 | 不会读到比之前更旧的版本          | DynamoDB       |");

        System.out.println("\n  S3 为何最终一致 (CAP角度):");
        System.out.println("    C (Consistency) 强一致性: 牺牲");
        System.out.println("    A (Availability) 可用性: 保证 (全球多区域, 任意节点可读写)");
        System.out.println("    P (Partition Tolerance) 分区容错: 保证 (跨区域网络延迟)");

        System.out.println("\n  2020 AWS 更新: S3 对所有 PUT/GET 默认强一致性");
        System.out.println("  原因: 内部 Paxos-like 协议 + 原子性元数据更新, 透明地实现了强一致\n");
    }

    /**
     * Q4: Multipart Upload 原理
     *
     * <p>将大文件拆分为多个分片并行上传, 提高吞吐 + 断点续传.
     *
     * <p><b>三步骤:</b>
     * <ol>
     *   <li><b>Initiate:</b> 客户端请求创建 UploadId, 服务端返回唯一标识</li>
     *   <li><b>UploadPart:</b> 客户端将文件按 partSize(≥5MB) 切分, 逐片上传.
     *       每片独立存储, 返回 Part ETag (MD5)</li>
     *   <li><b>Complete:</b> 客户端提交 PartNumber + ETag 列表,
     *       服务端按序合并并计算最终 ETag: hex(MD5(MD5(p1)+...+MD5(pn)))-n</li>
     * </ol>
     *
     * <p><b>优势:</b>
     * <ul>
     *   <li>并行上传: 多片同时传输, 充分利用带宽</li>
     *   <li>断点续传: 某片失败只需重传该片, 无需重传整个文件</li>
     *   <li>未知大小: 可在不知道总大小的情况下开始上传</li>
     *   <li>单文件上限: 5TB (10,000 片 × 5MB~5GB)</li>
     * </ul>
     */
    static void q4_multipartUploadPrinciple() {
        System.out.println("--- Q4: Multipart Upload 原理 ---");

        System.out.println("  三步骤流程:");

        System.out.println("  1. Initiate (POST /bucket/key?uploads)");
        System.out.println("     → 返回 UploadId: abc123-def456");

        System.out.println("  2. UploadPart (PUT /bucket/key?partNumber=1&uploadId=abc123)");
        System.out.println("     → Body: [5MB binary data]");
        System.out.println("     → 返回 ETag: \"a1b2c3d4...\"");
        System.out.println("     → 重复 N 次, 可并行");

        System.out.println("  3. Complete (POST /bucket/key?uploadId=abc123)");
        System.out.println("     → Body: <CompleteMultipartUpload>");
        System.out.println("               <Part><PartNumber>1</PartNumber><ETag>a1b2...</ETag></Part>");
        System.out.println("               <Part><PartNumber>2</PartNumber><ETag>e5f6...</ETag></Part>");
        System.out.println("             </CompleteMultipartUpload>");
        System.out.println("     → 服务端合并并返回最终 ETag");

        System.out.println("\n  关键参数:");
        System.out.println("    最小分片: 5MB (除最后一片)");
        System.out.println("    最大分片: 5GB");
        System.out.println("    最多分片: 10,000");
        System.out.println("    单对象上限: 10,000 × 5GB = 50TB (实际限制 5TB)");

        System.out.println("\n  最终 ETag 计算:");
        System.out.println("    ETag = hex(MD5(MD5(part1) || MD5(part2) || ... || MD5(partN))) + \"-\" + N");
        System.out.println("  示例: \"8cf16...a7b-3\" 表示 3 片合并\n");
    }

    /**
     * Q5: MinIO vs Ceph vs HDFS
     *
     * <p>三大分布式存储系统全面对比:
     *
     * <p><b>MinIO:</b> 轻量级对象存储, Go 编写, S3 兼容 API, EC 保护, 适合云原生/私有云
     * <br><b>Ceph:</b> 统一存储 (对象+块+文件), RADOS 底层, CRUSH 算法分布, 适合大型数据中心
     * <br><b>HDFS:</b> Hadoop 生态文件系统, 块存储, NameNode 元数据管理, 适合大数据分析
     */
    static void q5_minioVsCephVsHdfs() {
        System.out.println("--- Q5: MinIO vs Ceph vs HDFS ---");

        System.out.println("| 维度           | MinIO                      | Ceph                       | HDFS                       |");
        System.out.println("|----------------|----------------------------|----------------------------|----------------------------|");
        System.out.println("| 类型           | 对象存储 (S3)              | 统一存储 (对象/块/文件)     | 分布式文件系统             |");
        System.out.println("| 语言           | Go                         | C++                        | Java                       |");
        System.out.println("| 元数据         | 分布式 (与数据共存)        | MON + OSD                  | NameNode (单点/HA)         |");
        System.out.println("| 数据保护       | EC (Reed-Solomon)          | EC + 多副本                | 多副本 (默认3)             |");
        System.out.println("| 最小节点       | 1 (单机模式)               | 3 (MON + OSD)              | 2 (NN + DN)                |");
        System.out.println("| 部署复杂度     | 低 (单二进制)              | 高 (多组件)                | 中 (需 Hadoop 生态)        |");
        System.out.println("| 性能           | 高 (直接 I/O, 无抽象层)    | 中高 (分层设计)            | 高 (顺序读写)              |");
        System.out.println("| 运维难度       | 低                         | 高                         | 中                         |");
        System.out.println("| 适用场景       | 云原生, K8s, AI/ML         | 大型私有云, OpenStack      | 大数据 (Spark/Hive)        |");
        System.out.println("| API            | S3 兼容                    | S3 + RBD + CephFS          | HDFS API + NFS Gateway     |");
        System.out.println("| 社区/维护方    | MinIO Inc.                 | Red Hat / Linux Foundation | Apache Foundation          |");

        System.out.println("\n  选型建议:");
        System.out.println("    - 简单对象存储 + K8s → MinIO (部署一条命令)");
        System.out.println("    - 大型私有云 + 块/文件/对象 → Ceph (生态完整但运维重)");
        System.out.println("    - Hadoop 大数据 → HDFS (原生集成, 批处理友好)");
        System.out.println("    - 小团队/个人 → MinIO (成本最低, 上手最快)\n");
    }
}