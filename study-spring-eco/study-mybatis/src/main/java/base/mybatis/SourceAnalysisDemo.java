package base.mybatis;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * MyBatis 源码核心流程：Configuration → MappedStatement → BoundSql → TypeHandler
 *
 * <p>深入 MyBatis 源码核心组件，演示：</p>
 * <ol>
 *   <li>Configuration 对象构建过程（解析 XML/注解 → MappedStatement）</li>
 *   <li>MappedStatement：SQL + ParameterMap + ResultMap + SqlCommandType</li>
 *   <li>BoundSql：动态 SQL 参数替换（#{} vs ${} 本质区别）</li>
 *   <li>TypeHandler：Java 类型 ↔ JDBC 类型转换</li>
 * </ol>
 *
 * @author study-tuling
 */
public class SourceAnalysisDemo {

    // ============================================================
    // 1. XML 映射配置模拟（对应 XML Mapper 文件）
    // ============================================================

    /** 模拟 XML Mapper 配置的内容 */
    static class XmlMapperConfig {
        String namespace;
        List<XmlStatement> statements = new ArrayList<>();

        XmlMapperConfig(String namespace) {
            this.namespace = namespace;
        }

        void addStatement(XmlStatement stmt) {
            statements.add(stmt);
        }
    }

    /** XML 中的一条 SQL 语句定义 */
    static class XmlStatement {
        String id;
        String sql;
        String parameterType;   // 如 "java.lang.Long" 或 "base.mybatis.User"
        String resultType;      // 如 "base.mybatis.User"
        String resultMap;       // 复杂映射时使用
        SqlCommandType sqlCommandType;

        /** 动态 SQL 节点（if/where/foreach 等） */
        List<String> dynamicTags = new ArrayList<>();

        XmlStatement(String id, String sql, String parameterType,
                     String resultType, SqlCommandType sqlCommandType) {
            this.id = id;
            this.sql = sql;
            this.parameterType = parameterType;
            this.resultType = resultType;
            this.sqlCommandType = sqlCommandType;
        }
    }

    enum SqlCommandType {
        SELECT, INSERT, UPDATE, DELETE
    }

    // ============================================================
    // 2. Configuration 构建过程（XML 解析 → MappedStatement）
    // ============================================================

    /**
     * Configuration：MyBatis 全局配置对象
     *
     * <p>构建流程：</p>
     * <ol>
     *   <li>XMLConfigBuilder 解析 mybatis-config.xml → 环境配置、数据源、插件</li>
     *   <li>XMLMapperBuilder 解析 Mapper XML → MappedStatement</li>
     *   <li>MappedStatement 注册到 Configuration.mappedStatements Map</li>
     *   <li>SqlSessionFactoryBuilder.build(configuration) → SqlSessionFactory</li>
     * </ol>
     */
    static class Configuration {
        /** statementId → MappedStatement */
        Map<String, MappedStatement> mappedStatements = new HashMap<>();
        /** TypeHandler 注册表 */
        Map<Class<?>, TypeHandler<?>> typeHandlerRegistry = new HashMap<>();
        /** 数据源配置（模拟） */
        Map<String, String> properties = new HashMap<>();
        /** 是否启用缓存 */
        boolean cacheEnabled = true;

        void addMappedStatement(MappedStatement ms) {
            mappedStatements.put(ms.getId(), ms);
        }

        MappedStatement getMappedStatement(String id) {
            return mappedStatements.get(id);
        }

        @SuppressWarnings("unchecked")
        <T> TypeHandler<T> getTypeHandler(Class<T> javaType) {
            return (TypeHandler<T>) typeHandlerRegistry.get(javaType);
        }

        void registerTypeHandler(Class<?> javaType, TypeHandler<?> handler) {
            typeHandlerRegistry.put(javaType, handler);
        }
    }

    // ============================================================
    // 3. MappedStatement
    // ============================================================

