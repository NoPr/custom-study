package base.mybatis;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 动态 SQL：if/choose/trim/foreach/bind + SqlSource 解析原理
 *
 * <p>手写动态 SQL 引擎简化版，演示：</p>
 * <ol>
 *   <li>SqlSource → DynamicSqlSource vs RawSqlSource 区别</li>
 *   <li>foreach 参数映射（List&lt;Integer&gt; → (1,2,3)）</li>
 *   <li>OGNL 表达式引擎简化版：解析 if test="name != null and name != ''"</li>
 *   <li>SQL 注入防御：#{} 参数化 vs ${} 直接拼接</li>
 * </ol>
 *
 * @author study-tuling
 */
public class DynamicSQLDemo {

    // ============================================================
    // 1. OGNL 表达式引擎简化版
    // ============================================================

    /**
     * 简化版 OGNL 表达式解析器
     * 支持：property, ==, !=, >, <, >=, <=, and, or, null, ''
     *
     * <p>OGNL（Object-Graph Navigation Language）是 MyBatis 使用的表达式语言，
     * 用于在动态 SQL 中访问参数对象属性。</p>
     */
    static class SimpleOgnlEvaluator {

        /** 评估 OGNL 表达式，返回 boolean */
        static boolean evaluate(String expression, Object rootObject) {
            expression = expression.trim();

            // 处理 and / or 连接
            if (expression.contains(" and ")) {
                String[] parts = expression.split(" and ");
                for (String part : parts) {
                    if (!evaluate(part.trim(), rootObject)) {
                        return false;
                    }
                }
                return true;
            }

            if (expression.contains(" or ")) {
                String[] parts = expression.split(" or ");
                for (String part : parts) {
                    if (evaluate(part.trim(), rootObject)) {
                        return true;
                    }
                }
                return false;
            }

            // 处理 !=
            if (expression.contains("!=")) {
                String[] parts = expression.split("!=", 2);
                Object left = resolveValue(parts[0].trim(), rootObject);
                Object right = resolveValue(parts[1].trim(), rootObject);
                return !Objects.equals(left, right);
            }

            // 处理 ==
            if (expression.contains("==")) {
                String[] parts = expression.split("==", 2);
                Object left = resolveValue(parts[0].trim(), rootObject);
                Object right = resolveValue(parts[1].trim(), rootObject);
                return Objects.equals(left, right);
            }

            // 处理 != null
            if (expression.endsWith("!= null")) {
                Object value = resolveValue(
                        expression.substring(0, expression.length() - "!= null".length()).trim(),
                        rootObject);
                return value != null;
            }

            // 处理 != ''（不等于空字符串）
            if (expression.endsWith("!= ''")) {
                Object value = resolveValue(
                        expression.substring(0, expression.length() - "!= ''".length()).trim(),
                        rootObject);
                return value != null && !"".equals(value.toString());
            }

            // 默认：直接判断是否为真（如 name 不为 null 且不为空字符串）
            Object value = resolveValue(expression, rootObject);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return value != null;
        }

