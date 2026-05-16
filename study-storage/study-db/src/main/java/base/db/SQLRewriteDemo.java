package base.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SQL 改写技巧演示: SELECT * 替换 + OR 转 UNION ALL + LIKE 索引分析 + JOIN 小表驱动大表
 * SELECT * -> 列出具体列名: 减少网络 I/O, 支持覆盖索引
 * OR -> UNION ALL: 每个子查询可独立使用不同索引, 避免全表扫描
 * LIKE 'xxx%': 走 range, 最左前缀匹配索引; LIKE '%xxx': 无法用索引, type=ALL
 * JOIN: 优化器自动选小表驱动大表, 减少 Nested Loop 外层循环次数
 */
public class SQLRewriteDemo {

    static class TableSchema {
        String name;
        String[] columns;

        TableSchema(String name, String[] columns) {
            this.name = name;
            this.columns = columns;
        }
    }

    static class SimpleSQLParser {
        static TableSchema parse(String sql) {
            sql = sql.toUpperCase().replace(";", "").trim();
            String[] parts = sql.split("\\s+FROM\\s+");
            String selectPart = parts[0].replace("SELECT ", "");
            String tablePart = parts[1].split("\\s+")[0];
            return new TableSchema(tablePart, new String[]{"id", "name", "age", "email", "phone"});
        }

        static String rewriteStar(String sql) {
            if (!sql.toUpperCase().contains("SELECT *")) return sql;
            TableSchema schema = parse(sql);
            String cols = String.join(", ", schema.columns);
            return sql.replaceFirst("(?i)SELECT \\*", "SELECT " + cols);
        }

        static String rewriteOrToUnion(String sql) {
            if (!sql.toUpperCase().contains(" OR ")) return sql;
            String upper = sql.toUpperCase();
            String whereClause = sql.substring(upper.indexOf("WHERE ") + 6);
            String[] orParts = whereClause.split("(?i)\\s+OR\\s+");
            String prefix = sql.substring(0, sql.toUpperCase().indexOf("WHERE ")) + "WHERE ";
            List<String> unionParts = new ArrayList<>();
            for (int i = 0; i < orParts.length; i++) {
                unionParts.add(prefix + orParts[i].trim());
            }
            return String.join(" UNION ALL ", unionParts);
        }

        static String analyzeLike(String sql) {
            String upper = sql.toUpperCase();
            int likeIdx = upper.indexOf("LIKE ");
            if (likeIdx == -1) return "无 LIKE";
            String pattern = sql.substring(likeIdx + 5).trim().replace("'", "");
            if (pattern.startsWith("%") && pattern.endsWith("%")) {
                return "LIKE '%xxx%' -> 左右模糊，无法使用索引，type=ALL";
            } else if (pattern.startsWith("%")) {
                return "LIKE '%xxx' -> 前缀模糊，无法使用索引，type=ALL";
            } else if (pattern.endsWith("%")) {
                return "LIKE 'xxx%' -> 后缀模糊，可以使用索引，type=range";
            }
            return "LIKE 'xxx' -> 等值匹配，可以使用索引，type=ref";
        }
    }

    static class JoinSimulator {
        static class Table {
            String name;
            int rowCount;

            Table(String name, int rowCount) {
                this.name = name;
                this.rowCount = rowCount;
            }
        }

        static void demoNestedLoopJoin() {
            System.out.println("\n=== JOIN 小表驱动大表演示 (Nested Loop Join) ===");

            Table small = new Table("orders", 100);
            Table big = new Table("order_items", 10000);

            long costSmallFirst = (long) small.rowCount * (1 + big.rowCount);
            long costBigFirst = (long) big.rowCount * (1 + small.rowCount);

            System.out.printf("小表(%s, %d行) 驱动大表(%s, %d行): 复杂度 ~%d%n",
                    small.name, small.rowCount, big.name, big.rowCount, costSmallFirst);
            System.out.printf("大表(%s, %d行) 驱动小表(%s, %d行): 复杂度 ~%d%n",
                    big.name, big.rowCount, small.name, small.rowCount, costBigFirst);
            System.out.printf("差距: %.0f 倍，优化器自动选择小表驱动大表%n",
                    (double) costBigFirst / costSmallFirst);
        }
    }

    static class SubQueryRewriter {
        static String rewriteSubqueryToJoin(String sql) {
            System.out.println("\n原始子查询: " + sql);
            String rewritten = "SELECT o.* FROM orders o JOIN (SELECT user_id FROM users WHERE age > 18) u ON o.user_id = u.user_id";
            System.out.println("改写为 JOIN: " + rewritten);
            System.out.println("原理: 子查询每行都要执行一次，JOIN 一次扫描即可");
            return rewritten;
        }
    }

    static void demoSelectStarRewrite() {
        System.out.println("=== 1. SELECT * 改写演示 ===");
        String original = "SELECT * FROM users WHERE id = 1";
        System.out.println("原始 SQL: " + original);
        System.out.println("改写后 SQL: " + SimpleSQLParser.rewriteStar(original));
        System.out.println("原因: SELECT * 返回所有列，增加网络 I/O，无法使用覆盖索引");
    }

    static void demoOrToUnion() {
        System.out.println("\n=== 2. OR -> UNION ALL 改写演示 ===");
        String original = "SELECT * FROM users WHERE name = 'Alice' OR age = 25";
        System.out.println("原始 SQL: " + original);
        System.out.println("OR 问题: 每个 OR 条件需要独立判断，通常走全表扫描(type=ALL)");
        String union = SimpleSQLParser.rewriteOrToUnion(original);
        System.out.println("改写为 UNION ALL: " + union);
        System.out.println("优势: 每个子查询可以独立使用索引(name索引 + age索引)");
    }

    static void demoLikeIndex() {
        System.out.println("\n=== 3. LIKE 索引命中/失效演示 ===");
        String[] sqls = {
                "SELECT * FROM users WHERE name LIKE '%abc%'",
                "SELECT * FROM users WHERE name LIKE '%abc'",
                "SELECT * FROM users WHERE name LIKE 'abc%'",
                "SELECT * FROM users WHERE name LIKE 'abc'",
        };
        for (String sql : sqls) {
            System.out.println("SQL: " + sql);
            System.out.println("  -> " + SimpleSQLParser.analyzeLike(sql));
        }
    }

    static void demoJoin() {
        JoinSimulator.demoNestedLoopJoin();
    }

    static void demoSubquery(){
        System.out.println("\n=== 5. 子查询 -> JOIN 改写演示 ===");
        SubQueryRewriter.rewriteSubqueryToJoin(
                "SELECT * FROM orders WHERE user_id IN (SELECT user_id FROM users WHERE age > 18)");
    }

    public static void main(String[] args) {
        demoSelectStarRewrite();
        demoOrToUnion();
        demoLikeIndex();
        demoJoin();
        demoSubquery();
    }
}