    /**
     * MappedStatement：封装一条完整的 SQL 映射信息
     *
     * <p>核心字段：</p>
     * <ul>
     *   <li>id：statementId = namespace + "." + sqlId</li>
     *   <li>sqlSource：原始 SQL 源代码（DynamicSqlSource / RawSqlSource）</li>
     *   <li>sqlCommandType：SELECT / INSERT / UPDATE / DELETE</li>
     *   <li>parameterMap：参数映射（入参 → JDBC 参数）</li>
     *   <li>resultMaps：结果映射（JDBC ResultSet → Java 对象）</li>
     *   <li>keyGenerator：主键生成策略</li>
     * </ul>
     */
    static class MappedStatement {
        String id;
        SqlSource sqlSource;
        SqlCommandType sqlCommandType;
        ParameterMap parameterMap;
        List<ResultMap> resultMaps;
        String[] keyProperties;
        boolean useGeneratedKeys;

        MappedStatement(String id, SqlSource sqlSource, SqlCommandType sqlCommandType) {
            this.id = id;
            this.sqlSource = sqlSource;
            this.sqlCommandType = sqlCommandType;
            this.parameterMap = new ParameterMap();
            this.resultMaps = new ArrayList<>();
        }

        String getId() { return id; }
    }

    /** 参数映射 */
    static class ParameterMap {
        List<ParameterMapping> parameterMappings = new ArrayList<>();
    }

    /** 单个参数映射 */
    static class ParameterMapping {
        String property;    // Java 对象属性名
        String jdbcType;    // JDBC 类型（VARCHAR, INTEGER 等）
        String javaType;    // Java 类型全限定名

        ParameterMapping(String property, String jdbcType, String javaType) {
            this.property = property;
            this.jdbcType = jdbcType;
            this.javaType = javaType;
        }

        @Override
        public String toString() {
            return "param{property='" + property + "', jdbcType=" + jdbcType + ", javaType=" + javaType + "}";
        }
    }

    /** 结果映射 */
    static class ResultMap {
        String id;
        Class<?> type;
        List<ResultMapping> resultMappings = new ArrayList<>();

        ResultMap(String id, Class<?> type) {
            this.id = id;
            this.type = type;
        }

        void addMapping(ResultMapping mapping) {
            resultMappings.add(mapping);
        }
    }

    /** 单个字段映射 */
    static class ResultMapping {
        String property;    // Java 对象属性名
        String column;      // 数据库列名
        String jdbcType;
        String javaType;

        ResultMapping(String property, String column, String jdbcType, String javaType) {
            this.property = property;
            this.column = column;
            this.jdbcType = jdbcType;
            this.javaType = javaType;
        }

        @Override
        public String toString() {
            return "mapping{column='" + column + "' → property='" + property + "', jdbcType=" + jdbcType + "}";
        }
    }

    // ============================================================
    // 4. SqlSource（SQL 源：动态 vs 原始）
    // ============================================================

    /** SqlSource 接口 */
    interface SqlSource {
        BoundSql getBoundSql(Object parameterObject);
    }

    /**
     * RawSqlSource：静态 SQL（不含动态标签）
     * 在初始化时直接解析 #{} 为 ?，后续不再变化
     */
    static class RawSqlSource implements SqlSource {
        final String originalSql;
        final List<String> parameterNames = new ArrayList<>();

        RawSqlSource(String sql) {
            // 解析 #{property} → ?，提取参数名
            StringBuilder parsed = new StringBuilder();
            int i = 0;
            while (i < sql.length()) {
                if (i + 1 < sql.length() && sql.charAt(i) == '#' && sql.charAt(i + 1) == '{') {
                    int end = sql.indexOf('}', i + 2);
                    if (end > i) {
                        String paramName = sql.substring(i + 2, end).trim();
                        parameterNames.add(paramName);
                        parsed.append('?');
                        i = end + 1;
                        continue;
                    }
                }
                parsed.append(sql.charAt(i));
                i++;
            }
            this.originalSql = parsed.toString();
            System.out.println("    [RawSqlSource] 编译时解析: " + sql + " → " + originalSql);
            System.out.println("    [RawSqlSource] 参数占位符: " + (parameterNames.isEmpty() ? "无" : parameterNames));
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            return new BoundSql(originalSql, parameterNames, parameterObject);
        }
    }