        /**
         * 解析属性路径，如 "name"、"user.name"、"list[0].name"
         * 支持点号导航和 Map 取值
         */
        private static Object resolveValue(String propertyPath, Object root) {
            if (root == null) return null;

            // null 字面量
            if ("null".equals(propertyPath)) return null;
            // 空字符串字面量
            if ("''".equals(propertyPath) || "\"\"".equals(propertyPath)) return "";
            // 布尔字面量
            if ("true".equals(propertyPath)) return true;
            if ("false".equals(propertyPath)) return false;

            // 点号分隔的属性路径
            String[] parts = propertyPath.split("\\.");
            Object current = root;
            for (String part : parts) {
                if (current == null) return null;

                // Map 取值
                if (current instanceof Map) {
                    current = ((Map<?, ?>) current).get(part);
                    continue;
                }

                // 反射取值
                try {
                    Field field = findField(current.getClass(), part);
                    if (field != null) {
                        field.setAccessible(true);
                        current = field.get(current);
                    } else {
                        return null;
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            return current;
        }

        /** 在当前类及父类中查找字段 */
        private static Field findField(Class<?> clazz, String name) {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        }
    }

    // ============================================================
    // 2. SqlNode 体系（动态 SQL 节点）
    // ============================================================

    /** SQL 节点接口 */
    interface SqlNode {
        /** 将节点应用到 SQL 构建上下文中 */
        boolean apply(DynamicContext context);
    }

    /** 动态 SQL 上下文：持有 SQL 构建器 */
    static class DynamicContext {
        StringBuilder sqlBuilder = new StringBuilder();
        Object parameterObject;

        DynamicContext(Object parameterObject) {
            this.parameterObject = parameterObject;
        }

        void appendSql(String sql) {
            sqlBuilder.append(sql);
        }

        String getSql() {
            return sqlBuilder.toString();
        }
    }

    /** 静态文本节点 */
    static class StaticTextSqlNode implements SqlNode {
        final String text;

        StaticTextSqlNode(String text) { this.text = text; }

        @Override
        public boolean apply(DynamicContext context) {
            context.appendSql(text);
            return true;
        }
    }

    /** if 节点 */
    static class IfSqlNode implements SqlNode {
        final String test;      // OGNL 表达式
        final SqlNode contents; // 条件成立时输出的内容

        IfSqlNode(String test, SqlNode contents) {
            this.test = test;
            this.contents = contents;
        }

        @Override
        public boolean apply(DynamicContext context) {
            if (SimpleOgnlEvaluator.evaluate(test, context.parameterObject)) {
                System.out.println("      [IfSqlNode] 条件成立 test=\"" + test + "\"");
                return contents.apply(context);
            } else {
                System.out.println("      [IfSqlNode] 条件不成立 test=\"" + test + "\"，跳过");
                return false;
            }
        }
    }

    /** choose/when/otherwise 节点 */
    static class ChooseSqlNode implements SqlNode {
        final List<WhenSqlNode> whenNodes;
        final SqlNode otherwiseNode;

        ChooseSqlNode(List<WhenSqlNode> whenNodes, SqlNode otherwiseNode) {
            this.whenNodes = whenNodes;
            this.otherwiseNode = otherwiseNode;
        }

        @Override
        public boolean apply(DynamicContext context) {
            for (WhenSqlNode when : whenNodes) {
                if (SimpleOgnlEvaluator.evaluate(when.test, context.parameterObject)) {
                    System.out.println("      [ChooseSqlNode] 命中 when test=\"" + when.test + "\"");
                    return when.contents.apply(context);
                }
            }
            if (otherwiseNode != null) {
                System.out.println("      [ChooseSqlNode] 未命中任何 when，执行 otherwise");
                return otherwiseNode.apply(context);
            }
            return false;
        }
    }

    static class WhenSqlNode {
        final String test;
        final SqlNode contents;

        WhenSqlNode(String test, SqlNode contents) {
            this.test = test;
            this.contents = contents;
        }
    }

    /** trim 节点（处理前缀/后缀/移除多余关键字） */
    static class TrimSqlNode implements SqlNode {
        final SqlNode contents;
        final String prefix;            // 前缀，如 WHERE
        final String suffix;            // 后缀
        final List<String> prefixesToOverride; // 需删除的前缀，如 AND | OR

        TrimSqlNode(SqlNode contents, String prefix, String suffix,
                    List<String> prefixesToOverride) {
            this.contents = contents;
            this.prefix = prefix;
            this.suffix = suffix;
            this.prefixesToOverride = prefixesToOverride != null
                    ? prefixesToOverride : List.of();
        }

        @Override
        public boolean apply(DynamicContext context) {
            // 先收集内容到临时构建器
            StringBuilder temp = new StringBuilder();
            DynamicContext childContext = new DynamicContext(context.parameterObject);
            contents.apply(childContext);
            String innerSql = childContext.getSql().trim();

            if (innerSql.isEmpty()) {
                return false;
            }

            // 移除开头多余的前缀（如 AND / OR）
            for (String prefixToRemove : prefixesToOverride) {
                String upperInner = innerSql.toUpperCase();
                String upperPrefix = prefixToRemove.toUpperCase();
                if (upperInner.startsWith(upperPrefix)) {
                    innerSql = innerSql.substring(prefixToRemove.length()).trim();
                    System.out.println("      [TrimSqlNode] 移除了前缀 '" + prefixToRemove + "'");
                    break;
                }
            }

            // 添加前缀和后缀
            String trimmedSql = (prefix != null ? prefix + " " : "")
                    + innerSql
                    + (suffix != null ? " " + suffix : "");
            context.appendSql(trimmedSql);
            System.out.println("      [TrimSqlNode] 处理结果: " + trimmedSql);
            return true;
        }
    }

    /** foreach 节点 */
    static class ForEachSqlNode implements SqlNode {
        final String collection;    // 集合名，如 "list"
        final String item;          // 元素名，如 "item"
        final String open;          // 开始符号，如 "("
        final String close;         // 结束符号，如 ")"
        final String separator;     // 分隔符，如 ","
        final SqlNode contents;     // 内容（含 #{item} 占位符）

        ForEachSqlNode(String collection, String item, String open,
                       String close, String separator, SqlNode contents) {
            this.collection = collection;
            this.item = item;
            this.open = open;
            this.close = close;
            this.separator = separator;
            this.contents = contents;
        }

        @Override
        public boolean apply(DynamicContext context) {
            Object value = SimpleOgnlEvaluator.resolveValue(collection, context.parameterObject);

            if (value == null) {
                System.out.println("      [ForEachSqlNode] 集合为 null，跳过");
                return false;
            }

            Collection<?> iterable;
            if (value instanceof Collection) {
                iterable = (Collection<?>) value;
            } else if (value.getClass().isArray()) {
                iterable = Arrays.asList((Object[]) value);
            } else {
                System.out.println("      [ForEachSqlNode] 不支持的类型: " + value.getClass());
                return false;
            }

            if (iterable.isEmpty()) {
                System.out.println("      [ForEachSqlNode] 集合为空，跳过");
                return false;
            }

            System.out.println("      [ForEachSqlNode] 遍历 " + collection
                    + " (" + iterable.size() + " 个元素)");

            context.appendSql(open);
            int count = 0;
            for (Object element : iterable) {
                if (count > 0) {
                    context.appendSql(separator);
                }

                // 为每个元素创建子上下文，注入 item 变量
                DynamicContext itemContext = new DynamicContext(context.parameterObject);
                // 将 item 和 index 注入到参数对象中（简化：用 Map 包装）
                Map<String, Object> enrichedParams = new HashMap<>();
                enrichedParams.put(item, element);
                enrichedParams.put("index", count);
                itemContext.parameterObject = enrichedParams;
                contents.apply(itemContext);
                String itemSql = itemContext.getSql();

                // 替换 #{item} 为实际值（模拟参数绑定）
                itemSql = itemSql.replace("#{" + item + "}", String.valueOf(element));

                context.appendSql(itemSql);
                count++;
            }
            context.appendSql(close);

            System.out.println("      [ForEachSqlNode] 生成 SQL: " + open + "..." + close);
            return true;
        }
    }

    // ============================================================
    // 3. SqlSource：DynamicSqlSource vs RawSqlSource
    // ============================================================

    /**
     * SqlSource 接口：封装 SQL 语句
     *
     * <p>两种实现的核心区别：</p>
     * <ul>
     *   <li>RawSqlSource：静态 SQL（无动态标签），初始化时直接编译 #{} → ?</li>
     *   <li>DynamicSqlSource：动态 SQL（含 if/foreach 等），每次调用时运行时解析</li>
     * </ul>
     */
    interface SqlSource {
        BoundSql getBoundSql(Object parameterObject, DynamicSqlNode rootNode);
    }

    /** 静态 SQL：初始化时编译 */
    static class RawSqlSource implements SqlSource {
        final String compiledSql;

        RawSqlSource(String sql, DynamicSqlNode rootNode) {
            this.compiledSql = sql;
            System.out.println("[RawSqlSource] 初始化时编译 SQL: " + sql);
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject, DynamicSqlNode rootNode) {
            return new BoundSql(compiledSql, List.of(), parameterObject);
        }
    }

    /** 动态 SQL：运行时解析 */
    static class DynamicSqlSource implements SqlSource {
        final DynamicSqlNode rootNode;

        DynamicSqlSource(DynamicSqlNode rootNode) {
            this.rootNode = rootNode;
            System.out.println("[DynamicSqlSource] 持有 SqlNode 树，运行时解析");
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject, DynamicSqlNode rootNode) {
            DynamicContext context = new DynamicContext(parameterObject);
            this.rootNode.apply(context);
            String finalSql = context.getSql();
            System.out.println("[DynamicSqlSource] 运行时解析完成: " + finalSql);
            return new BoundSql(finalSql, List.of(), parameterObject);
        }
    }

    /** 动态 SQL 根节点（组合多个 SqlNode） */
    static class DynamicSqlNode implements SqlNode {
        final List<SqlNode> children = new ArrayList<>();

        void addChild(SqlNode node) {
            children.add(node);
        }

        @Override
        public boolean apply(DynamicContext context) {
            for (SqlNode child : children) {
                child.apply(context);
            }
            return true;
        }
    }

    /** BoundSql */
    static class BoundSql {
        final String sql;
        final List<String> parameterMappings;
        final Object parameterObject;

        BoundSql(String sql, List<String> parameterMappings, Object parameterObject) {
            this.sql = sql;
            this.parameterMappings = parameterMappings;
            this.parameterObject = parameterObject;
        }

        String getSql() { return sql; }
    }

    // ============================================================
    // 4. 演示方法
    // ============================================================

    static class QueryParams {
        String name;
        Integer minAge;
        Integer maxAge;
        String orderBy;
        List<Long> ids;
        String keyword;

        QueryParams name(String n) { this.name = n; return this; }
        QueryParams minAge(Integer a) { this.minAge = a; return this; }
        QueryParams maxAge(Integer a) { this.maxAge = a; return this; }
        QueryParams orderBy(String o) { this.orderBy = o; return this; }
        QueryParams ids(List<Long> ids) { this.ids = ids; return this; }
        QueryParams keyword(String k) { this.keyword = k; return this; }
    }

    static void demoIfWhere() {
        System.out.println("==================== if + where 动态 SQL 演示 ====================");
        System.out.println();

        // 构建 SqlNode 树
        SqlNode staticPrefix = new StaticTextSqlNode("SELECT * FROM user");

        List<SqlNode> whereChildren = new ArrayList<>();
        whereChildren.add(new IfSqlNode("name != null and name != ''",
                new StaticTextSqlNode("AND name = #{name} ")));
        whereChildren.add(new IfSqlNode("minAge != null",
                new StaticTextSqlNode("AND age >= #{minAge} ")));
        whereChildren.add(new IfSqlNode("maxAge != null",
                new StaticTextSqlNode("AND age <= #{maxAge} ")));

        SqlNode whereContents = new DynamicSqlNode();
        for (SqlNode child : whereChildren) {
            ((DynamicSqlNode) whereContents).addChild(child);
        }

        SqlNode whereNode = new TrimSqlNode(whereContents, "WHERE", null,
                List.of("AND", "OR"));

        DynamicSqlNode root = new DynamicSqlNode();
        root.addChild(staticPrefix);
        root.addChild(whereNode);

        // 场景 1：所有条件都传
        System.out.println("--- 场景 1: name='张三', minAge=20, maxAge=40 ---");
        QueryParams p1 = new QueryParams().name("张三").minAge(20).maxAge(40);
        processSql(root, p1);
        System.out.println();

        // 场景 2：只传部分条件
        System.out.println("--- 场景 2: name=null, minAge=20, maxAge=null ---");
        QueryParams p2 = new QueryParams().minAge(20);
        processSql(root, p2);
        System.out.println();

        // 场景 3：所有条件为空
        System.out.println("--- 场景 3: 所有条件为空 name=null, minAge=null ---");
        QueryParams p3 = new QueryParams();
        processSql(root, p3);
        System.out.println();
    }

    static void demoChooseWhen() {
        System.out.println("==================== choose/when/otherwise 演示 ====================");
        System.out.println();

        DynamicSqlNode root = new DynamicSqlNode();
        root.addChild(new StaticTextSqlNode("SELECT * FROM user WHERE "));

        List<WhenSqlNode> whenNodes = List.of(
                new WhenSqlNode("name != null", new StaticTextSqlNode("name = #{name}")),
                new WhenSqlNode("minAge != null", new StaticTextSqlNode("age >= #{minAge}")),
                new WhenSqlNode("keyword != null",
                        new StaticTextSqlNode("name LIKE CONCAT('%', #{keyword}, '%')"))
        );
        SqlNode otherwise = new StaticTextSqlNode("1 = 1"); // 兜底条件

        root.addChild(new ChooseSqlNode(whenNodes, otherwise));

        // 场景 1：name 有值，优先匹配
        System.out.println("--- 场景 1: name='张三' ---");
        processSql(root, new QueryParams().name("张三"));
        System.out.println();

        // 场景 2：name 为空，匹配 keyword
        System.out.println("--- 场景 2: keyword='张' ---");
        processSql(root, new QueryParams().keyword("张"));
        System.out.println();

        // 场景 3：都不满足，走 otherwise
        System.out.println("--- 场景 3: 无匹配，走 otherwise ---");
        processSql(root, new QueryParams());
        System.out.println();
    }

    static void demoForeach() {
        System.out.println("==================== foreach 动态 SQL 演示 ====================");
        System.out.println();

        // 构建 foreach 节点
        SqlNode foreachContent = new StaticTextSqlNode("#{item}");
        ForEachSqlNode foreachNode = new ForEachSqlNode(
                "ids", "item", "(", ")", ",", foreachContent);

        DynamicSqlNode root = new DynamicSqlNode();
        root.addChild(new StaticTextSqlNode("SELECT * FROM user WHERE id IN "));
        root.addChild(foreachNode);

        // 场景 1：ids = [1, 2, 3]
        System.out.println("--- 场景 1: ids = [1, 2, 3] ---");
        Map<String, Object> p1 = new HashMap<>();
        p1.put("ids", List.of(1L, 2L, 3L));
        processSql(root, p1);
        System.out.println();

        // 场景 2：ids = [10, 20, 30, 40, 50]
        System.out.println("--- 场景 2: ids = [10, 20, 30, 40, 50] ---");
        Map<String, Object> p2 = new HashMap<>();
        p2.put("ids", List.of(10L, 20L, 30L, 40L, 50L));
        processSql(root, p2);
        System.out.println();
    }

    static void demoSQLInjectionDefense() {
        System.out.println("==================== SQL 注入防御：代码证明 ====================");
        System.out.println();

        // 模拟动态 SQL 中的 #{keyword}（安全）
        System.out.println("--- #{keyword} 参数化查询（安全） ---");
        String safeSql = "SELECT * FROM user WHERE name LIKE CONCAT('%', #{keyword}, '%')";
        System.out.println("原始 SQL: " + safeSql);
        System.out.println("恶意输入 keyword: ' OR '1'='1");
        System.out.println("实际执行: PreparedStatement, 参数化绑定");
        System.out.println("  pstmt.setString(1, \"' OR '1'='1\");");
        System.out.println("  → 数据库将其作为普通字符串处理，不会执行恶意 SQL");
        System.out.println();

        // 模拟动态 SQL 中的 ${keyword}（不安全）
        System.out.println("--- ${keyword} 字符串拼接（不安全） ---");
        String unsafeSql = "SELECT * FROM user WHERE name LIKE '%${keyword}%'";
        System.out.println("原始 SQL: " + unsafeSql);
        String maliciousInput = "' OR '1'='1";
        String result = unsafeSql.replace("${keyword}", maliciousInput);
        System.out.println("恶意输入: " + maliciousInput);
        System.out.println("拼接结果: " + result);
        System.out.println("  → 条件永远为真，泄漏全部数据！");
        System.out.println();

        String maliciousInput2 = "'; DROP TABLE user; --";
        String result2 = unsafeSql.replace("${keyword}", maliciousInput2);
        System.out.println("恶意输入: " + maliciousInput2);
        System.out.println("拼接结果: " + result2);
        System.out.println("  → 可能导致删表！");
        System.out.println();
    }

    static void demoOGNL() {
        System.out.println("==================== OGNL 表达式评估演示 ====================");
        System.out.println();

        QueryParams params = new QueryParams().name("张三").minAge(20);

        String[][] tests = {
                {"name != null and name != ''", String.valueOf(true)},
                {"name != null",                        String.valueOf(true)},
                {"name == null",                        String.valueOf(false)},
                {"minAge != null",                      String.valueOf(true)},
                {"maxAge != null",                      String.valueOf(false)},
                {"keyword != null",                     String.valueOf(false)},
        };

        System.out.println("参数: name='张三', minAge=20, maxAge=null, keyword=null");
        System.out.printf("| %-40s | %-8s |%n", "OGNL 表达式", "结果");
        System.out.println("|------------------------------------------|----------|");
        for (String[] test : tests) {
            boolean result = SimpleOgnlEvaluator.evaluate(test[0], params);
            System.out.printf("| %-40s | %-8s |%n", test[0], result);
        }
        System.out.println();

        String[][] xmlExamples = {
                {"<if test=\"name != null and name != ''\">", "过滤空字符串和 null"},
                {"<if test=\"minAge != null\">", "过滤 null 值"},
                {"<choose><when test=\"...\">", "多条件分支"},
                {"<trim prefix=\"WHERE\" prefixOverrides=\"AND|OR\">", "智能 WHERE"},
                {"<foreach collection=\"list\" item=\"item\" separator=\",\">", "IN 条件构建"},
                {"<set> for UPDATE", "动态 SET 子句"},
                {"<bind name=\"pattern\" value=\"'%' + name + '%'\">", "创建新变量"},
        };

        System.out.println("MyBatis 常用动态标签:");
        System.out.printf("| %-55s | %-20s |%n", "标签", "用途");
        System.out.println("|---------------------------------------------------------|----------------------|");
        for (String[] row : xmlExamples) {
            System.out.printf("| %-55s | %-20s |%n", row[0], row[1]);
        }
        System.out.println();
    }

    private static void processSql(DynamicSqlNode rootNode, Object params) {
        DynamicContext context = new DynamicContext(params);
        rootNode.apply(context);
        System.out.println("  最终 SQL: " + context.getSql());
    }

    // ============================================================
    // 5. 入口
    // ============================================================

    public static void main(String[] args) {
        demoIfWhere();
        demoChooseWhen();
        demoForeach();
        demoOGNL();
        demoSQLInjectionDefense();
    }
}