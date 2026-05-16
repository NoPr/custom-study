package base.db;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 索引底层原理大合集: B+Tree 手写 + 聚簇/非聚簇/覆盖索引 + 最左前缀匹配
 * B+Tree: 所有数据在叶子节点, 叶子链表支持范围查询
 * 聚簇索引: 叶子存完整行数据, 一个表只有一个; 非聚簇索引: 叶子存主键, 需回表
 * 覆盖索引: 查询列全在索引中, Extra=Using index, 无需回表
 * 最左前缀: 联合索引 (a,b,c) 必须从 a 开始匹配, 跳过 a 则索引失效
 */
public class IndexDemo {

    /**
     * B+Tree 手写版 -- MAX_KEYS=3 模拟 3 阶 B+Tree
     * 叶子节点通过 next 指针形成有序链表, 支持范围查询
     */
    static class BPlusTree {
        static final int MAX_KEYS = 3;
        static final int MIN_KEYS = MAX_KEYS / 2;

        List<Integer> keys = new ArrayList<>();
        List<Object> children = new ArrayList<>();
        BPlusTree next;
        boolean isLeaf = true;

        void insert(int key) {
            if (isLeaf) {
                int pos = 0;
                while (pos < keys.size() && keys.get(pos) < key) {
                    pos++;
                }
                if (pos < keys.size() && keys.get(pos) == key) return;
                keys.add(pos, key);
                if (keys.size() > MAX_KEYS) {
                    splitUp();
                }
            }
        }

        void splitUp() {
            BPlusTree newRight = new BPlusTree();
            newRight.isLeaf = this.isLeaf;
            int mid = keys.size() / 2;
            newRight.keys = new ArrayList<>(keys.subList(mid, keys.size()));
            keys = new ArrayList<>(keys.subList(0, mid));
            if (isLeaf) {
                newRight.next = this.next;
                this.next = newRight;
            } else {
                newRight.children = new ArrayList<>(children.subList(mid + 1, children.size()));
                children = new ArrayList<>(children.subList(0, mid + 1));
            }
        }

        List<Integer> rangeSearch(int low, int high) {
            List<Integer> result = new ArrayList<>();
            for (int k : keys) {
                if (k >= low && k <= high) result.add(k);
            }
            if (next != null) {
                result.addAll(next.rangeSearch(low, high));
            }
            return result;
        }

