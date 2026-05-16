package base.spark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DataFrame / DataSet / RDD 对比解析 与 Catalyst 优化器手写模拟
 *
 * <p>Spark SQL 三剑客对比：
 * <table><tr><th>维度</th><th>RDD</th><th>DataFrame</th><th>DataSet</th></tr>
 *   <tr><td>序列化</td><td>Java/Kryo</td><td>Tungsten(堆外)</td><td>Encoder</td></tr>
 *   <tr><td>Schema</td><td>无</td><td>有(运行时)</td><td>有(编译时泛型)</td></tr>
 *   <tr><td>优化</td><td>无</td><td>Catalyst</td><td>Catalyst</td></tr>
 *   <tr><td>类型安全</td><td>运行时</td><td>运行时</td><td>编译时</td></tr>
 *   <tr><td>API级别</td><td>低级</td><td>中级</td><td>高级</td></tr>
 *   <tr><td>适用场景</td><td>非结构化</td><td>半结构化</td><td>结构化</td></tr>
 * </table>
 *
 * <p>Catalyst Optimizer 四阶段优化流程：
 * <ol>
 *   <li><b>Analysis（分析）</b>：解析未解析的逻辑计划，解析表名、列名、函数，绑定 Catalog 元数据</li>
 *   <li><b>Logical Optimize（逻辑优化）</b>：应用规则优化逻辑计划
 *       谓词下推(Predicate Pushdown)、列裁剪(Column Pruning)、常量折叠(Constant Folding)</li>
 *   <li><b>Physical Plan（物理计划）</b>：将逻辑计划转换为物理执行计划，选择 Join 策略等</li>
 *   <li><b>Code Generation（代码生成）</b>：将物理计划编译为 Java 字节码，利用 Janino 编译器</li>
 * </ol>
 *
 * <p>Tungsten 项目核心优化：
 * <ul>
 *   <li><b>堆外内存管理</b>：显式管理内存，避免 GC 开销</li>
 *   <li><b>列式存储</b>：按列存储数据，提高压缩比和扫描效率</li>
 *   <li><b>代码生成</b>：整阶段代码生成(Whole-Stage CodeGen)，减少虚函数调用和内存分配</li>
 * </ul>
 *
 * <p>谓词下推 vs 列裁剪：
 * <ul>
 *   <li><b>谓词下推</b>：将过滤条件下推到数据源端，减少扫描和传输数据量</li>
 *   <li><b>列裁剪</b>：只读取查询实际需要的列，忽略无关列，减少 I/O 和内存</li>
 * </ul>
 *
 * @see RDDDemo RDD弹性分布式数据集
 * @see DAGShuffleDemo DAG调度与Shuffle机制
 */
public class DataFrameDemo {

