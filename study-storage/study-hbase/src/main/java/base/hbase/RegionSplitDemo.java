package base.hbase;

import java.util.*;

/**
 * HBase Region 拆分全流程 Pure Java 模拟。
 * 完整链路: 数据写入 -> MemStore 累积 -> Flush 刷写为 HFile -> Region 文件增长
 * -> 超过阈值触发 Split -> 计算 Split Point -> 创建 Daughter Regions
 * -> 更新 META 表 -> 父 Region 下线。
 *
 * <p>核心机制:
 * 1. MemStore Flush 触发条件: 大小达 hbase.hregion.memstore.flush.size (默认 128MB)
 *    或 RegionServer 全局 MemStore 上限触发
 * 2. HFile 持久化: Flush 后生成不可变 HFile, 后续写入走新 MemStore
 * 3. Split 阈值: hbase.hregion.max.filesize (默认 10GB) 或自定义 SplitPolicy
 * 4. Split Point: 找到 Region 中间 Key (MidKey), 一切为二
 * 5. 拆分期间读写: HBase 2.0+ 引入 Procedure V2, 保证原子性和一致性
 */
public class RegionSplitDemo {

    public static void main(String[] args) {
        System.out.println("========== HBase Region 拆分模拟 ==========\n");

        fullSplitFlowDemo();
        splitPolicyComparison();
        splitReadWriteConsistency();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /* ===================== 1. Region Split 全流程 ===================== */

    /**
     * 模拟 Region 拆分完整流程, 从数据写入到 Daughter Regions 上线。
     *
     * <p>流程: MemStore -> Flush(HFile) -> RegionSize 检查 -> Split Point -> Daughter Regions -> META 更新
     */
    static void fullSplitFlowDemo() {
        System.out.println("--- 1. Region Split 全流程 ---");

        MemStore memStore = new MemStore(128);     // 128MB 触发 Flush
        Region region = new Region("region_001", "user_0000", "user_9999");
        List<HFile> hFiles = new ArrayList<>();

        /* 阶段1: 写入数据到 MemStore */
        System.out.println("[阶段1] 数据写入 MemStore...");
        for (int i = 1; i <= 150; i++) {
            String rowKey = String.format("user_%04d", i);
            String value = "data_" + i;
            memStore.put(rowKey, value);
        }
        System.out.printf("  MemStore 当前大小: %d MB (阈值 %d MB)%n", memStore.currentSizeMB(), memStore.flushThresholdMB);

        /* 阶段2: MemStore Flush -> HFile */
        System.out.println("[阶段2] MemStore Flush -> HFile...");
        if (memStore.currentSizeMB() >= memStore.flushThresholdMB) {
            HFile flushedFile = memStore.flushToHFile("hfile_001");
            hFiles.add(flushedFile);
            region.addHFile(flushedFile);
            System.out.printf("  Flush 完成: %s, 文件大小 %d MB%n", flushedFile.name, flushedFile.sizeMB);
            System.out.printf("  Region 总大小: %d MB%n", region.totalSizeMB());
        }

        /* 阶段3: 追加数据, 继续 Flush, Region 增长 */
        System.out.println("[阶段3] 继续写入 + Flush, Region 持续增长...");
        for (int j = 1; j <= 5; j++) {
            for (int i = 1; i <= 150; i++) {
                memStore.put(String.format("user_%04d", i + j * 1000), "data_batch" + j);
            }
            if (memStore.currentSizeMB() >= memStore.flushThresholdMB) {
                HFile hf = memStore.flushToHFile("hfile_00" + (j + 1));
                hFiles.add(hf);
                region.addHFile(hf);
            }
        }
        System.out.printf("  Region 总大小: %d MB (分裂阈值 %d MB)%n",
                region.totalSizeMB(), region.splitThresholdMB);

        /* 阶段4: 触发 Split */
        System.out.println("[阶段4] 触发 Region Split...");
        if (region.totalSizeMB() >= region.splitThresholdMB) {
            String splitPoint = region.calculateMidKey();
            System.out.printf("  Split Point (中间 RowKey): %s%n", splitPoint);

            /* 创建两个 Daughter Regions */
            Region daughterA = new Region("region_001_A", region.startKey, splitPoint);
            Region daughterB = new Region("region_001_B", splitPoint, region.endKey);

            /* 将 HFile 按 Split Point 分配到两个子 Region */
            for (HFile hf : hFiles) {
                if (hf.maxKey.compareTo(splitPoint) <= 0) {
                    daughterA.addHFile(hf);
                } else if (hf.minKey.compareTo(splitPoint) > 0) {
                    daughterB.addHFile(hf);
                } else {
                    /* 跨 Split Point 的 HFile 需拆分 (实际由 HFileSplitter 处理) */
                    daughterA.addHFile(hf);
                    daughterB.addHFile(hf);
                }
            }

            System.out.printf("  Daughter-A: [%s, %s), 大小=%d MB%n",
                    daughterA.startKey, daughterA.endKey, daughterA.totalSizeMB());
            System.out.printf("  Daughter-B: [%s, %s), 大小=%d MB%n",
                    daughterB.startKey, daughterB.endKey, daughterB.totalSizeMB());

            /* 阶段5: 更新 META 表 */
            System.out.println("[阶段5] 更新 META 表...");
            System.out.printf("  META 操作: DELETE region_001, PUT region_001_A, PUT region_001_B%n");
            System.out.println("  父 Region 标记为 SPLIT 状态, 新请求路由到 Daughter Regions\n");
        }
    }

    /* ===================== 2. Split Policy 对比 ===================== */

    /**
     * HBase 支持的几种 Split 策略。
     *
     * <p>ConstantSizeRegionSplitPolicy: 达到 hbase.hregion.max.filesize 即拆分 (默认 10GB)
     * IncreasingToUpperBoundRegionSplitPolicy (默认): Region 数量越多, 拆分阈值越大 (防止小 Region 过多)
     *   公式: Min(totalSize * 2 / regionCount^3, maxFilesize)
     * KeyPrefixRegionSplitPolicy: 按 RowKey 前缀分组拆分
     * DelimitedKeyPrefixRegionSplitPolicy: 按分隔符前缀分组拆分
     * DisabledRegionSplitPolicy: 禁止自动拆分
     */
    static void splitPolicyComparison() {
        System.out.println("--- 2. Split Policy 策略对比 ---");

        System.out.println("  | 策略                    | 触发条件                      | 适用场景           |");
        System.out.println("  |-------------------------|-------------------------------|--------------------|");
        System.out.println("  | ConstantSize            | Region >= max.filesize        | 稳定大小场景       |");
        System.out.println("  | IncreasingToUpperBound  | Min(size*2/N^3, max.filesize) | 默认策略, 自适应   |");
        System.out.println("  | KeyPrefix               | 按 RowKey prefix 分组再拆分   | 前缀有业务含义     |");
        System.out.println("  | DelimitedKeyPrefix      | 按分隔符取前缀, 分组拆分      | 分隔符分隔的 RowKey|");
        System.out.println("  | Disabled                | 永不自动拆分                  | 预分区 + 手动管理  |");

        /* 模拟 IncreasingToUpperBound 策略 */
        System.out.println("\n  IncreasingToUpperBound 示例:");
        long maxFileSize = 10L * 1024; // 10GB = 10240MB
        int[] regionCounts = {1, 2, 4, 8, 16, 32};
        long totalSize = 50L * 1024; // 50GB
        for (int count : regionCounts) {
            long tableSize = totalSize / count;
            long threshold = Math.min(tableSize * 2 / ((long) count * count * count), maxFileSize);
            System.out.printf("    Region数=%2d, 每Region约%4dMB, 拆分阈值=%4dMB%n",
                    count, tableSize, threshold > 0 ? threshold : maxFileSize);
        }
        System.out.println();
    }

    /* ===================== 3. 拆分期间读写一致性 ===================== */

    /**
     * 拆分期间的数据一致性保证。
     *
     * <p>HBase 1.x: Split 期间父 Region 先关闭再创建子 Region, 期间写入阻塞。
     * HBase 2.0+ Procedure V2: 引入状态机, 拆分过程原子化。阶段如下:
     *   1. SPLITTING: 父 Region 标记为 splitting, 读写仍走父 Region
     *   2. SPLIT: HFile 按 split point 分配到两个子 Region
     *   3. SPLIT_COMPLETED: 父 Region 标记为 split, 子 Region 上线,
     *      Client 通过 META 表重新定位
     *
     * <p>写阻塞时间: 从父 Region close 到子 Region open, 通常毫秒级。
     * 读阻塞: 如果 Client 缓存了旧 Region 信息, 遇到 NotServingRegionException
     * 后会重试并重新定位。
     */
    static void splitReadWriteConsistency() {
        System.out.println("--- 3. 拆分期间读写一致性 ---");

        System.out.println("  Procedure V2 (HBase 2.0+) 拆分状态机:");
        String[] states = {
                "PREPARE      -> 准备拆分, 获取写锁",
                "SPLITTING    -> 父 Region Flush, 生成 reference HFile",
                "SPLIT         -> META 表添加子 Region 条目, 父 Region 仍在线",
                "SPLIT_COMPLETED -> 父 Region 下线, 子 Region 上线接手请求"
        };
        for (String state : states) {
            System.out.printf("    %s%n", state);
        }

        System.out.println("\n  读一致性:");
        System.out.println("    - 拆分期间, 读请求仍路由到父 Region");
        System.out.println("    - Client 缓存过期 -> NotServingRegionException -> 重试 META 定位");

        System.out.println("  写一致性:");
        System.out.println("    - 拆分期间, 写请求路由到父 Region, Flush 后才正式拆分");
        System.out.println("    - 父 Region close -> 子 Region open 之间有短暂写阻塞 (毫秒级)");
        System.out.println("    - WAL (Write Ahead Log) 保证数据不丢失");

        System.out.println("\n  关键优化:");
        System.out.println("    - 预分区: 建表时规划 Region, 避免运行时自动 Split");
        System.out.println("    - 手动 Split: 业务低峰期手动触发, 可控性强");
        System.out.println("    - 监控: 关注 Region 数量和大小, 提前预警\n");
    }

    /* ===================== 内部模型类 ===================== */

    /** MemStore 写缓存模拟 */
    static class MemStore {
        private final NavigableMap<String, String> data = new TreeMap<>();
        final int flushThresholdMB;
        private int estimatedSizeMB;

        MemStore(int flushThresholdMB) {
            this.flushThresholdMB = flushThresholdMB;
        }

        void put(String rowKey, String value) {
            data.put(rowKey, value);
            estimatedSizeMB = (int) (data.size() * 0.85); // 每条记录约 0.85 MB 模拟
        }

        int currentSizeMB() { return estimatedSizeMB; }

        HFile flushToHFile(String name) {
            HFile hf = new HFile(name, data.firstKey(), data.lastKey(), estimatedSizeMB);
            data.clear();
            estimatedSizeMB = 0;
            return hf;
        }
    }

    /** HFile 模拟 */
    static class HFile {
        final String name;
        final String minKey;
        final String maxKey;
        final int sizeMB;

        HFile(String name, String minKey, String maxKey, int sizeMB) {
            this.name = name;
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.sizeMB = sizeMB;
        }
    }

    /** Region 模拟 */
    static class Region {
        final String name;
        final String startKey;
        final String endKey;
        final int splitThresholdMB = 512;  // 512MB 模拟 (实际默认 10GB)
        private final List<HFile> hFiles = new ArrayList<>();

        Region(String name, String startKey, String endKey) {
            this.name = name;
            this.startKey = startKey;
            this.endKey = endKey;
        }

        void addHFile(HFile hf) { hFiles.add(hf); }

        int totalSizeMB() {
            return hFiles.stream().mapToInt(hf -> hf.sizeMB).sum();
        }

        /**
         * 计算 Split Point: 取 Region 内所有数据的中间 RowKey。
         * 实际实现会扫描 HFile 的 midkey 元数据, 这里简化取字典序中点。
         */
        String calculateMidKey() {
            List<String> allKeys = new ArrayList<>();
            for (HFile hf : hFiles) {
                allKeys.add(hf.minKey);
                allKeys.add(hf.maxKey);
            }
            Collections.sort(allKeys);
            return allKeys.get(allKeys.size() / 2);
        }
    }
}