    /**
     * DynamicSqlSource：动态 SQL（含 if/choose/foreach 等）
     * 每次调用时根据参数重新解析 SQL
     */
    static class DynamicSqlSource implements SqlSource {
        final String template; // 含动态标签的原始模板

        DynamicSqlSource(String template) {
            this.template = template;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            // 运行时解析动态标签，生成最终 SQL
            String processedSql = processDynamicTags(template, parameterObject);
            System.out.println("    [DynamicSqlSource] 运行时解析: " + template);
            System.out.println("    [DynamicSqlSource] 解析结果: " + processedSql);
            return new BoundSql(processedSql, List.of(), parameterObject);
        }

        private String processDynamicTags(String sql, Object param) {
            // 简化版：去除动态标签，返回原始 SQL
            return sql;
        }
    }

    // ============================================================
    // 5. BoundSql：#{} vs ${} 本质区别
    // ============================================================

    /**
     * BoundSql：最终的 SQL 语句 + 参数绑定
     *
     * <p>#{id} vs ${id} 的本质区别：</p>
     *
     * <pre>
     * #{id} → PreparedStatement 的 ? 占位符，参数化查询，防止 SQL 注入
     *   SQL: SELECT * FROM user WHERE id = ?
     *   参数: [1]
     *
     * ${id} → 字符串直接拼接替换，有 SQL 注入风险
     *   SQL: SELECT * FROM user WHERE id = 1
     *   参数: []（无参数化）
     * </pre>
     */
    static class BoundSql {
        String sql;                         // 最终 SQL（? 占位）
        List<String> parameterMappings;     // 参数名列表（#{param} 中提取）
        Object parameterObject;             // 参数对象
        List<Object> parameterValues;       // 参数值列表（设置到 PreparedStatement）

        BoundSql(String sql, List<String> parameterMappings, Object parameterObject) {
            this.sql = sql;
            this.parameterMappings = parameterMappings;
            this.parameterObject = parameterObject;
        }

        List<String> getParameterMappings() { return parameterMappings; }

        String getSql() { return sql; }
    }

    /** 演示 #{id} 安全的参数化查询 */
    static void demoHashPlaceholder() {
        System.out.println("=== #{id} 参数化查询（安全） ===");
        String template = "SELECT * FROM user WHERE id = #{id} AND name = #{name}";
        System.out.println("原始 SQL: " + template);

        // 模拟 MyBatis 解析 #{id} → ? 并提取参数名
        System.out.println("\n解析过程:");
        RawSqlSource rawSource = new RawSqlSource(template);

        // BoundSql 生成
        BoundSql boundSql = rawSource.getBoundSql(null);
        System.out.println("最终 SQL: " + boundSql.getSql());
        System.out.println("参数列表: " + boundSql.getParameterMappings());
        System.out.println("JDBC 执行方式:");
        System.out.println("  PreparedStatement pstmt = conn.prepareStatement(sql);");
        System.out.println("  pstmt.setLong(1, idValue);    // #{id} ≫ ? ≫ 参数化");
        System.out.println("  pstmt.setString(2, nameValue); // #{name} ≫ ? ≫ 参数化");
        System.out.println("  pstmt.executeQuery();");
        System.out.println("\n安全原因: 参数值永远不会被当作 SQL 的一部分执行");
        System.out.println();
    }

    /** 演示 ${id} 字符串拼接的风险 */
    static void demoDollarPlaceholder() {
        System.out.println("=== ${id} 字符串拼接（有 SQL 注入风险） ===");
        String template = "SELECT * FROM user WHERE id = ${id}";
        System.out.println("原始 SQL: " + template);

        System.out.println("\n正常输入 id=1:");
        String safeResult = template.replace("${id}", "1");
        System.out.println("  拼接结果: " + safeResult);
        System.out.println("  → 安全");

        System.out.println("\n恶意输入 id=1 OR 1=1:");
        String unsafeResult = template.replace("${id}", "1 OR 1=1");
        System.out.println("  拼接结果: " + unsafeResult);
        System.out.println("  → 危险！返回所有用户数据");

        System.out.println("\n恶意输入 id=1; DROP TABLE user;--:");
        String dropTableResult = template.replace("${id}", "1; DROP TABLE user;--");
        System.out.println("  拼接结果: " + dropTableResult);
        System.out.println("  → 极度危险！可能导致删表");

        System.out.println("\n恶意输入 id=1 UNION SELECT username,password FROM admin:");
        String unionResult = template.replace("${id}",
                "1 UNION SELECT username,password FROM admin");
        System.out.println("  拼接结果: " + unionResult);
        System.out.println("  → 数据泄露风险");
        System.out.println();
    }