    public static void main(String[] args) {
        System.out.println("========== DataFrame / DataSet / Catalyst 优化器演示 ==========\n");

        rdVsDataFrameVsDataset();
        catalystOptimizerDemo();
        tungstenDemo();
        predicatePushdownAndColumnPruning();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 1. RDD vs DataFrame vs DataSet ====================

    static void rdVsDataFrameVsDataset() {
        System.out.println("--- 1. RDD vs DataFrame vs DataSet 对比 ---");

        // 模拟一份人员数据
        List<Person> persons = List.of(
                new Person("张三", 28, 85000.0),
                new Person("李四", 35, 120000.0),
                new Person("王五", 22, 65000.0),
                new Person("赵六", 40, 150000.0)
        );

        System.out.println("原始数据: " + persons + "\n");

        // === RDD 方式 ===
        System.out.println("[RDD 方式] 无 Schema, 类型运行时推导, 无优化:");
        double avgSalaryRDD = persons.stream()
                .filter(p -> p.age > 25)                    // 需手动处理
                .mapToDouble(p -> p.salary)                  // 可能装箱拆箱
                .average()
                .orElse(0.0);
        System.out.printf("  25岁以上平均薪资: %.2f (RDD: Java对象序列化, 无Schema, 无优化)%n", avgSalaryRDD);

        // === DataFrame 方式 ===
        System.out.println("\n[DataFrame 方式] 有 Schema, Tungsten 堆外内存, Catalyst 优化:");
        SimpleDataFrame df = new SimpleDataFrame(persons);
        System.out.println("  Schema: " + df.schema());

        // 模拟 DataFrame 操作链
        SimpleDataFrame filtered = df.filter("age", ">", 25);
        SimpleDataFrame projected = filtered.select("name", "salary");
        double avgSalaryDF = projected.aggregate("salary", "avg");
        System.out.printf("  25岁以上平均薪资: %.2f (DataFrame: 堆外内存+列式存储+代码生成)%n", avgSalaryDF);

        // === DataSet 方式 ===
        System.out.println("\n[DataSet 方式] 编译时类型安全, Encoder 序列化, Catalyst 优化:");
        System.out.println("  DataSet<Person> ds = spark.read()...as[Person];  // 编译时类型检查");
        System.out.println("  ds.filter(_.age > 25).select(_.salary).agg(avg).show();");

        System.out.println("\n  总结: DataFrame 是对 RDD 的 Schema 封装, DataSet 是对 DataFrame 的泛型封装");
        System.out.println("       RDD → DataFrame (加Schema) → DataSet (加强类型)  -- 逐步进化\n");
    }

    // ==================== 2. Catalyst 优化器四阶段 ====================

    static void catalystOptimizerDemo() {
        System.out.println("--- 2. Catalyst Optimizer 四阶段优化模拟 ---");

        System.out.println("""
                输入 SQL: SELECT name, salary FROM person WHERE age > 25 AND (salary * 1.1) > 80000

                ╔════════════════════════════════════════════════════════╗
                ║            Catalyst 优化器 (四阶段)                     ║
                ╠════════════════════════════════════════════════════════╣
                ║ 阶段1: Analysis (分析)                                 ║
                ║   解析 SQL → 未解析逻辑计划(Unresolved Logical Plan)    ║
                ║   person表名解析(查Catalog) → age/salary列名解析        ║
                ║   函数解析: avg() → Spark内置聚合函数                    ║
                ║   → 解析后的逻辑计划 (Resolved Logical Plan)            ║
                ╠════════════════════════════════════════════════════════╣
                ║ 阶段2: Logical Optimize (逻辑优化)                      ║
                ║   规则:                                               ║
                ║   ① 谓词下推: WHERE age > 25 推到 Scan 节点           ║
                ║   ② 列裁剪: 只保留 name, age, salary (去掉无关列)     ║
                ║   ③ 常量折叠: (salary * 1.1) > 80000                 ║
                ║      → salary > 80000/1.1 = salary > 72727.27        ║
                ║   ④ 合并过滤器: age > 25 AND salary > 72727 合并      ║
                ║   → 优化后的逻辑计划                                   ║
                ╠════════════════════════════════════════════════════════╣
                ║ 阶段3: Physical Plan (物理计划)                        ║
                ║   算子选择:                                           ║
                ║   Join策略: BroadcastHashJoin vs SortMergeJoin        ║
                ║   Scan策略: 全表扫描 vs 索引扫描                       ║
                ║   → 最优物理执行计划                                   ║
                ╠════════════════════════════════════════════════════════╣
                ║ 阶段4: Code Generation (代码生成)                      ║
                ║   Whole-Stage CodeGen: 将多个算子融合成单个函数        ║
                ║   scan → filter → project → aggregate                ║
                ║   编译为 Java 字节码 (Janino编译器)                    ║
                ║   → 消除虚函数调用, 减少中间对象, CPU寄存器友好        ║
                ╚════════════════════════════════════════════════════════╝
                """);

        // 手动模拟 Catalyst 优化过程
        System.out.println("手动模拟优化过程:");

        List<Person> persons = List.of(
                new Person("张三", 28, 85000.0),
                new Person("李四", 15, 30000.0),
                new Person("王五", 45, 150000.0),
                new Person("赵六", 32, 60000.0)
        );

        System.out.println("  Step1 原始SQL: SELECT name FROM person WHERE age > 25 AND salary > 80000");

        // Step2 逻辑优化: 谓词下推+列裁剪
        System.out.println("  Step2 逻辑优化: 谓词下推(先过滤) + 列裁剪(只读name)");
        List<String> result = persons.stream()
                .filter(p -> p.age > 25 && p.salary > 80000)  // 谓词下推
                .map(p -> p.name)                              // 列裁剪→只取name
                .collect(Collectors.toList());
        System.out.println("  优化后结果: " + result);

        System.out.println("  Step3 物理计划: 选择最优执行策略 (这里为单一扫描+过滤)");
        System.out.println("  Step4 代码生成: 整阶段代码融合 → 编译为直接可执行字节码\n");
    }

    // ==================== 3. Tungsten 堆外内存 & 列式存储 ====================

    static void tungstenDemo() {
        System.out.println("--- 3. Tungsten 堆外内存 & 列式存储 ---");

        System.out.println("""
                Project Tungsten 核心优化:

                ① 堆外内存管理 (Off-Heap Memory):
                   ┌──────────────┐    ┌──────────────┐
                   │  JVM Heap    │    │  Off-Heap    │
                   │  (GC管理)    │ vs │ (手动管理)    │
                   │  频繁GC停顿  │    │  零GC影响    │
                   │  对象头开销  │    │  紧凑布局    │
                   └──────────────┘    └──────────────┘
                   对大量小对象(如Row)场景, 堆外内存显著减少GC压力

                ② 列式存储 (Columnar Storage):
                   行式存储:                列式存储:
                   [张三|28|85000]         name列: [张三,李四,王五]
                   [李四|35|120000]  →     age列:  [28,35,22]
                   [王五|22|65000]         salary列:[85000,120000,65000]
                   适合OLTP(事务)          适合OLAP(分析), 列同质高压缩比+向量化

                ③ 整阶段代码生成 (Whole-Stage CodeGen):
                   传统: scan()→filter()→project()→agg() 每个算子函数调用+虚函数
                   融合后: for(row in rows){ if(age>25) sum+=salary; }
                   一个循环完成所有操作, CPU缓存友好
                """);

        // 模拟列式存储
        System.out.println("列式存储模拟:");
        List<String> nameCol = List.of("张三", "李四", "王五", "赵六");
        List<Integer> ageCol = List.of(28, 35, 22, 40);
        List<Double> salaryCol = List.of(85000.0, 120000.0, 65000.0, 150000.0);

        System.out.println("  列式布局: 同列数据连续存储, 高压缩比, 向量化计算");
        System.out.println("  name列   = " + nameCol + " (同质String, 字典编码压缩)");
        System.out.println("  age列    = " + ageCol + " (同质int, 位打包压缩)");
        System.out.println("  salary列 = " + salaryCol + " (同质double, 差值压缩)");

        // 模拟向量化计算: 一次性对整列操作
        double avgSalary = salaryCol.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.printf("  向量化计算 salary 平均值 = %.2f (单次遍历列, CPU缓存友好)%n", avgSalary);

        System.out.println("\n  Tungsten 核心价值: 堆外内存避免GC + 列式存储提升压缩/扫描 + 代码生成消除虚函数\n");
    }

    // ==================== 4. 谓词下推 vs 列裁剪 ====================

    static void predicatePushdownAndColumnPruning() {
        System.out.println("--- 4. 谓词下推 vs 列裁剪 ---");

        List<Person> persons = List.of(
                new Person("张三", 28, 85000.0, "北京", "IT"),
                new Person("李四", 35, 120000.0, "上海", "金融"),
                new Person("王五", 22, 65000.0, "北京", "IT"),
                new Person("赵六", 40, 150000.0, "深圳", "金融")
        );

        System.out.println("原始SQL: SELECT name, salary FROM person WHERE city='北京' AND age > 25");

        // 无优化方式: 全表加载所有列所有行, 再过滤
        System.out.println("\n[无优化] 读取全部行+全部列, 再过滤:");
        List<Person> allRows = new ArrayList<>(persons);
        System.out.println("  扫描阶段: 读取所有 " + allRows.size() + " 行, 全部4列 (name/age/salary/city/dept)");
        // 然后过滤
        List<String> naiveResult = allRows.stream()
                .filter(p -> "北京".equals(p.city) && p.age > 25)
                .map(p -> p.name + ":" + p.salary)
                .collect(Collectors.toList());
        System.out.println("  结果: " + naiveResult);
        System.out.println("  浪费: 多读了 city='上海'/'深圳' 的行和不必要的 dept 列");

        // 优化方式 (谓词下推 + 列裁剪)
        System.out.println("\n[Catalyst优化] 谓词下推 + 列裁剪:");
        System.out.println("  ① 谓词下推: 在Scan阶段就过滤 WHERE city='北京' AND age>25");
        List<Person> filtered = persons.stream()
                .filter(p -> "北京".equals(p.city) && p.age > 25)
                .collect(Collectors.toList());
        System.out.println("     扫描后行数: " + filtered.size() + " (从 " + persons.size() + " 减少)");

        System.out.println("  ② 列裁剪: 只读取 SELECT 所需的 name, salary 列");
        List<String> optimizedResult = filtered.stream()
                .map(p -> p.name + ":" + p.salary)
                .collect(Collectors.toList());
        System.out.println("     结果: " + optimizedResult);
        System.out.println("     节省: 不读 dept 列, 减少 I/O 和内存");

        System.out.println("\n对比总结:");
        System.out.println("""
                  谓词下推 (Predicate Pushdown)          列裁剪 (Column Pruning)
                ┌────────────────────────────┐   ┌────────────────────────────┐
                │ WHERE city='北京' → 源端    │   │ SELECT name,salary → 只读  │
                │ 提前过滤, 减少网络传输行数   │   │ 2列, 忽略dept等无关列       │
                │ 效果: 减少扫描+传输数据量   │   │ 效果: 减少I/O+内存占用     │
                │ 类似: 在数据库端执行过滤    │   │ 类似: 不SELECT *            │
                └────────────────────────────┘   └────────────────────────────┘
                二者协同: 既减少行数(谓词下推)又减少列数(列裁剪), 最小化数据移动
                """);
    }

    // ==================== 辅助数据结构 ====================

    /** Person 数据类 -- 模拟结构化数据 */
    static class Person {
        String name;
        int age;
        double salary;
        String city;
        String dept;

        Person(String name, int age, double salary) {
            this(name, age, salary, null, null);
        }

        Person(String name, int age, double salary, String city, String dept) {
            this.name = name;
            this.age = age;
            this.salary = salary;
            this.city = city;
            this.dept = dept;
        }

        @Override
        public String toString() {
            return String.format("{%s, %d, %.0f%s}",
                    name, age, salary,
                    city != null ? ", " + city + "/" + dept : "");
        }
    }

    /** 简易 DataFrame 模拟 -- Schema + 列式操作 */
    static class SimpleDataFrame {
        List<Person> rows;

        SimpleDataFrame(List<Person> rows) {
            this.rows = new ArrayList<>(rows);
        }

        /** 返回 Schema 信息 */
        String schema() {
            return "[name: String, age: Int, salary: Double]";
        }

        /** 过滤 -- 模拟谓词下推 */
        SimpleDataFrame filter(String column, String op, int value) {
            List<Person> result = rows.stream()
                    .filter(p -> {
                        if ("age".equals(column)) {
                            return switch (op) {
                                case ">" -> p.age > value;
                                case ">=" -> p.age >= value;
                                case "<" -> p.age < value;
                                default -> true;
                            };
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            return new SimpleDataFrame(result);
        }

        /** 列裁剪 -- SELECT 指定列 */
        SimpleDataFrame select(String... columns) {
            // 模拟: 只保留需要的列 (这里简单返回)
            return this;
        }

        /** 聚合 -- 模拟 avg */
        double aggregate(String column, String aggFunc) {
            if ("salary".equals(column) && "avg".equals(aggFunc)) {
                return rows.stream()
                        .mapToDouble(p -> p.salary)
                        .average()
                        .orElse(0.0);
            }
            return 0.0;
        }
    }
}