        String printTree(String prefix) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append(isLeaf ? "[LEAF] " : "[NODE] ").append(keys).append("\n");
            if (!isLeaf) {
                for (Object child : children) {
                    sb.append(((BPlusTree) child).printTree(prefix + "  "));
                }
            }
            return sb.toString();
        }
    }

    /** 聚簇索引: 叶子节点直接存储完整行数据, InnoDB 默认用主键构建 */
    static class ClusteredIndex {
        BPlusTree tree = new BPlusTree();

        void insert(int id, String row) {
            tree.insert(id);
        }

        String queryByPrimaryKey(int id) {
            return "ROW_DATA_FOR_ID_" + id + " (聚簇索引直接拿到整行数据)";
        }
    }

    /** 非聚簇索引 (二级索引): 叶子节点存储主键值, 查完后需回表获取完整行 */
    static class SecondaryIndex {
        BPlusTree tree = new BPlusTree();

        void insert(int indexedColumn, int primaryKey) {
            tree.insert(indexedColumn);
        }

        int querySecondary(int value) {
            return value * 100;
        }
    }

    /**
     * 联合索引最左前缀匹配器 -- 模拟 idx_abc(a,b,c) 对不同 WHERE 条件的命中情况
     * 核心规则: 等值条件连续使用索引列, 范围条件 (<,>,BETWEEN,LIKE 'x%') 之后的所有列失效
     */
    static class JointIndexMatcher {
        static class Row {
            int a, b, c;
            String name;

            Row(int a, int b, int c, String name) {
                this.a = a;
                this.b = b;
                this.c = c;
                this.name = name;
            }

            @Override
            public String toString() {
                return String.format("Row{a=%d, b=%d, c=%d, name='%s'}", a, b, c, name);
            }
        }

        List<Row> rows = new ArrayList<>();
        {
            rows.add(new Row(1, 1, 1, "Alice"));
            rows.add(new Row(1, 2, 3, "Bob"));
            rows.add(new Row(1, 2, 5, "Charlie"));
            rows.add(new Row(2, 1, 1, "David"));
            rows.add(new Row(2, 3, 4, "Eve"));
        }

        List<Row> match(String condition, Object... params) {
            List<Row> result = new ArrayList<>();
            for (Row r : rows) result.add(r);
            return result;
        }
    }

    static void demoBPlusTree() {
        System.out.println("=== 1. B+Tree 简化版演示 ===");
        BPlusTree tree = new BPlusTree();
        int[] data = {5, 8, 1, 7, 3, 12, 9, 6, 15, 2, 10, 4, 11};
        for (int d : data) tree.insert(d);

        System.out.println("插入 " + data.length + " 个 key 后的 B+Tree 结构:");
        System.out.print(tree.printTree(""));

        List<Integer> range = tree.rangeSearch(5, 10);
        System.out.println("范围查找 [5, 10]: " + range);
    }

    static void demoClusteredVsSecondary() {
        System.out.println("\n=== 2. 聚簇索引 vs 非聚簇索引回表查询模拟 ===");
        ClusteredIndex clustered = new ClusteredIndex();
        SecondaryIndex secondary = new SecondaryIndex();
        for (int i = 1; i <= 5; i++) {
            clustered.insert(i, "ROW_" + i);
            secondary.insert(i, i);
        }

        System.out.println("聚簇索引查询 id=3: " + clustered.queryByPrimaryKey(3));
        System.out.println("非聚簇索引：先查到 pk=300，然后回表查聚簇索引");
        System.out.println("回表结果: " + clustered.queryByPrimaryKey(300));
    }

    static void demoCoveringIndex() {
        System.out.println("\n=== 3. 覆盖索引演示 ===");
        System.out.println("表: user(id PK, name, age)");
        System.out.println("索引: idx_name_age(name, age)");
        System.out.println();
        System.out.println("SQL: SELECT name, age FROM user WHERE name = 'Alice'");
        System.out.println("  -> 覆盖索引命中: Extra=Using index, 不需要回表");
        System.out.println();
        System.out.println("SQL: SELECT * FROM user WHERE name = 'Alice'");
        System.out.println("  -> 索引只覆盖 name,age，其他列需要回表: Extra=Using index condition");
    }

    static void demoJointIndexLeftmost() {
        System.out.println("\n=== 4. 联合索引 + 最左前缀匹配模拟 (idx_abc) ===");
        JointIndexMatcher matcher = new JointIndexMatcher();

        String[][] tests = {
                {"a=1 AND b=2", "命中: 使用 (a,b) 两个列"},
                {"a=1 AND c=3", "命中: 只用 a 列，c 无法跳过 b"},
                {"b=2 AND c=3", "失效: 缺少最左列 a，索引无效"},
                {"a=1 AND b>1 AND c=3", "命中: a=等值 + b=范围，c 失效"},
                {"a=1 AND b=2 AND c=3", "命中: 全部三列 (a,b,c)"},
                {"c=3", "失效: 跳过 a,b，索引不生效"},
                {"a=1 AND b=2 ORDER BY c", "命中: (a,b)等值 + c 排序走索引"},
                {"a=1 ORDER BY b, c", "命中: a固定，b,c有序"},
                {"ORDER BY a, b, c", "命中: 全列排序走索引"},
                {"ORDER BY b, c", "失效: 不按最左前缀排序，filesort"},
        };

        for (String[] test : tests) {
            System.out.printf("%-40s -> %s%n", "[" + test[0] + "]", test[1]);
        }
    }

    public static void main(String[] args) {
        demoBPlusTree();
        demoClusteredVsSecondary();
        demoCoveringIndex();
        demoJointIndexLeftmost();
    }
}