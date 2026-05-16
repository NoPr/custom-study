package base.db;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Oracle/Kingbase 特性对比: ROWNUM 分页 vs ROW_NUMBER() + MERGE INTO vs INSERT ON DUPLICATE KEY + SEQUENCE vs AUTO_INCREMENT
 * ROWNUM 问题: 在 ORDER BY 之前赋值, 导致分页结果不确定, 需双层子查询
 * MERGE INTO: Oracle 的 upsert 标准语法, 比 MySQL INSERT ON DUPLICATE KEY 功能更强
 * SEQUENCE: 独立数据库对象, 可多表共享、可设置缓存步长; MySQL AUTO_INCREMENT 绑定单列
 * Kingbase 兼容模式: 同时支持 Oracle 语法 (DUAL/ROWNUM/MERGE) 和 MySQL 语法 (LIMIT/AUTO_INCREMENT)
 */
public class OracleKingbaseDemo {

    /** Oracle 分页对比 -- ROWNUM 旧式双层嵌套 vs ROW_NUMBER() + OFFSET/FETCH 新式写法 */
    static class OraclePagination {
        static class Row {
            int id;
            String name;
            int salary;

            Row(int id, String name, int salary) {
                this.id = id;
                this.name = name;
                this.salary = salary;
            }

            @Override
            public String toString() {
                return String.format("Row{id=%d, name='%s', salary=%d}", id, name, salary);
            }
        }

        static List<Row> mockRows() {
            List<Row> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                rows.add(new Row(i, "User" + i, 5000 + i * 100));
            }
            return rows;
        }

