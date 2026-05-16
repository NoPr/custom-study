package base.db;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PostgreSQL 核心特性模拟: MVCC 多版本并发控制 + VACUUM 垃圾回收 + GIN 倒排索引 + BRIN 块范围索引
 * MVCC: xmin(创建事务ID) + xmax(删除事务ID=0表示未删除), 读取时根据快照判断可见性
 * VACUUM: 回收被标记为 DEAD 的旧版本元组, 更新可见性地图, 防止事务 ID 回卷
 * GIN: 倒排索引, 一个 token 映射到多个文档 ID, 用于全文搜索/JSONB/数组
 * BRIN: 块范围索引, 每块只存 min/max, 极省空间, 适合时序/append-only 场景
 */
public class PostgreSQLDemo {

    /**
     * MVCC 模拟器 -- 每个 Tuple 有 xmin(创建) 和 xmax(删除),
     * 写入新版本时旧版本 xmax 被标记, 通过快照时间判断可见性
     */
    static class MVCCSimulator {
        static final AtomicLong nextXid = new AtomicLong(100);

        static class Tuple {
            long xmin;
            long xmax;
            String data;

            Tuple(long xmin, String data) {
                this.xmin = xmin;
                this.xmax = 0;
                this.data = data;
            }

            Tuple(Tuple old, String newData) {
                old.xmax = nextXid.incrementAndGet();
                this.xmin = old.xmax;
                this.xmax = 0;
                this.data = newData;
            }

            boolean isVisible(long snapshotXmin, long snapshotXmax, long currentXid) {
                if (xmin > currentXid) return false;
                if (xmax != 0 && xmax <= currentXid) return false;
                return true;
            }

            boolean isDead(long currentXid) {
                return xmax != 0 && xmax < currentXid;
            }

            @Override
            public String toString() {
                return String.format("Tuple{xmin=%d, xmax=%d, data='%s', visible=%s}",
                        xmin, xmax, data, xmax == 0);
            }
        }

        List<Tuple> tuples = new ArrayList<>();

        void insert(String data) {
            long xid = nextXid.incrementAndGet();
            tuples.add(new Tuple(xid, data));
            System.out.println("INSERT: " + tuples.get(tuples.size() - 1));
        }

        void update(int index, String newData) {
            Tuple old = tuples.get(index);
            Tuple newTuple = new Tuple(old, newData);
            tuples.add(newTuple);
            System.out.println("UPDATE: old=" + old + " -> new=" + newTuple);
        }

        void showAllTuples(long currentXid) {
            System.out.println("\n当前所有元组 (xid=" + currentXid + "):");
            for (int i = 0; i < tuples.size(); i++) {
                Tuple t = tuples.get(i);
                String status = t.isDead(currentXid) ? "DEAD" : "LIVE";
                System.out.printf("  [%d] %s (%s)%n", i, t, status);
            }
        }
    }

    /** VACUUM 模拟 -- 遍历元组列表, 删除 xmax 已提交的旧版本, 保留最新版本 */
    static class VacuumSimulator {
        static void simulate(MVCCSimulator mvcc) {
            System.out.println("\n=== VACUUM 过程模拟 ===");
            System.out.println("执行 VACUUM...");

            List<MVCCSimulator.Tuple> live = new ArrayList<>();
            int deadCount = 0;
            for (MVCCSimulator.Tuple t : mvcc.tuples) {
                if (t.xmax == 0 || t.xmax > MVCCSimulator.nextXid.get()) {
                    live.add(t);
                } else {
                    deadCount++;
                }
            }

            int before = mvcc.tuples.size();
            mvcc.tuples = live;

            System.out.printf("回收前元组数: %d, 回收后: %d, 清理死元组: %d%n",
                    before, live.size(), deadCount);
            System.out.println("VACUUM 作用: 标记可重用空间、更新可见性地图、防止事务ID回卷");
        }
    }

    /** GIN 倒排索引 -- 单词 -> 文档ID集合 的映射, 支持包含/不包含搜索 */
    static class GINIndexSimulator {
        static class InvertedIndex {
            Map<String, Set<Integer>> index = new TreeMap<>();

            void indexDocument(int docId, String text) {
                String[] words = text.toLowerCase().split("\\s+");
                for (String word : words) {
                    index.computeIfAbsent(word, k -> new TreeSet<>()).add(docId);
                }
            }

            Set<Integer> searchContaining(String word) {
                return index.getOrDefault(word.toLowerCase(), Collections.emptySet());
            }

            Set<Integer> searchNotContaining(String word) {
                Set<Integer> all = new TreeSet<>();
                for (Set<Integer> docs : index.values()) all.addAll(docs);
                Set<Integer> containing = searchContaining(word);
                all.removeAll(containing);
                return all;
            }

