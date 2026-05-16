package base.hbase;

import java.util.*;

/**
 * HBase 存储架构全景 Pure Java 模拟。
 * 核心组件: HMaster (元数据管理 + Region 调度) + HRegionServer (Region 容器 + 读写服务)
 * + ZooKeeper (集群协调 + 选主 + 元数据定位) + HDFS (底层持久化)。
 *
 * <p>LSM-Tree 核心: 写入先到 MemStore (内存有序) -> Flush 到 HFile (磁盘顺序写)
 * -> Compaction 合并小文件。
 *
 * <p>Compaction:
 * Minor Compaction: 选取少量相邻 HFile 合并, 频率高开销低
 * Major Compaction: 选取所有 HFile 合并为一个, 清理过期数据和删除标记, 开销大
 *
 * <p>BloomFilter: 行级过滤, 快速判断 RowKey 是否在 HFile 中,
 * 减少无效磁盘 IO。每个 HFile 维护一个布隆过滤器。
 *
 * <p>BlockCache: 读缓存, 缓存热点数据块。
 * LRUBlockCache (JVM 堆内): 受 GC 影响, 可能 OOM
 * BucketCache (堆外/文件): 堆外内存, 不受 GC 影响, 容量更大
 */
public class StorageArchitectureDemo {

    public static void main(String[] args) {
        System.out.println("========== HBase 存储架构全景演示 ==========\n");

        hbaseArchitectureOverview();
        lsmTreeDemo();
        compactionDemo();
        bloomFilterDemo();
        blockCacheDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /* ===================== 1. HBase 架构全景 ===================== */

    /**
     * HBase 集群架构: HMaster + HRegionServer + ZooKeeper 三核心角色。
     *
     * <p>HMaster: 管理表的 DDL 操作 (create/alter/delete table), Region 分配与负载均衡,
     * RegionServer 故障恢复。无单点 (ZK 选主, 支持 Active/Standby)。
     *
     * <p>HRegionServer: 处理客户端读写请求, 管理 Region (MemStore + HFile + WAL)。
     * 每个 RegionServer 承载多个 Region。
     *
     * <p>ZooKeeper: 存储 META 表位置, HMaster 选主, RegionServer 心跳, 集群配置。
     *
     * <p>HDFS: HFile 和 WAL 持久化存储, 提供数据冗余和容错。
     */
    static void hbaseArchitectureOverview() {
        System.out.println("--- 1. HBase 集群架构 ---");

        System.out.println("  角色              职责");
        System.out.println("  ──────────────────────────────────────────────────");
        System.out.println("  HMaster           DDL 操作, Region 分配/负载均衡, 故障恢复");
        System.out.println("  HRegionServer     读写请求处理, Region 管理 (MemStore + HFile + WAL)");
        System.out.println("  ZooKeeper         META 表位置, HMaster 选主, RS 心跳, 配置存储");
        System.out.println("  HDFS              持久化 HFile 和 WAL, 数据冗余容错");
        System.out.println();

        System.out.println("  请求路径:");
        System.out.println("    Client -> ZK (获取 META 表位置) -> HRegionServer 读取 META 表");
        System.out.println("    -> 定位目标 Region -> 直连目标 HRegionServer 读写");
        System.out.println("    (Client 会缓存 Region 位置, 减少 ZK 和 META 查询)\n");
    }

    /* ===================== 2. LSM-Tree 写入模型 ===================== */

    /**
     * LSM-Tree (Log-Structured Merge-Tree) 写入模型:
     * 1. 写入先到 WAL (Write Ahead Log, HLog) 保证数据不丢
     * 2. 再写入 MemStore (内存有序跳表结构), 写入即成功
     * 3. MemStore 达到阈值 -> Flush 生成 HFile (不可变, 磁盘顺序写)
     * 4. HFile 随写入增多, 通过 Compaction 合并
     *
     * <p>为什么写入快: 顺序写磁盘 + 内存跳跃表, 避免随机 IO。
     * 为什么读取需要优化: 数据分散在 MemStore + 多个 HFile, 需多层查找。
     * 所以需要 BloomFilter + BlockCache 加速读。
     */
    static void lsmTreeDemo() {
        System.out.println("--- 2. LSM-Tree 写入模型 ---");

        /* 模拟 MemStore (使用 TreeMap 替代 ConcurrentSkipListMap) */
        NavigableMap<String, String> memStore = new TreeMap<>();
        List<LSMHFile> hFiles = new ArrayList<>();
        final int MEMSTORE_FLUSH_THRESHOLD = 5;

        System.out.println("  写入流程 (MemStore 阈值=" + MEMSTORE_FLUSH_THRESHOLD + "):");

        String[][] testData = {
                {"r05", "val5"}, {"r02", "val2"}, {"r08", "val8"},
                {"r01", "val1"}, {"r04", "val4"}, {"r07", "val7"},
                {"r03", "val3"}, {"r06", "val6"}, {"r09", "val9"},
                {"r10", "val10"},
        };

        int hFileSeq = 1;
        for (int i = 0; i < testData.length; i++) {
            /* Step 1: 写 WAL (模拟) */
            String rowKey = testData[i][0];
            String value = testData[i][1];

            /* Step 2: 写 MemStore (TreeMap 自动排序) */
            memStore.put(rowKey, value);
            System.out.printf("    写入 WAL -> MemStore: {%s: %s} (MemStore size=%d)%n",
                    rowKey, value, memStore.size());

            /* Step 3: MemStore 满, Flush 为 HFile */
            if (memStore.size() >= MEMSTORE_FLUSH_THRESHOLD) {
                LSMHFile hf = new LSMHFile("hfile_" + hFileSeq++, memStore);
                hFiles.add(hf);
                System.out.printf("    >>> Flush! MemStore -> %s, HFile 总数=%d%n",
                        hf.name, hFiles.size());
                memStore.clear();
            }
        }

        /* 剩余数据 Flush */
        if (!memStore.isEmpty()) {
            LSMHFile hf = new LSMHFile("hfile_" + hFileSeq, memStore);
            hFiles.add(hf);
            System.out.printf("    >>> 最终 Flush! MemStore -> %s%n", hf.name);
        }

        /* 读路径: 先查 MemStore, 再查所有 HFile (从新到旧) */
        System.out.println("\n  读路径 (MemStore -> HFile 从新到旧):");
        String searchKey = "r05";
        String result = memStore.get(searchKey);
        if (result == null) {
            for (int i = hFiles.size() - 1; i >= 0; i--) {
                result = hFiles.get(i).get(searchKey);
                if (result != null) {
                    System.out.printf("    命中 %s%n", hFiles.get(i).name);
                    break;
                }
            }
        } else {
            System.out.println("    命中 MemStore");
        }
        System.out.printf("    查询 RowKey=%s -> %s%n", searchKey, result);

        System.out.println("\n  LSM-Tree 特征:");
        System.out.println("    写入: 顺序写磁盘 (WAL + HFile), 高性能");
        System.out.println("    读取: 多层查找 (MemStore + 多 HFile), 需 BloomFilter/BlockCache 加速");
        System.out.println("    空间放大: Compaction 合并前存在冗余数据\n");
    }

    /** LSM-Tree HFile 模拟 */
    static class LSMHFile {
        final String name;
        final NavigableMap<String, String> data;
        final String firstKey;
        final String lastKey;

        LSMHFile(String name, NavigableMap<String, String> memStoreData) {
            this.name = name;
            this.data = new TreeMap<>(memStoreData);
            this.firstKey = data.firstKey();
            this.lastKey = data.lastKey();
        }

        String get(String key) { return data.get(key); }
    }

    /* ===================== 3. Compaction 合并 ===================== */

    /**
     * Minor Compaction vs Major Compaction:
     *
     * <p>Minor Compaction:
     * - 选取当前 Store 中少量连续小 HFile 合并为一个
     * - 频率高 (每几秒到几分钟), 开销低
     * - 不清理过期数据和删除标记 (Delete Marker)
     * - 目的: 减少 HFile 数量, 加速读操作
     *
     * <p>Major Compaction:
     * - 选取当前 Store 中所有 HFile 合并为一个
     * - 频率低 (默认 7 天, 可配置), 开销大 (IO + CPU)
     * - 清理过期数据 (TTL 过期) 和删除标记
     * - 目的: 回收磁盘空间, 提升读性能
     */
    static void compactionDemo() {
        System.out.println("--- 3. Compaction 合并机制 ---");

        /* 模拟 Store 中的 HFile 列表 */
        List<CompactionHFile> storeFiles = new ArrayList<>();

        /* 创建不同大小和 Key 范围的 HFile */
        storeFiles.add(new CompactionHFile("hf_a", "r01", "r20", 30, false));
        storeFiles.add(new CompactionHFile("hf_b", "r15", "r35", 25, false));
        storeFiles.add(new CompactionHFile("hf_c", "r05", "r15", 20, false));
        storeFiles.add(new CompactionHFile("hf_d", "r22", "r40", 35, false));
        storeFiles.add(new CompactionHFile("hf_e", "r30", "r50", 40, true));

        System.out.println("  合并前 Store HFile 列表:");
        for (CompactionHFile hf : storeFiles) {
            String marker = hf.hasTombstone ? " [含删除标记]" : "";
            System.out.printf("    %s: key=[%s,%s], size=%dMB%s%n",
                    hf.name, hf.firstKey, hf.lastKey, hf.sizeMB, marker);
        }

        System.out.println("\n  Minor Compaction (选取相邻小文件合并):");
        /* 选取 size <= 30MB 的相邻文件合并 */
        List<CompactionHFile> minorSelection = new ArrayList<>();
        for (CompactionHFile hf : storeFiles) {
            if (hf.sizeMB <= 30) minorSelection.add(hf);
        }
        if (!minorSelection.isEmpty()) {
            CompactionHFile merged = mergeHFiles("hf_minor_merged", minorSelection, false);
            System.out.printf("    合并: %s -> %s (size=%dMB)%n",
                    minorSelection.stream().map(h -> h.name).toList(), merged.name, merged.sizeMB);
            System.out.println("    合并后: 未清理删除标记 (expired 数据保留)");
        }

        System.out.println("\n  Major Compaction (全量合并, 清理过期):");
        CompactionHFile majorMerged = mergeHFiles("hf_major_merged", storeFiles, true);
        System.out.printf("    全量合并: %d 个 HFile -> %s (size=%dMB)%n",
                storeFiles.size(), majorMerged.name, majorMerged.sizeMB);
        System.out.println("    清理内容: TTL 过期数据 + 删除标记 (tombstone) -> 回收磁盘空间");

        System.out.println("\n  Compaction 策略对比:");
        System.out.println("    | 类型    | 频率          | 开销  | 清理删除标记 | 适用时机     |");
        System.out.println("    |---------|--------------|------|-------------|-------------|");
        System.out.println("    | Minor   | 高频(秒~分钟) | 低   | 否          | 持续后台运行 |");
        System.out.println("    | Major   | 低频(天~周)   | 高   | 是          | 低峰期手动   |");
        System.out.println();
    }

    static class CompactionHFile {
        final String name;
        final String firstKey;
        final String lastKey;
        final int sizeMB;
        final boolean hasTombstone;

        CompactionHFile(String name, String firstKey, String lastKey, int sizeMB, boolean hasTombstone) {
            this.name = name;
            this.firstKey = firstKey;
            this.lastKey = lastKey;
            this.sizeMB = sizeMB;
            this.hasTombstone = hasTombstone;
        }
    }

    static CompactionHFile mergeHFiles(String newName, List<CompactionHFile> sources, boolean removeTombstones) {
        String minKey = sources.stream().map(h -> h.firstKey).min(String::compareTo).orElse("");
        String maxKey = sources.stream().map(h -> h.lastKey).max(String::compareTo).orElse("");
        int totalSize = sources.stream().mapToInt(h -> h.sizeMB).sum();
        /* Major Compaction 可以去除冗余, 实际合并后大小比 sum 小 */
        int resultSize = removeTombstones ? (int)(totalSize * 0.6) : totalSize;
        boolean hasTombstone = !removeTombstones && sources.stream().anyMatch(h -> h.hasTombstone);
        return new CompactionHFile(newName, minKey, maxKey, resultSize, hasTombstone);
    }

    /* ===================== 4. BloomFilter 行级过滤 ===================== */

    /**
     * 布隆过滤器 (BloomFilter) 用于快速判断一个 RowKey 是否"可能"存在于某个 HFile 中。
     *
     * <p>原理: 使用 K 个哈希函数将 RowKey 映射到位图 (BitSet) 的 K 个位置。
     * 查询时, 如果 K 个位置中有任意一个为 0, 则 RowKey 一定不存在于该 HFile,
     * 从而跳过该文件, 减少磁盘 IO。
     *
     * <p>误判率: BloomFilter 存在误判 (false positive), 即可能报告"存在"但实际上不存在。
     * 此时需要回源 HFile 验证, 但误判率可控 (默认 1%)。
     * 不存在误判 (false negative), 即不会漏报。
     *
     * <p>ROW vs ROWCOL: ROW 级别以 RowKey 为单位; ROWCOL 级别以 RowKey+Column 为单位, 更精确。
     */
    static void bloomFilterDemo() {
        System.out.println("--- 4. BloomFilter 行级过滤 ---");

        int bitSize = 64;
        int hashCount = 3;

        /* 模拟 HFile 数据 */
        Set<String> hFileRows = Set.of("r01", "r03", "r05", "r07", "r09", "r11");

        /* 构建 BloomFilter */
        BitSet bloom = new BitSet(bitSize);
        for (String row : hFileRows) {
            for (int i = 0; i < hashCount; i++) {
                int hash = Math.abs((row.hashCode() + i * 31) % bitSize);
                bloom.set(hash);
            }
        }

        System.out.printf("  HFile 包含 RowKeys: %s%n", hFileRows);
        System.out.printf("  BloomFilter BitSet: %s%n", bloomToString(bloom, bitSize));

        /* 查询测试 */
        String[] testRows = {"r01", "r08", "r11", "r20"};
        System.out.println("\n  查询测试 (hashCount=" + hashCount + "):");
        for (String row : testRows) {
            boolean mightExist = true;
            for (int i = 0; i < hashCount; i++) {
                int hash = Math.abs((row.hashCode() + i * 31) % bitSize);
                if (!bloom.get(hash)) {
                    mightExist = false;
                    break;
                }
            }
            boolean actuallyExists = hFileRows.contains(row);
            String verdict;
            if (mightExist && actuallyExists) {
                verdict = "命中, 读取 HFile";
            } else if (mightExist) {
                verdict = "误判 (false positive), 读 HFile 后发现不存在";
            } else {
                verdict = "一定不存在, 跳过 HFile (节省 IO)";
            }
            System.out.printf("    RowKey=%s: Bloom=%s, 实际=%s -> %s%n",
                    row, mightExist ? "可能存在" : "一定不存在",
                    actuallyExists ? "存在" : "不存在", verdict);
        }

        System.out.println("\n  BloomFilter 类型:");
        System.out.println("    ROW: 以 RowKey 为粒度, 默认开启");
        System.out.println("    ROWCOL: 以 RowKey + ColumnFamily + Qualifier 为粒度, 更精细");
        System.out.println("    配置: 建表时指定 BLOOMFILTER => 'ROWCOL'");
        System.out.println("    效果: 查询特定列时避免扫描不含该列的行, 进一步减少磁盘 IO\n");
    }

    static String bloomToString(BitSet bloom, int size) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            sb.append(bloom.get(i) ? "1" : "0");
            if ((i + 1) % 8 == 0 && i < size - 1) sb.append(" ");
        }
        sb.append("]");
        return sb.toString();
    }

    /* ===================== 5. BlockCache 读缓存 ===================== */

    /**
     * BlockCache: HBase 的读缓存, 缓存从 HFile 读取的热点数据块 (Block)。
     *
     * <p>LRUBlockCache (堆内):
     * - 位于 JVM 堆内, 使用 LRU 淘汰策略
     * - 容量受 JVM 堆限制, 受 GC 影响
     * - 配置: hfile.block.cache.size (默认 0.4, 即 40% 堆内存)
     *
     * <p>BucketCache (堆外):
     * - 位于堆外内存 (DirectByteBuffer) 或 SSD 文件
     * - 不受 JVM GC 影响, 容量更大
     * - 与 LRUBlockCache 组合使用 (CombinedBlockCache)
     * - 配置: hbase.bucketcache.ioengine (offheap/file)
     *
     * <p>CombinedBlockCache: LRUBlockCache (L1) + BucketCache (L2) 两级缓存。
     * 热点数据先入 L1, 淘汰后进入 L2。
     */
    static void blockCacheDemo() {
        System.out.println("--- 5. BlockCache 读缓存 ---");

        /* 模拟 LRUBlockCache (堆内) */
        LRUSimpleCache<String, String> lruCache = new LRUSimpleCache<>(5);

        System.out.println("  LRUBlockCache (JVM 堆内, capacity=5):");
        String[] keys = {"block_a", "block_b", "block_c", "block_d", "block_e", "block_f"};
        for (String key : keys) {
            lruCache.put(key, "data_of_" + key);
            System.out.printf("    写入 %s -> cache=[%s]%n", key, lruCache.keySet());
        }

        System.out.println("\n  BucketCache (堆外, 不受 GC 影响):");
        System.out.println("    - ioengine=offheap: 使用 DirectByteBuffer (堆外内存)");
        System.out.println("    - ioengine=file: 使用 SSD 文件作为缓存");
        System.out.println("    - 优势: 容量大 (几十 GB), 无 GC 压力");

        System.out.println("\n  CombinedBlockCache 两级缓存:");
        System.out.println("    L1 (LRUBlockCache): 堆内, 缓存索引 Block 和 BloomFilter, 小容量");
        System.out.println("    L2 (BucketCache):   堆外/SSD, 缓存数据 Block, 大容量");
        System.out.println("    流程: 查询 -> L1 -> L2 -> HFile 磁盘");
        System.out.println("    淘汰: L1 淘汰数据 Block 进入 L2; L1 保留索引/Bloom");

        System.out.println("\n  BlockCache vs MemStore:");
        System.out.println("    MemStore: 写缓存 (最近写入的数据), 内存, 有序, 可 Flush 到 HFile");
        System.out.println("    BlockCache: 读缓存 (从 HFile 读取的 Block), 内存/堆外, LRU 淘汰");
        System.out.println("    两者互补: 写入走 MemStore, 读取走 BlockCache -> HFile\n");
    }

    /** 简易 LRU 缓存 (LinkedHashMap 实现) */
    static class LRUSimpleCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxCapacity;

        LRUSimpleCache(int maxCapacity) {
            super(maxCapacity, 0.75f, true);
            this.maxCapacity = maxCapacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxCapacity;
        }
    }
}