package base.db.interview;

import java.util.*;

/**
 * 面试题: MySQL 索引 -- 聚簇/非聚簇/覆盖索引对比 + ICP 索引下推 + 最左前缀匹配代码模拟
 * ICP (Index Condition Pushdown): MySQL 5.6+ 将 WHERE 条件下推到存储引擎层过滤,
 * 减少 Server 层与引擎层之间的数据传输和回表次数
 * 聚簇索引 vs 非聚簇索引: 聚簇索引叶子存完整行 (1次IO), 非聚簇索引叶子存主键 (需回表 >=2次IO)
 * 最左前缀: 联合索引按 (a,b,c) 排序, 跳过 a 直接用 b 无法利用索引
 */
public class Q01_MySQL_Index {

    static void printIndexTypeTable() {
        System.out.println("=== 聚簇索引 vs 非聚簇索引 vs 覆盖索引 对比 ===");
        String fmt = "| %-12s | %-18s | %-18s | %-18s |%n";
        System.out.printf(fmt, "特性", "聚簇索引", "非聚簇索引", "覆盖索引");
        System.out.println("|--------------|--------------------|--------------------|--------------------|");
        System.out.printf(fmt, "数据存储", "叶子存完整行数据", "叶子存主键值", "索引包含查询列");
        System.out.printf(fmt, "表数量限制", "每个表仅1个", "每个表可有多个", "非独立索引类型");
        System.out.printf(fmt, "回表", "不需要", "需要", "不需要");
        System.out.printf(fmt, "查询流程", "索引->数据", "索引->回表->数据", "索引->直接返回");
        System.out.printf(fmt, "IO次数", "1次(最理想)", ">=2次", "1次");
        System.out.printf(fmt, "排序优势", "主键天然有序", "无", "可利用索引序");
    }

    static void simulateICP() {
        System.out.println("\n=== 索引下推 ICP (Index Condition Pushdown) 模拟 ===");
        System.out.println("表: user(id PK, name, age, city)");
        System.out.println("索引: idx_name_age(name, age)");
        System.out.println("SQL: SELECT * FROM user WHERE name LIKE '张%' AND age = 25");
        System.out.println();
        System.out.println("MySQL 5.6 之前 (无ICP):");
        System.out.println("  1. 存储引擎用 name LIKE '张%' 找到所有匹配行");
        System.out.println("  2. 逐行返回给 Server 层");
        System.out.println("  3. Server 层再过滤 age=25");
        System.out.println("  问题: 大量不满足 age=25 的行被返回到 Server 层");
        System.out.println();
        System.out.println("MySQL 5.6+ (有ICP):");
        System.out.println("  1. 存储引擎用 name LIKE '张%' 找到候选行");
        System.out.println("  2. 存储引擎直接在索引中检查 age=25 (下推到引擎层)");
        System.out.println("  3. 只将满足两个条件的行返回 Server 层");
        System.out.println("  优势: 减少 Server 层与引擎层的数据传输，减少回表次数");

        int withoutICP = 1000;
        int actualMatch = 50;
        int withICP = actualMatch;
        System.out.printf("\n模拟数据: 索引扫描 %d 行 -> 无ICP 回表 %d 行 -> 有ICP 回表 %d 行%n",
                withoutICP, withoutICP, withICP);
        System.out.printf("ICP 减少回表: %d 次 (%.0f%%)%n",
                withoutICP - withICP, (1 - (double) withICP / withoutICP) * 100);
    }