            void printIndex() {
                System.out.println("\nGIN 倒排索引结构:");
                for (Map.Entry<String, Set<Integer>> e : index.entrySet()) {
                    System.out.printf("  '%s' -> docs: %s%n", e.getKey(), e.getValue());
                }
            }
        }
    }

    /** BRIN 块范围索引 -- 按数据块 (block) 分组, 每块仅存储 min 和 max */
    static class BRINIndexSimulator {
        static class BlockRangeIndex {
            int blockSize;
            Map<Integer, int[]> blocks = new TreeMap<>();

            BlockRangeIndex(int blockSize) {
                this.blockSize = blockSize;
            }

            void insert(int value) {
                int block = value / blockSize;
                int[] range = blocks.computeIfAbsent(block, k -> new int[]{value, value});
                range[0] = Math.min(range[0], value);
                range[1] = Math.max(range[1], value);
            }

            boolean mightContain(int value) {
                int block = value / blockSize;
                int[] range = blocks.get(block);
                if (range == null) return false;
                return value >= range[0] && value <= range[1];
            }

            void printIndex() {
                System.out.println("\nBRIN 块范围索引结构 (block_size=" + blockSize + "):");
                for (Map.Entry<Integer, int[]> e : blocks.entrySet()) {
                    System.out.printf("  Block %d: [%d, %d] (共 %d 个)%n",
                            e.getKey(), e.getValue()[0], e.getValue()[1],
                            e.getValue()[1] - e.getValue()[0] + 1);
                }
            }
        }
    }

    static void demoMVCC() {
        System.out.println("=== 1. MVCC 多版本并发控制模拟 ===");
        MVCCSimulator mvcc = new MVCCSimulator();

        mvcc.insert("Alice, 25");
        mvcc.insert("Bob, 30");
        mvcc.update(0, "Alice, 26");
        mvcc.insert("Charlie, 35");
        mvcc.update(2, "Charlie, 36");

        mvcc.showAllTuples(MVCCSimulator.nextXid.get());
        System.out.println("\nMVCC 原理: xmin=创建事务ID, xmax=删除事务ID(0=未删除)");
        System.out.println("可见性: 元组的 xmin 必须已提交, xmax 必须未提交或 > 当前快照");
    }

    static void demoVacuum() {
        System.out.println("\n=== 2. VACUUM 过程模拟 ===");
        MVCCSimulator mvcc = new MVCCSimulator();
        mvcc.insert("row1");
        mvcc.insert("row2");
        mvcc.update(0, "row1_new");
        mvcc.insert("row3");
        mvcc.update(1, "row2_new");
        System.out.println("VACUUM 前:");
        mvcc.showAllTuples(MVCCSimulator.nextXid.get());
        VacuumSimulator.simulate(mvcc);
    }

    static void demoGIN() {
        System.out.println("\n=== 3. GIN 倒排索引演示 ===");
        GINIndexSimulator.InvertedIndex gin = new GINIndexSimulator.InvertedIndex();
        gin.indexDocument(1, "PostgreSQL is a powerful database");
        gin.indexDocument(2, "MySQL is popular for web apps");
        gin.indexDocument(3, "PostgreSQL supports full text search");
        gin.indexDocument(4, "database indexing is important");

        gin.printIndex();

        System.out.println("\n搜索 'PostgreSQL': " + gin.searchContaining("PostgreSQL"));
        System.out.println("搜索 'database': " + gin.searchContaining("database"));
        System.out.println("GIN 用途: 全文搜索、数组包含(@>)、JSONB 键存在(?)、hstore");
    }

    static void demoBRIN() {
        System.out.println("\n=== 4. BRIN 块范围索引演示 ===");
        BRINIndexSimulator.BlockRangeIndex brin = new BRINIndexSimulator.BlockRangeIndex(100);

        int[] timestamps = {5, 12, 45, 89, 150, 220, 310, 450, 520, 680, 750, 900};
        for (int ts : timestamps) {
            brin.insert(ts);
        }
        brin.printIndex();
        System.out.println("BRIN 特点: 每块只存 min/max，极省空间 (适用于时序数据/append-only大表)");
        System.out.println("查找 250: " + (brin.mightContain(250) ? "可能在块2内(需回表验证)" : "不在任何块"));
        System.out.println("查找 9999: " + (brin.mightContain(9999) ? "可能有" : "不在任何块，跳过"));
    }

    public static void main(String[] args) {
        demoMVCC();
        demoVacuum();
        demoGIN();
        demoBRIN();
    }
}