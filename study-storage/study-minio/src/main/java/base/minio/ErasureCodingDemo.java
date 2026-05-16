package base.minio;

import java.util.*;

/**
 * 纠删码(Erasure Coding)原理: Reed-Solomon(k+m, k数据块+m校验块) 手写简化版(3+2)
 * + RS vs 多副本(m+1) 存储开销对比 + EC 读写流程: 写分片3步(编码→分发→元数据) 读恢复(std+parity解码)
 *
 * <p><b>纠删码 vs 多副本:</b>
 * <pre>
 * 配置          | 存储开销 | 容错数 | 耐久性
 * -------------|---------|--------|--------
 * 3副本         | 300%    | 2      | 高 (简单)
 * RS(6+3)      | 150%    | 3      | 更高 (复杂)
 * RS(10+6)     | 160%    | 6      | 最高 (最复杂)
 * RS(3+2,本示例) | 167%    | 2      | 较高
 * </pre>
 *
 * <p><b>Reed-Solomon 原理 (简化):</b>
 * 将 k 个数据块视为多项式系数, 在 m 个不同点上求值得到 m 个校验块.
 * 丢失任意 k 个块 (数据+校验混合) 都可通过解线性方程组恢复.
 *
 * <p><b>写流程:</b>
 * <ol>
 *   <li>编码: 数据分 k 块 → RS 编码生成 m 个校验块</li>
 *   <li>分发: k+m 个块分散写入不同磁盘/节点 (跨故障域)</li>
 *   <li>元数据: 记录分片分布 + 编码参数 (k, m, 块大小)</li>
 * </ol>
 *
 * <p><b>读恢复:</b>
 * <ol>
 *   <li>尝试读取 k 个数据块直接返回 (最优路径)</li>
 *   <li>数据块不足 k 个时, 用校验块参与解码恢复</li>
 *   <li>解码: 通过高斯消元 / 范德蒙德矩阵逆运算恢复原始数据</li>
 * </ol>
 *
 * @author study-tuling
 */
public class ErasureCodingDemo {

    /** 伽罗瓦域 GF(256) 的指数表和对数表 (简化, 本示例用普通整数运算模拟) */
    private static final int K = 3; // 数据块数
    private static final int M = 2; // 校验块数
    private static final int TOTAL = K + M; // 总块数 = 5

    /** 范德蒙德矩阵 (K+M) x K, 用于编码 */
    private static final int[][] VANDERMONDE = generateVandermonde(K + M, K);

