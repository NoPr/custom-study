package base.seatunnel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 数据转换演示：字段映射(rename/cast/drop) + 过滤(filter) + 关联(join) + 聚合(aggregate) + 加解密 + 字段默认值填充 + 多种转换的流经顺序。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>字段映射</b>：rename 重命名字段、cast 类型转换、drop 删除字段</li>
 *   <li><b>过滤(Filter)</b>：按条件过滤行数据，支持 AND/OR 组合条件</li>
 *   <li><b>关联(Join)</b>：维表关联(左连接)，通过 lookup key 补充维度字段</li>
 *   <li><b>聚合(Aggregate)</b>：按分组键聚合(sum/count/avg/max/min)</li>
 *   <li><b>加解密</b>：敏感字段 AES 加密/SHA256 哈希脱敏</li>
 *   <li><b>默认值填充</b>：NULL 或缺失字段使用默认值</li>
 *   <li><b>转换链顺序</b>：rename → default → cast → filter → encrypt → join → aggregate</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。
 *
 * @author study-tuling
 */
public class TransformDemo {

    /** 转换链顺序常量 */
    private static final List<String> TRANSFORM_ORDER = List.of(
            "RENAME", "DEFAULT", "CAST", "FILTER", "ENCRYPT", "JOIN", "AGGREGATE"
    );

    public static void main(String[] args) {
        System.out.println("=== Seatunnel 数据转换演示 ===\n");

        // 准备原始数据
        List<Row> sourceData = prepareSourceData();
        System.out.printf("原始数据 %d 条:%n", sourceData.size());
        sourceData.forEach(row -> System.out.println("  " + row));

        System.out.printf("%n转换链顺序: %s%n%n", TRANSFORM_ORDER);

        // 按顺序执行转换
        List<Row> result = new ArrayList<>(sourceData);
        result = applyRename(result);
        result = applyDefaultValue(result);
        result = applyCast(result);
        result = applyFilter(result);
        result = applyEncrypt(result);
        result = applyJoin(result);
        result = applyAggregate(result);

        System.out.println("\n最终结果:");
        result.forEach(row -> System.out.println("  " + row));
        System.out.println("\n=== 演示完成 ===");
    }


    // ==================== 数据模型 ====================

    /** 数据行 */
    static class Row {
        final Map<String, Object> fields;

        Row() { this.fields = new LinkedHashMap<>(); }
        Row(Map<String, Object> fields) { this.fields = new LinkedHashMap<>(fields); }

        void put(String key, Object value) { fields.put(key, value); }
        Object get(String key) { return fields.get(key); }
        void remove(String key) { fields.remove(key); }
        boolean has(String key) { return fields.containsKey(key); }
        Row copy() { return new Row(new LinkedHashMap<>(this.fields)); }
        Set<String> keys() { return fields.keySet(); }