        static void demoRownumVsRowNumber() {
            System.out.println("=== Oracle ROWNUM vs ROW_NUMBER() 分页对比 ===");
            List<Row> rows = mockRows();

            System.out.println("\n-- Oracle 旧式写法 (ROWNUM, 两层嵌套) --");
            String oracleOld = """
                    SELECT * FROM (
                      SELECT a.*, ROWNUM rn FROM (
                        SELECT * FROM employees ORDER BY salary DESC
                      ) a WHERE ROWNUM <= 10
                    ) WHERE rn >= 1""";
            System.out.println(oracleOld);
            System.out.println("问题: ROWNUM 在 ORDER BY 之前赋值，第二页第二层 ROWNUM <= 20 嵌套复杂");

            System.out.println("\n-- Oracle 新式写法 (ROW_NUMBER() + OFFSET/FETCH) --");
            String oracleNew = """
                    SELECT * FROM employees
                    ORDER BY salary DESC
                    OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY""";
            System.out.println(oracleNew);

            System.out.println("\n-- MySQL 写法 --");
            String mysql = """
                    SELECT * FROM employees ORDER BY salary DESC LIMIT 0, 10""";
            System.out.println(mysql);

            System.out.println("\n模拟结果 (第1页, 每页5条, 按 salary DESC):");
            rows.sort((a, b) -> Integer.compare(b.salary, a.salary));
            for (int i = 0; i < 5; i++) {
                System.out.println("  " + rows.get(i));
            }
        }
    }

    /** MERGE INTO vs INSERT ON DUPLICATE KEY -- 三种数据库的 upsert 语法对比 */
    static class MergeComparer {
        static class Record {
            int id;
            String name;
            int version;

            Record(int id, String name, int version) {
                this.id = id;
                this.name = name;
                this.version = version;
            }

            @Override
            public String toString() {
                return String.format("Record{id=%d, name='%s', version=%d}", id, name, version);
            }
        }

        static void demoMergeVsInsertOnDuplicate() {
            System.out.println("\n=== MERGE INTO vs INSERT ON DUPLICATE KEY ===");

            System.out.println("-- Oracle MERGE INTO --");
            String oracleMerge = """
                    MERGE INTO target_table t
                    USING (SELECT ? AS id, ? AS name FROM dual) s
                    ON (t.id = s.id)
                    WHEN MATCHED THEN
                      UPDATE SET t.name = s.name, t.version = t.version + 1
                    WHEN NOT MATCHED THEN
                      INSERT (id, name, version) VALUES (s.id, s.name, 1)""";
            System.out.println(oracleMerge);

            System.out.println("\n-- MySQL INSERT ON DUPLICATE KEY --");
            String mysqlUpsert = """
                    INSERT INTO target_table (id, name, version)
                    VALUES (?, ?, 1)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), version = version + 1""";
            System.out.println(mysqlUpsert);

            System.out.println("\n-- PostgreSQL INSERT ON CONFLICT --");
            String pgUpsert = """
                    INSERT INTO target_table (id, name, version)
                    VALUES (?, ?, 1)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, version = target_table.version + 1""";
            System.out.println(pgUpsert);

            System.out.println("\n模拟 upsert 行为:");
            List<Record> records = new ArrayList<>();
            records.add(new Record(1, "Alice", 1));
            System.out.println("初始: " + records);

            int upsertId1 = 1;
            String newName1 = "Alice_v2";
            Record existing = records.stream().filter(r -> r.id == upsertId1).findFirst().orElse(null);
            if (existing != null) {
                existing.name = newName1;
                existing.version++;
            }
            System.out.println("UPSERT id=1: " + records);

            int upsertId2 = 2;
            String newName2 = "Bob";
            existing = records.stream().filter(r -> r.id == upsertId2).findFirst().orElse(null);
            if (existing != null) {
                existing.name = newName2;
                existing.version++;
            } else {
                records.add(new Record(upsertId2, newName2, 1));
            }
            System.out.println("UPSERT id=2: " + records);
        }
    }

    /** SEQUENCE vs AUTO_INCREMENT -- Oracle 序列是独立对象, MySQL 自增绑定到列 */
    static class SequenceSimulator {
        static AtomicLong oracleSeq = new AtomicLong(1);
        static AtomicLong mysqlAutoInc = new AtomicLong(1);

        static long oracleNextVal() {
            return oracleSeq.addAndGet(1);
        }

        static long mysqlAutoIncrement() {
            return mysqlAutoInc.getAndIncrement();
        }

        static void demoSequenceVsAutoIncrement() {
            System.out.println("\n=== SEQUENCE vs AUTO_INCREMENT 对比 ===");

            System.out.printf("%-25s | %-25s%n", "Oracle SEQUENCE", "MySQL AUTO_INCREMENT");
            System.out.println("-".repeat(55));
            System.out.printf("%-25s | %-25s%n", "独立对象，可多个表共享", "绑定到特定表的一列");
            System.out.printf("%-25s | %-25s%n", "CACHE 预分配减少 IO", "innodb_autoinc_lock_mode");
            System.out.printf("%-25s | %-25s%n", "SELECT seq.NEXTVAL FROM dual", "INSERT 时自动生成");
            System.out.printf("%-25s | %-25s%n", "可设置步长 INCREMENT BY", "auto_increment_increment");
            System.out.printf("%-25s | %-25s%n", "可 CYCLE 循环使用", "到达上限报错");

            System.out.println("\nID 生成模拟:");
            System.out.printf("Oracle NEXTVAL: %d, %d, %d%n", oracleNextVal(), oracleNextVal(), oracleNextVal());
            System.out.printf("MySQL auto_inc: %d, %d, %d%n", mysqlAutoIncrement(), mysqlAutoIncrement(), mysqlAutoIncrement());
        }
    }

    /** Kingbase 兼容模式 -- 同时支持 Oracle 和 MySQL 语法, 迁移关键差异对比 */
    static class KingbaseCompatDemo {
        static void demoDualSyntax() {
            System.out.println("\n=== Kingbase 兼容模式演示 ===");

            String[][] examples = {
                    {"Oracle 模式", "SELECT * FROM DUAL", "可用"},
                    {"Oracle 模式", "SELECT seq.NEXTVAL FROM DUAL", "可用"},
                    {"Oracle 模式", "SELECT * FROM (SELECT ...) WHERE ROWNUM <= 10", "可用"},
                    {"Oracle 模式", "MERGE INTO ... USING dual", "可用"},
                    {"MySQL 模式", "SELECT * FROM table LIMIT 10", "可用"},
                    {"MySQL 模式", "INSERT ... ON DUPLICATE KEY UPDATE", "可用"},
                    {"MySQL 模式", "SHOW TABLES", "可用"},
                    {"MySQL 模式", "SELECT * FROM table FOR UPDATE", "可用"},
                    {"MySQL 模式", "AUTO_INCREMENT", "可用"},
            };

            System.out.printf("%-12s | %-45s | %-6s%n", "兼容模式", "语法", "支持");
            System.out.println("-".repeat(72));
            for (String[] ex : examples) {
                System.out.printf("%-12s | %-45s | %-6s%n", ex[0], ex[1], ex[2]);
            }

            System.out.println("\nKingbase 迁移关键差异:");
            System.out.println("1. 空串 vs NULL: Oracle ''=NULL, Kingbase Oracle模式 ''=NULL, MySQL模式 ''!=''");
            System.out.println("2. 大小写: Oracle 默认大写, Kingbase 模式决定, 建议统一加引号");
            System.out.println("3. 数据类型: NUMBER->NUMERIC, VARCHAR2->VARCHAR, CLOB->TEXT");
            System.out.println("4. 存储过程: PL/SQL 兼容需设置 plsql_mode=oracle");
            System.out.println("5. 序列: Oracle 模式自动创建, MySQL 模式需手动");
        }
    }

    public static void main(String[] args) {
        OraclePagination.demoRownumVsRowNumber();
        MergeComparer.demoMergeVsInsertOnDuplicate();
        SequenceSimulator.demoSequenceVsAutoIncrement();
        KingbaseCompatDemo.demoDualSyntax();
    }
}