    // ============================================================
    // 6. TypeHandler：Java 类型 ↔ JDBC 类型
    // ============================================================

    /**
     * TypeHandler 接口：Java 类型与 JDBC 类型之间的双向转换
     *
     * <p>MyBatis 内置 TypeHandler 示例：</p>
     * <ul>
     *   <li>StringTypeHandler: String ↔ VARCHAR</li>
     *   <li>IntegerTypeHandler: Integer ↔ INTEGER</li>
     *   <li>LongTypeHandler: Long ↔ BIGINT</li>
     *   <li>DateTypeHandler: java.util.Date ↔ TIMESTAMP</li>
     *   <li>BooleanTypeHandler: Boolean ↔ BOOLEAN/TINYINT</li>
     *   <li>EnumTypeHandler: Enum ↔ VARCHAR (name) 或 Enum ↔ INTEGER (ordinal)</li>
     * </ul>
     */
    interface TypeHandler<T> {
        /** Java 对象 → PreparedStatement 参数设置 */
        void setParameter(T parameter);

        /** ResultSet → Java 对象 */
        T getResult(Object jdbcValue);
    }

    /** String ↔ VARCHAR */
    static class StringTypeHandler implements TypeHandler<String> {
        @Override
        public void setParameter(String parameter) {
            System.out.println("      [StringTypeHandler] pstmt.setString(idx, \"" + parameter + "\") → VARCHAR");
        }

        @Override
        public String getResult(Object jdbcValue) {
            String result = jdbcValue != null ? jdbcValue.toString() : null;
            System.out.println("      [StringTypeHandler] rs.getString(column) → \"" + result + "\"");
            return result;
        }
    }

    /** Long ↔ BIGINT */
    static class LongTypeHandler implements TypeHandler<Long> {
        @Override
        public void setParameter(Long parameter) {
            System.out.println("      [LongTypeHandler] pstmt.setLong(idx, " + parameter + ") → BIGINT");
        }

        @Override
        public Long getResult(Object jdbcValue) {
            Long result = jdbcValue != null ? Long.valueOf(jdbcValue.toString()) : null;
            System.out.println("      [LongTypeHandler] rs.getLong(column) → " + result);
            return result;
        }
    }

    /** Date ↔ TIMESTAMP */
    static class DateTypeHandler implements TypeHandler<Date> {
        @Override
        public void setParameter(Date parameter) {
            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(parameter);
            System.out.println("      [DateTypeHandler] pstmt.setTimestamp(idx, Timestamp.valueOf(\""
                    + formatted + "\")) → TIMESTAMP");
        }

        @Override
        public Date getResult(Object jdbcValue) {
            Date result = jdbcValue instanceof Date ? (Date) jdbcValue : null;
            System.out.println("      [DateTypeHandler] rs.getTimestamp(column) → " + result);
            return result;
        }
    }

    /** Enum ↔ VARCHAR (使用 name()) */
    static class EnumTypeHandler<E extends Enum<E>> implements TypeHandler<E> {
        final Class<E> enumType;

        EnumTypeHandler(Class<E> enumType) { this.enumType = enumType; }

        @Override
        public void setParameter(E parameter) {
            System.out.println("      [EnumTypeHandler] pstmt.setString(idx, \""
                    + parameter.name() + "\") → VARCHAR (存储枚举名)");
        }