        @Override
        public String toString() {
            return fields.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /** 维表数据（模拟字典表/维表） */
    record DimRecord(int deptId, String deptName, String location, int level) {}


    // ==================== 准备数据 ====================

    static List<Row> prepareSourceData() {
        List<Row> rows = new ArrayList<>();

        // 用户数据，含一些空字段
        Row r1 = new Row();
        r1.put("user_id", 1L);
        r1.put("user_name", "Alice");
        r1.put("age", "28");
        r1.put("phone", "13800138000");
        r1.put("email", "alice@example.com");
        r1.put("dept_id", 101);
        r1.put("salary", 15000.0);
        r1.put("status", "active");
        rows.add(r1);

        Row r2 = new Row();
        r2.put("user_id", 2L);
        r2.put("user_name", "Bob");
        r2.put("age", null);             // 缺失
        r2.put("phone", "13900139000");
        r2.put("email", null);           // 缺失
        r2.put("dept_id", 102);
        r2.put("salary", 20000.0);
        r2.put("status", "active");
        rows.add(r2);

        Row r3 = new Row();
        r3.put("user_id", 3L);
        r3.put("user_name", "Charlie");
        r3.put("age", "35");
        r3.put("phone", "13700137000");
        r3.put("email", "charlie@example.com");
        r3.put("dept_id", 101);
        r3.put("salary", 80000.0);       // 异常高薪，用于过滤
        r3.put("status", "inactive");
        rows.add(r3);

        Row r4 = new Row();
        r4.put("user_id", 4L);
        r4.put("user_name", "Diana");
        r4.put("age", "22");
        r4.put("phone", "13600136000");
        r4.put("email", "diana@example.com");
        r4.put("dept_id", 103);
        r4.put("salary", 12000.0);
        r4.put("status", "active");
        rows.add(r4);

        Row r5 = new Row();
        r5.put("user_id", 5L);
        r5.put("user_name", "Eve");
        r5.put("age", "-5");             // 脏数据
        r5.put("phone", null);           // 缺失
        r5.put("email", "eve@example.com");
        r5.put("dept_id", 102);
        r5.put("salary", 0.0);           // 异常低薪，用于过滤
        r5.put("status", "active");
        rows.add(r5);

        return rows;
    }

    /** 维表数据 */
    static Map<Integer, DimRecord> prepareDimensionTable() {
        Map<Integer, DimRecord> dim = new LinkedHashMap<>();
        dim.put(101, new DimRecord(101, "技术部", "北京", 1));
        dim.put(102, new DimRecord(102, "产品部", "上海", 2));
        dim.put(103, new DimRecord(103, "运营部", "深圳", 2));
        return dim;
    }


    // ==================== 1. 字段映射：RENAME + DROP ====================

    /**
     * 字段重命名和删除。
     *
     * <p>rename：将 user_name → name, dept_id → department_id, status → user_status
     * <p>drop：删除 phone 字段（隐私保护，不传输到下游）
     */
    static List<Row> applyRename(List<Row> rows) {
        System.out.println("--- 1. RENAME 字段映射 ---");

        Map<String, String> renameMap = Map.of(
                "user_name", "name",
                "dept_id", "department_id",
                "status", "user_status"
        );
        List<String> dropFields = List.of("phone");

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Row newRow = row.copy();

            // 执行重命名
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                Object value = newRow.get(entry.getKey());
                if (value != null || newRow.has(entry.getKey())) {
                    newRow.remove(entry.getKey());
                    newRow.put(entry.getValue(), value);
                }
            }

            // 执行删除
            for (String field : dropFields) {
                newRow.remove(field);
            }

            System.out.printf("  RENAME: %s → %s%n", rowKeys(row), rowKeys(newRow));
            result.add(newRow);
        }
        System.out.println();
        return result;
    }

    private static String rowKeys(Row row) {
        return row.keys().toString();
    }


    // ==================== 2. 默认值填充 ====================