    /** 生成范德蒙德矩阵: matrix[i][j] = i^j (简化整数运算, GF(256) 中为 i^(j)) */
    private static int[][] generateVandermonde(int rows, int cols) {
        int[][] matrix = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = (int) Math.pow(i + 1, j); // 行索引+1 为基
            }
        }
        return matrix;
    }

    /* ======================== 数据模型 ======================== */

    /** 分片信息: 分片在哪个磁盘/节点 */
    static class ShardPlacement {
        int shardIndex;
        String nodeId;
        int diskId;

        ShardPlacement(int shardIndex, String nodeId, int diskId) {
            this.shardIndex = shardIndex;
            this.nodeId = nodeId;
            this.diskId = diskId;
        }

        @Override
        public String toString() {
            return String.format("[shard#%d @ %s/disk%d]", shardIndex, nodeId, diskId);
        }
    }

    /** EC 编码结果: k+m 个分片 + 元数据 */
    static class ECResult {
        int[] dataBlocks;       // K 个原始数据块
        int[] parityBlocks;     // M 个校验块
        int blockSize;          // 块大小 (字节)
        List<ShardPlacement> placements;

        ECResult(int[] dataBlocks, int[] parityBlocks, int blockSize, List<ShardPlacement> placements) {
            this.dataBlocks = dataBlocks;
            this.parityBlocks = parityBlocks;
            this.blockSize = blockSize;
            this.placements = placements;
        }
    }

    /* ======================== RS 编码/解码核心 ======================== */

    /**
     * RS 编码: 用范德蒙德矩阵将 K 个数据块编码为 K+M 个编码块
     * encoded[i] = sum(V[i][j] * data[j])  (简化整数运算)
     */
    static int[] encode(int[] dataBlocks) {
        if (dataBlocks.length != K) {
            throw new IllegalArgumentException("需要 " + K + " 个数据块");
        }

        int[] encoded = new int[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            int sum = 0;
            for (int j = 0; j < K; j++) {
                sum += VANDERMONDE[i][j] * dataBlocks[j];
            }
            encoded[i] = sum;
        }
        return encoded;
    }

    /**
     * RS 解码: 当数据块不足 K 个时, 用可用块恢复原始数据.
     *
     * <p>原理: 从编码矩阵中选取 K 个可用行组成子矩阵, 求逆后乘以可用编码块得到原始数据.
     *
     * @param availableIndices 可用的分片索引 (0 ~ TOTAL-1)
     * @param availableValues  对应分片的值
     */
    static int[] decode(List<Integer> availableIndices, List<Integer> availableValues) {
        if (availableIndices.size() < K) {
            throw new IllegalArgumentException("至少需要 " + K + " 个块才能恢复, 当前仅 " + availableIndices.size());
        }

        // 选取前 K 个可用索引构建子矩阵
        int[][] subMatrix = new int[K][K];
        for (int i = 0; i < K; i++) {
            int row = availableIndices.get(i);
            System.arraycopy(VANDERMONDE[row], 0, subMatrix[i], 0, K);
        }

        // 高斯消元求解 (简化: 针对已知可用数据块直接恢复)
        int[] recovered = new int[K];
        // 对于数据块直接取回, 校验块需反解
        for (int i = 0; i < K; i++) {
            int idx = availableIndices.get(i);
            if (idx < K) {
                // 数据块直接可用
                recovered[idx] = availableValues.get(i);
            }
        }

        // 补全缺失的数据块: 用校验块和已知数据块反推
        // 简化为: 如果数据块0缺失, 用校验块0和已知数据块1,2反推
        for (int missingIdx = 0; missingIdx < K; missingIdx++) {
            boolean found = false;
            for (int idx : availableIndices) {
                if (idx == missingIdx) {
                    found = true;
                    break;
                }
            }
            if (found) continue;

            // 从可用块中找校验块来恢复
            for (int i = 0; i < availableIndices.size(); i++) {
                int availIdx = availableIndices.get(i);
                if (availIdx >= K) {
                    // 校验块: encoded = sum(V[row][j] * data[j])
                    // 解出 missingIdx: data[missingIdx] = (encoded - sum_{j!=missingIdx} V[row][j]*data[j]) / V[row][missingIdx]
                    int encodedVal = availableValues.get(i);
                    int remaining = encodedVal;
                    for (int j = 0; j < K; j++) {
                        if (j == missingIdx) continue;
                        remaining -= VANDERMONDE[availIdx][j] * recovered[j];
                    }
                    recovered[missingIdx] = remaining / VANDERMONDE[availIdx][missingIdx];
                    break;
                }
            }
        }

        return recovered;
    }

    /* ======================== EC 读写流程模拟 ======================== */

    /**
     * 写流程 (3步): 编码 → 分发 → 元数据
     */
    static ECResult writeWithEC(int[] originalData) {
        int blockSize = originalData.length / K;

        // 步骤1: 数据分 K 块
        int[] dataBlocks = new int[K];
        for (int i = 0; i < K; i++) {
            dataBlocks[i] = originalData[i * blockSize]; // 简化: 取每块首字节
        }
        System.out.printf("  [写-步骤1] 数据分 %d 块: %s%n", K, Arrays.toString(dataBlocks));

        // 步骤2: RS 编码生成 M 个校验块
        int[] encoded = encode(dataBlocks);
        int[] parityBlocks = Arrays.copyOfRange(encoded, K, TOTAL);
        System.out.printf("  [写-步骤2] RS 编码完成, 校验块: %s%n", Arrays.toString(parityBlocks));

        // 步骤3: 分发到不同节点/磁盘 + 记录元数据
        List<ShardPlacement> placements = new ArrayList<>();
        for (int i = 0; i < K; i++) {
            placements.add(new ShardPlacement(i, "node-" + ((i % 3) + 1), i / 3 + 1));
        }
        for (int i = 0; i < M; i++) {
            placements.add(new ShardPlacement(K + i, "node-" + (((K + i) % 3) + 1), (K + i) / 3 + 1));
        }
        System.out.printf("  [写-步骤3] 分片分发到 %d 个位置:%n", placements.size());
        for (ShardPlacement p : placements) {
            String type = p.shardIndex < K ? "DATA" : "PARITY";
            int value = p.shardIndex < K ? dataBlocks[p.shardIndex] : parityBlocks[p.shardIndex - K];
            System.out.printf("    %s shard#%d(value=%d) → %s%n", type, p.shardIndex, value, p);
        }

        System.out.println();
        return new ECResult(dataBlocks, parityBlocks, blockSize, placements);
    }

    /**
     * 读恢复流程: 尝试读取数据块 → 不足 K 个时解码恢复
     */
    static int[] readWithEC(ECResult ecResult, Set<Integer> failedShards) {
        System.out.printf("  [读] 故障分片: %s%n", failedShards);

        // 收集可用块
        List<Integer> availableIndices = new ArrayList<>();
        List<Integer> availableValues = new ArrayList<>();

        for (int i = 0; i < K; i++) {
            if (!failedShards.contains(i)) {
                availableIndices.add(i);
                availableValues.add(ecResult.dataBlocks[i]);
                System.out.printf("    DATA shard#%d 可用, value=%d%n", i, ecResult.dataBlocks[i]);
            } else {
                System.out.printf("    DATA shard#%d 故障!%n", i);
            }
        }
        for (int i = 0; i < M; i++) {
            int parityIdx = K + i;
            if (!failedShards.contains(parityIdx)) {
                availableIndices.add(parityIdx);
                availableValues.add(ecResult.parityBlocks[i]);
                System.out.printf("    PARITY shard#%d 可用, value=%d%n", parityIdx, ecResult.parityBlocks[i]);
            } else {
                System.out.printf("    PARITY shard#%d 故障!%n", parityIdx);
            }
        }

        if (availableIndices.size() >= K) {
            int[] recovered = decode(availableIndices, availableValues);
            System.out.printf("  [读] 恢复成功! 原始数据: %s%n%n", Arrays.toString(recovered));
            return recovered;
        } else {
            System.out.printf("  [读] 恢复失败! 仅 %d/%d 块可用, 至少需要 %d%n%n", availableIndices.size(), TOTAL, K);
            return null;
        }
    }

    /* ======================== 演示入口 ======================== */

    public static void main(String[] args) {
        System.out.println("========== 纠删码(Erasure Coding)原理模拟 ==========\n");

        storageOverheadComparison();
        writeFlowDemo();
        readRecoveryDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /** RS(3+2) vs 多副本(3) 存储开销对比 */
    static void storageOverheadComparison() {
        System.out.println("--- 1. EC vs 多副本 存储开销对比 ---");

        final int ORIGINAL_SIZE = 100; // 100MB

        System.out.println("| 方案        | 存储开销 | 实际占用 | 容错数 | 计算开销 |");
        System.out.println("|------------|---------|---------|--------|---------|");

        // 3副本
        int replicaCost = ORIGINAL_SIZE * 3;
        System.out.printf("| 3副本       | %6.0f%% | %4d MB |  2     | 无      |%n", 300.0, replicaCost);

        // RS(3+2) = 100MB → 分3块, 每块33.3MB, +2校验块 → 5块共166.7MB
        double ecCost3_2 = ORIGINAL_SIZE * (K + M) / (double) K;
        System.out.printf("| RS(3+2)     | %6.0f%% | %4d MB |  2     | 中      |%n",
                ecCost3_2 * 100 / ORIGINAL_SIZE, (int) ecCost3_2);

        // RS(6+3) = 100MB → 分6块, +3校验块 → 9块共150MB
        double ecCost6_3 = ORIGINAL_SIZE * (6 + 3) / 6.0;
        System.out.printf("| RS(6+3)     | %6.0f%% | %4d MB |  3     | 较高    |%n",
                ecCost6_3 * 100 / ORIGINAL_SIZE, (int) ecCost6_3);

        // RS(10+6) = 100MB → 分10块, +6校验块 → 16块共160MB
        double ecCost10_6 = ORIGINAL_SIZE * (10 + 6) / 10.0;
        System.out.printf("| RS(10+6)    | %6.0f%% | %4d MB |  6     | 高      |%n",
                ecCost10_6 * 100 / ORIGINAL_SIZE, (int) ecCost10_6);

        // 计算公式
        System.out.println("\n  存储开销公式: (k+m)/k * 100%");
        System.out.printf("    RS(%d+%d): (%d+%d)/%d = %.1f%%%n", K, M, K, M, K, ecCost3_2 * 100 / ORIGINAL_SIZE);
        System.out.println("  结论: EC 在相同容错能力下存储开销远低于多副本, 但需要额外 CPU 编码/解码\n");
    }

    /** 写流程: 编码 → 分发 → 元数据 */
    static void writeFlowDemo() {
        System.out.println("--- 2. EC 写流程 (3步) ---");

        // 原始数据: 模拟 9 字节数据
        int[] original = {41, 42, 43, 44, 45, 46, 47, 48, 49};
        System.out.printf("  原始数据 (%d bytes): %s%n%n", original.length, Arrays.toString(original));

        ECResult result = writeWithEC(original);

        System.out.printf("  编码结果总结: %d 数据块 + %d 校验块, 分布在 %d 个节点%n",
                K, M, result.placements.stream().map(p -> p.nodeId).distinct().count());
        System.out.println();
    }

    /** 读恢复: 正常读取 + 故障恢复 */
    static void readRecoveryDemo() {
        System.out.println("--- 3. EC 读恢复 (正常 + 故障场景) ---");

        // 先写一份数据
        int[] original = {10, 20, 30, 40, 50, 60, 70, 80, 90};
        ECResult result = writeWithEC(original);

        // 场景A: 无故障, 直接读取
        System.out.println("  [场景A] 无故障正常读取:");
        readWithEC(result, Set.of());

        // 场景B: 1个数据块故障, 校验块可恢复
        System.out.println("  [场景B] DATA shard#1 故障, 用 PARITY shard#3 恢复:");
        readWithEC(result, Set.of(1));

        // 场景C: 2个数据块故障 (极端情况, K=3 恰好够)
        System.out.println("  [场景C] DATA shard#0 + DATA shard#2 故障, 用2个校验块恢复:");
        readWithEC(result, Set.of(0, 2));

        // 场景D: 超过容错数, 无法恢复
        System.out.println("  [场景D] DATA shard#0 + DATA shard#1 + PARITY shard#3 故障 (3个), 无法恢复:");
        readWithEC(result, Set.of(0, 1, 3));

        System.out.println("  EC 容错规则: 最多容忍 M 个块故障 (任意组合), 超过则数据丢失\n");
    }
}