        @Override
        public E getResult(Object jdbcValue) {
            if (jdbcValue == null) return null;
            E result = Enum.valueOf(enumType, jdbcValue.toString());
            System.out.println("      [EnumTypeHandler] rs.getString(column) → " + result);
            return result;
        }
    }

    // ============================================================
    // 7. XML 解析模拟
    // ============================================================

    /** 模拟 XMLConfigBuilder 解析 mybatis-config.xml */
    static class XmlConfigBuilder {
        Configuration parse() {
            Configuration configuration = new Configuration();

            // 注册默认 TypeHandler
            configuration.registerTypeHandler(String.class, new StringTypeHandler());
            configuration.registerTypeHandler(Long.class, new LongTypeHandler());
            configuration.registerTypeHandler(Date.class, new DateTypeHandler());

            // 模拟解析数据源配置
            configuration.properties.put("driver", "com.mysql.cj.jdbc.Driver");
            configuration.properties.put("url", "jdbc:mysql://localhost:3306/mybatis_demo");
            configuration.properties.put("username", "root");
            configuration.properties.put("password", "***");

            System.out.println("[XmlConfigBuilder] 解析 mybatis-config.xml 完成");
            System.out.println("[XmlConfigBuilder] 环境配置: " + configuration.properties);

            return configuration;
        }
    }

    /** 模拟 XMLMapperBuilder 解析 Mapper XML */
    static class XmlMapperBuilder {
        final Configuration configuration;

        XmlMapperBuilder(Configuration configuration) {
            this.configuration = configuration;
        }

        void parse(XmlMapperConfig mapperConfig) {
            System.out.println("[XmlMapperBuilder] 解析 Mapper: " + mapperConfig.namespace);

            for (XmlStatement stmt : mapperConfig.statements) {
                String statementId = mapperConfig.namespace + "." + stmt.id;

                // 创建 SqlSource
                SqlSource sqlSource;
                if (stmt.dynamicTags.isEmpty()) {
                    sqlSource = new RawSqlSource(stmt.sql);
                } else {
                    sqlSource = new DynamicSqlSource(stmt.sql);
                }

                // 创建 MappedStatement
                MappedStatement ms = new MappedStatement(statementId, sqlSource, stmt.sqlCommandType);
                configuration.addMappedStatement(ms);

                System.out.println("  → 注册 MappedStatement: " + statementId
                        + " [" + stmt.sqlCommandType + "] " + stmt.sql);
            }
        }
    }

    // ============================================================
    // 8. 完整构建流程演示
    // ============================================================

    static void demoConfigurationBuild() {
        System.out.println("==================== Configuration 构建流程 ====================");
        System.out.println();

        // 步骤 1：解析全局配置
        XmlConfigBuilder configBuilder = new XmlConfigBuilder();
        Configuration configuration = configBuilder.parse();
        System.out.println();

        // 步骤 2：准备 Mapper XML 配置
        XmlMapperConfig userMapperXml = new XmlMapperConfig("base.mybatis.UserMapper");
        userMapperXml.addStatement(new XmlStatement(
                "selectById",
                "SELECT id, name, age, create_time FROM user WHERE id = #{id}",
                "java.lang.Long", "base.mybatis.User", SqlCommandType.SELECT));
        userMapperXml.addStatement(new XmlStatement(
                "insert",
                "INSERT INTO user(name, age, create_time) VALUES(#{name}, #{age}, #{createTime})",
                "base.mybatis.User", null, SqlCommandType.INSERT));
        userMapperXml.addStatement(new XmlStatement(
                "updateById",
                "UPDATE user SET name=#{name}, age=#{age} WHERE id=#{id}",
                "base.mybatis.User", null, SqlCommandType.UPDATE));

        // 步骤 3：解析 Mapper XML 注册 MappedStatement
        XmlMapperBuilder mapperBuilder = new XmlMapperBuilder(configuration);
        mapperBuilder.parse(userMapperXml);
        System.out.println();

        // 步骤 4：验证注册结果
        System.out.println("已注册的 MappedStatement:");
        for (Map.Entry<String, MappedStatement> entry : configuration.mappedStatements.entrySet()) {
            System.out.printf("  %s → SqlSource=%s, CommandType=%s%n",
                    entry.getKey(),
                    entry.getValue().sqlSource.getClass().getSimpleName(),
                    entry.getValue().sqlCommandType);
        }
        System.out.println();
    }