    /**
     * 字段默认值填充。
     *
     * <p>对 NULL 或缺失字段填充默认值：
     * <ul>
     *   <li>age → "0"</li>
     *   <li>email → "unknown@unknown.com"</li>
     *   <li>department_id → 999</li>
     * </ul>
     */
    static List<Row> applyDefaultValue(List<Row> rows) {
        System.out.println("--- 2. DEFAULT 默认值填充 ---");

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("age", "0");
        defaults.put("email", "unknown@unknown.com");
        defaults.put("department_id", 999);

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Row newRow = row.copy();
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                Object value = newRow.get(entry.getKey());
                if (value == null || !newRow.has(entry.getKey())) {
                    newRow.put(entry.getKey(), entry.getValue());
                    System.out.printf("  DEFAULT: %s=%s → %s%n",
                            entry.getKey(), value, entry.getValue());
                }
            }
            result.add(newRow);
        }
        System.out.println();
        return result;
    }


    // ==================== 3. 类型转换 CAST ====================

    /**
     * 字段类型转换。
     *
     * <p>cast 类型映射：
     * <ul>
     *   <li>age: String → Integer（过滤掉无法转换的脏数据）</li>
     *   <li>salary: Double → Long（精度降级）</li>
     * </ul>
     */
    static List<Row> applyCast(List<Row> rows) {
        System.out.println("--- 3. CAST 类型转换 ---");

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Row newRow = row.copy();
            boolean valid = true;

            // age: String → Integer
            String ageStr = (String) newRow.get("age");
            try {
                int age = Integer.parseInt(ageStr);
                if (age < 0 || age > 150) {
                    System.out.printf("  CAST(age): %s 为非法值（%d），丢弃记录%n", ageStr, age);
                    valid = false;
                } else {
                    newRow.put("age", age);
                    System.out.printf("  CAST(age): String \"%s\" → Integer %d%n", ageStr, age);
                }
            } catch (NumberFormatException e) {
                System.out.printf("  CAST(age): \"%s\" 无法转换为整数，丢弃记录%n", ageStr);
                valid = false;
            }

            // salary: Double → Long
            if (valid) {
                Double salary = (Double) newRow.get("salary");
                newRow.put("salary", salary.longValue());
            }

            if (valid) {
                result.add(newRow);
            }
        }
        System.out.printf("  CAST 后剩余 %d 条%n%n", result.size());
        return result;
    }


    // ==================== 4. 过滤 FILTER ====================

    /**
     * 条件过滤。
     *
     * <p>过滤条件：
     * <ul>
     *   <li>user_status = "active"（只保留活跃用户）</li>
     *   <li>AND salary BETWEEN 1000 AND 50000（排除异常薪资）</li>
     *   <li>AND email NOT NULL（邮箱不能为空）</li>
     * </ul>
     */
    static List<Row> applyFilter(List<Row> rows) {
        System.out.println("--- 4. FILTER 条件过滤 ---");

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            String status = (String) row.get("user_status");
            Long salary = (Long) row.get("salary");
            String email = (String) row.get("email");

            // 条件1：状态过滤
            if (!"active".equals(status)) {
                System.out.printf("  FILTER DROP: %s (user_status=%s)%n", row.get("name"), status);
                continue;
            }

            // 条件2：薪资范围过滤
            if (salary == null || salary < 1000 || salary > 50000) {
                System.out.printf("  FILTER DROP: %s (salary=%d 超出范围)%n", row.get("name"), salary);
                continue;
            }

            // 条件3：邮箱非空过滤
            if (email == null || email.isBlank()) {
                System.out.printf("  FILTER DROP: %s (email 为空)%n", row.get("name"));
                continue;
            }

            System.out.printf("  FILTER PASS: %s%n", row.get("name"));
            result.add(row);
        }
        System.out.printf("  FILTER 后剩余 %d 条%n%n", result.size());
        return result;
    }


    // ==================== 5. 加解密 ====================

    /**
     * 加解密与脱敏处理。
     *
     * <p>处理策略：
     * <ul>
     *   <li>email: SHA256 哈希脱敏（不可逆，用于去重关联）</li>
     *   <li>name: AES 加密（可逆，用于传输安全）</li>
     *   <li>user_id: 保留原始值用于关联</li>
     * </ul>
     */
    static List<Row> applyEncrypt(List<Row> rows) {
        System.out.println("--- 5. ENCRYPT 加解密脱敏 ---");

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Row newRow = row.copy();

            // email SHA256 脱敏
            String email = (String) newRow.get("email");
            if (email != null) {
                String hashedEmail = sha256(email);
                newRow.put("email_hash", hashedEmail);
                newRow.remove("email");
                System.out.printf("  SHA256(email): %s → %s...%n", email,
                        hashedEmail.substring(0, 16));
            }

            // name AES 加密（模拟 Base64 编码结果）
            String name = (String) newRow.get("name");
            if (name != null) {
                String encryptedName = mockAesEncrypt(name);
                newRow.put("name_encrypted", encryptedName);
                newRow.remove("name");
                System.out.printf("  AES(name): %s → %s%n", name, encryptedName);
            }

            result.add(newRow);
        }
        System.out.println();
        return result;
    }

    /** 模拟 AES 加密（实际应使用 javax.crypto.Cipher） */
    static String mockAesEncrypt(String plainText) {
        byte[] bytes = plainText.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b ^ 0x5A)); // XOR 伪加密
        }
        return sb.toString();
    }

    /** SHA256 哈希 */
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }


    // ==================== 6. 关联 JOIN ====================

    /**
     * 维表关联（Lookup Join）。
     *
     * <p>左连接维表字典，通过 department_id 关联部门信息：
     * <ul>
     *   <li>关联字段：dept_name, dept_location, dept_level</li>
     *   <li>关联不上时使用默认值填充</li>
     * </ul>
     */
    static List<Row> applyJoin(List<Row> rows) {
        System.out.println("--- 6. JOIN 维表关联 ---");

        Map<Integer, DimRecord> dimTable = prepareDimensionTable();

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Row newRow = row.copy();
            Integer deptId = (Integer) newRow.get("department_id");

            DimRecord dim = dimTable.get(deptId);
            if (dim != null) {
                newRow.put("dept_name", dim.deptName());
                newRow.put("dept_location", dim.location());
                newRow.put("dept_level", dim.level());
                System.out.printf("  JOIN: %s → dept=%s (%s, %s)%n",
                        row.get("name_encrypted") != null ? row.get("name_encrypted") : row.get("user_id"),
                        dim.deptName(), dim.location(), dim.level());
            } else {
                // 关联不上时填充默认值
                newRow.put("dept_name", "未知部门");
                newRow.put("dept_location", "未知");
                newRow.put("dept_level", 0);
                System.out.printf("  JOIN: department_id=%d 无匹配，使用默认值%n", deptId);
            }

            result.add(newRow);
        }
        System.out.println();
        return result;
    }


    // ==================== 7. 聚合 AGGREGATE ====================

    /**
     * 聚合计算。
     *
     * <p>按维度字段分组进行聚合：
     * <ul>
     *   <li>GROUP BY: dept_name, dept_location, dept_level</li>
     *   <li>聚合函数: COUNT(人数), SUM(salary), AVG(salary), MAX(salary), MIN(salary)</li>
     * </ul>
     */
    static List<Row> applyAggregate(List<Row> rows) {
        System.out.println("--- 7. AGGREGATE 聚合计算 ---");

        // 按部门分组
        Map<String, List<Row>> grouped = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> (String) row.get("dept_name"),
                        LinkedHashMap::new, Collectors.toList()));

        List<Row> result = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : grouped.entrySet()) {
            String deptName = entry.getKey();
            List<Row> groupRows = entry.getValue();

            long count = groupRows.size();
            long sumSalary = groupRows.stream().mapToLong(r -> (Long) r.get("salary")).sum();
            long avgSalary = count > 0 ? sumSalary / count : 0;
            long maxSalary = groupRows.stream().mapToLong(r -> (Long) r.get("salary")).max().orElse(0);
            long minSalary = groupRows.stream().mapToLong(r -> (Long) r.get("salary")).min().orElse(0);

            // 取第一条记录的维度信息
            Row firstRow = groupRows.get(0);
            Row aggRow = new Row();
            aggRow.put("dept_name", deptName);
            aggRow.put("dept_location", firstRow.get("dept_location"));
            aggRow.put("dept_level", firstRow.get("dept_level"));
            aggRow.put("employee_count", count);
            aggRow.put("total_salary", sumSalary);
            aggRow.put("avg_salary", avgSalary);
            aggRow.put("max_salary", maxSalary);
            aggRow.put("min_salary", minSalary);

            System.out.printf("  GROUP dept=%s: count=%d, sum=%d, avg=%d, max=%d, min=%d%n",
                    deptName, count, sumSalary, avgSalary, maxSalary, minSalary);
            result.add(aggRow);
        }

        System.out.println();
        return result;
    }
}