    static void simulateExplainTypes() {
        System.out.println("\n=== Explain type 字段含义(优->劣) ===");

        class ExplainRow {
            String type;
            String meaning;
            String example;
            String indexUsed;

            ExplainRow(String type, String meaning, String example, String indexUsed) {
                this.type = type;
                this.meaning = meaning;
                this.example = example;
                this.indexUsed = indexUsed;
            }
        }

        List<ExplainRow> rows = List.of(
                new ExplainRow("system", "表仅一行(系统表)", "system tables", "Y"),
                new ExplainRow("const", "PRIMARY/UNIQUE 等值,最多一行", "WHERE id=1", "Y-PK"),
                new ExplainRow("eq_ref", "JOIN 用 PRIMARY/UNIQUE,每行一个匹配", "JOIN ON a.id=b.id", "Y-PK"),
                new ExplainRow("ref", "非唯一索引等值匹配", "WHERE name='Alice'", "Y"),
                new ExplainRow("fulltext", "全文索引", "MATCH...AGAINST", "Y-FULLTEXT"),
                new ExplainRow("ref_or_null", "ref + NULL 值查找", "WHERE name='Alice' OR name IS NULL", "Y"),
                new ExplainRow("index_merge", "多索引合并", "WHERE name='A' OR age=25", "Y-多个"),
                new ExplainRow("unique_subquery", "IN 子查询转 EXISTS 优化", "WHERE id IN (SELECT ...)", "Y-PK"),
                new ExplainRow("index_subquery", "IN 子查询使用非唯一索引", "WHERE name IN (SELECT ...)", "Y"),
                new ExplainRow("range", "索引范围扫描(<,>,BETWEEN,IN,LIKE 'x%')", "WHERE age>18", "Y"),
                new ExplainRow("index", "全索引扫描(遍历整个索引树)", "SELECT name FROM t", "Y"),
                new ExplainRow("ALL", "全表扫描", "WHERE non_indexed_col=1", "N")
        );

        System.out.printf("| %-15s | %-35s | %-35s | %-8s |%n", "type", "含义", "示例", "用索引");
        System.out.println("|-----------------|-------------------------------------|-------------------------------------|----------|");
        for (ExplainRow r : rows) {
            System.out.printf("| %-15s | %-35s | %-35s | %-8s |%n",
                    r.type, r.meaning, r.example, r.indexUsed);
        }

        System.out.println("\n目标: 至少达到 range 级别，最好 ref; ALL 需要优化");
    }

    static void simulateLeftmostAndICP() {
        System.out.println("\n=== 联合索引最左前缀 + ICP 代码模拟 ===");
        System.out.println("索引: idx_abc(a, b, c)");

        class IndexNode {
            int a, b, c;
            int pk;

            IndexNode(int a, int b, int c, int pk) {
                this.a = a;
                this.b = b;
                this.c = c;
                this.pk = pk;
            }
        }

        List<IndexNode> index = new ArrayList<>();
        index.add(new IndexNode(1, 1, 1, 101));
        index.add(new IndexNode(1, 1, 5, 102));
        index.add(new IndexNode(1, 2, 1, 103));
        index.add(new IndexNode(1, 2, 3, 104));
        index.add(new IndexNode(1, 3, 8, 105));
        index.add(new IndexNode(2, 1, 2, 106));
        index.add(new IndexNode(2, 2, 4, 107));
        index.add(new IndexNode(2, 3, 6, 108));

        System.out.println("索引内容 (按 a,b,c 排序):");
        for (IndexNode n : index) {
            System.out.printf("  (%d,%d,%d) -> pk=%d%n", n.a, n.b, n.c, n.pk);
        }

        System.out.println("\n查询: WHERE a=1 AND b=2 (最左前缀匹配 a,b):");
        List<IndexNode> result1 = index.stream()
                .filter(n -> n.a == 1 && n.b == 2).toList();
        for (IndexNode n : result1) {
            System.out.printf("  命中: (%d,%d,%d), pk=%d%n", n.a, n.b, n.c, n.pk);
        }
        System.out.println("  定位: a=1 二分查找 -> 在 a=1 范围内 b=2 二分查找");

        System.out.println("\n查询: WHERE b=2 AND c=3 (跳过a，最左前缀失效):");
        System.out.println("  无法使用索引，需要全索引扫描或全表扫描");
        System.out.println("  原因: 索引按 (a,b,c) 排序，不知道 a 就无法定位");

        System.out.println("\n查询: WHERE a=1 AND c=5 (仅匹配 a, c 跳过 b):");
        List<IndexNode> result2 = index.stream()
                .filter(n -> n.a == 1 && n.c == 5).toList();
        for (IndexNode n : result2) {
            System.out.printf("  命中: (%d,%d,%d), pk=%d (a=1定位后，c需在索引中过滤)%n",
                    n.a, n.b, n.c, n.pk);
        }
        System.out.println("  ICP 下推: 存储引擎在索引中直接过滤 c=5，无需回表检查");
    }

    public static void main(String[] args) {
        printIndexTypeTable();
        simulateICP();
        simulateExplainTypes();
        simulateLeftmostAndICP();
    }
}