    static void demoResultMap() {
        System.out.println("==================== ResultMap 字段映射演示 ====================");
        System.out.println();

        ResultMap resultMap = new ResultMap("userResultMap", User.class);
        resultMap.addMapping(new ResultMapping("id", "id", "BIGINT", "java.lang.Long"));
        resultMap.addMapping(new ResultMapping("name", "name", "VARCHAR", "java.lang.String"));
        resultMap.addMapping(new ResultMapping("age", "age", "INTEGER", "java.lang.Integer"));
        resultMap.addMapping(new ResultMapping("createTime", "create_time", "TIMESTAMP", "java.util.Date"));

        System.out.println("ResultMap: " + resultMap.id + " → " + resultMap.type.getName());
        System.out.println("字段映射:");
        for (ResultMapping rm : resultMap.resultMappings) {
            System.out.println("  " + rm);
        }

        System.out.println("\nResultSet 行 → Java 对象映射过程:");
        System.out.println("  rs.getLong(\"id\")         → user.setId(1L)");
        System.out.println("  rs.getString(\"name\")     → user.setName(\"张三\")");
        System.out.println("  rs.getInt(\"age\")         → user.setAge(25)");
        System.out.println("  rs.getTimestamp(\"create_time\") → user.setCreateTime(date)");
        System.out.println();
    }

    static class User {
        Long id;
        String name;
        Integer age;
        Date createTime;

        User() {}

        @Override
        public String toString() {
            return "User{id=" + id + ", name='" + name + "', age=" + age + "}";
        }
    }

    static void demoTypeHandler() {
        System.out.println("==================== TypeHandler 类型转换演示 ====================");
        System.out.println();

        // 模拟 PreparedStatement 参数设置
        System.out.println("--- Java → JDBC 设置参数 ---");
        new StringTypeHandler().setParameter("张三");
        new LongTypeHandler().setParameter(1L);
        new DateTypeHandler().setParameter(new Date());

        System.out.println();

        // 模拟 ResultSet 取值
        System.out.println("--- JDBC → Java 取值 ---");
        new StringTypeHandler().getResult("李四");
        new LongTypeHandler().getResult("2");
        new DateTypeHandler().getResult(new Date());

        System.out.println();

        // TypeHandler 注册表演示
        System.out.println("MyBatis 内置 TypeHandler 注册表:");
        String[][] handlers = {
                {"String", "VARCHAR / CHAR", "StringTypeHandler"},
                {"Long / long", "BIGINT", "LongTypeHandler"},
                {"Integer / int", "INTEGER", "IntegerTypeHandler"},
                {"Double / double", "DOUBLE / DECIMAL", "DoubleTypeHandler"},
                {"Boolean / boolean", "BOOLEAN / TINYINT", "BooleanTypeHandler"},
                {"java.util.Date", "TIMESTAMP / DATE", "DateTypeHandler"},
                {"java.math.BigDecimal", "DECIMAL / NUMERIC", "BigDecimalTypeHandler"},
                {"byte[]", "BLOB / BINARY", "BlobTypeHandler / BlobByteArrayTypeHandler"},
                {"Enum", "VARCHAR (name) / INTEGER (ordinal)", "EnumTypeHandler / EnumOrdinalTypeHandler"},
        };
        System.out.printf("| %-22s | %-22s | %-30s |%n", "Java 类型", "JDBC 类型", "TypeHandler");
        System.out.println("|------------------------|------------------------|--------------------------------|");
        for (String[] row : handlers) {
            System.out.printf("| %-22s | %-22s | %-30s |%n", row[0], row[1], row[2]);
        }
        System.out.println();
    }

    public static void main(String[] args) {
        demoConfigurationBuild();
        demoResultMap();
        demoHashPlaceholder();
        demoDollarPlaceholder();
        demoTypeHandler();
    }
}