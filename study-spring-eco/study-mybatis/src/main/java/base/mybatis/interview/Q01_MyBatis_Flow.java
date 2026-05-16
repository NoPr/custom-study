package base.mybatis.interview;

import java.util.*;

/**
 * 面试：MyBatis 核心流程 + 一二级缓存 + 插件原理 + #{ } vs ${ }
 *
 * <p>涵盖 MyBatis 面试高频问题：</p>
 * <ol>
 *   <li>完整 13 步调用链（从 MapperProxy 到 ResultSetHandler）</li>
 *   <li>一级缓存命中时机（同一个 SqlSession 内）</li>
 *   <li>二级缓存跨 SqlSession 共享</li>
 *   <li>MyBatis vs MyBatis Plus vs JPA/Hibernate 对比表</li>
 * </ol>
 *
 * @author study-tuling
 */
public class Q01_MyBatis_Flow {

    // ============================================================
    // 1. 完整 13 步调用链
    // ============================================================

    static void printFullFlow() {
        System.out.println("==================== MyBatis 完整 13 步调用链 ====================");
        System.out.println();
        System.out.println("从 Mapper 接口方法调用到数据库结果返回的完整链路:");
        System.out.println();

        String[] steps = {
                "步骤 1 | MapperProxy.invoke()                       | JDK 动态代理拦截 Mapper 接口方法调用，获取方法名 → statementId",
                "步骤 2 | MapperMethod.execute()                     | 根据 SqlCommandType 路由到 SqlSession 的 select/insert/update/delete",
                "步骤 3 | SqlSession.selectOne() / selectList()       | 委托给 Executor 执行查询，传递 statementId + 参数",
                "步骤 4 | CachingExecutor.query()                     | 如果有二级缓存，先查二级缓存 → 未命中则委托 BaseExecutor",
                "步骤 5 | BaseExecutor.query()                         | 先查一级缓存（localCache）→ 未命中则 queryFromDatabase()",
                "步骤 6 | BaseExecutor.queryFromDatabase()             | 执行 doQuery()，完成后将结果放入一级缓存",
                "步骤 7 | SimpleExecutor.doQuery()                     | 创建 StatementHandler、ParameterHandler、ResultSetHandler",
                "步骤 8 | Configuration.newStatementHandler()          | 创建 RoutingStatementHandler → PreparedStatementHandler",
                "步骤 9 | StatementHandler.parameterize()              | 委托 ParameterHandler 设置 PreparedStatement 参数",
                "步骤 10 | ParameterHandler.setParameters()            | 调用 TypeHandler.setParameter() 将 Java 对象 → JDBC 参数",
                "步骤 11 | StatementHandler.query()                    | 执行 PreparedStatement.executeQuery() 获取 ResultSet",
                "步骤 12 | ResultSetHandler.handleResultSets()         | 遍历 ResultSet，调用 TypeHandler.getResult() 映射为 Java 对象",
                "步骤 13 | 返回结果给调用方                            | Java 对象 → MapperProxy → 业务代码",
        };

        for (String step : steps) {
            System.out.println(step);
        }

        System.out.println();
        System.out.println("核心类职责速查:");
        System.out.println();

        String[][] roles = {
                {"SqlSessionFactoryBuilder", "构建者模式", "解析 XML/注解配置 → Configuration → 构建 SqlSessionFactory"},
                {"SqlSessionFactory", "工厂模式", "创建 SqlSession，全局单例"},
                {"SqlSession", "门面模式", "对外提供 CRUD API，持有 Executor 引用"},
                {"MapperProxy", "JDK 动态代理", "拦截 Mapper 接口方法，路由到 SqlSession"},
                {"Executor", "模板方法模式", "管理缓存、事务，委托 StatementHandler 执行"},
                {"StatementHandler", "策略模式", "创建 PreparedStatement，设置参数，执行 SQL"},
                {"ParameterHandler", "-", "将 Java 对象转换为 PreparedStatement 参数"},
                {"ResultSetHandler", "-", "将 ResultSet 转换为 Java 对象列表"},
                {"TypeHandler", "策略模式", "Java 类型 ↔ JDBC 类型双向转换"},
                {"InterceptorChain", "责任链模式", "插件链式拦截 Executor/StatementHandler/ResultSetHandler"},
        };

        System.out.printf("| %-25s | %-12s | %-55s |%n", "类", "设计模式", "职责");
        System.out.println("|---------------------------|--------------|---------------------------------------------------------|");
        for (String[] row : roles) {
            System.out.printf("| %-25s | %-12s | %-55s |%n", row[0], row[1], row[2]);
        }
        System.out.println();
    }

    // ============================================================
    // 2. 一级缓存 vs 二级缓存
    // ============================================================

    static void printCacheCompare() {
        System.out.println("==================== 一级缓存 vs 二级缓存 ====================");
        System.out.println();

        System.out.println("1. 一级缓存 (Local Cache / SqlSession 级别):");
        System.out.println("   - 默认开启，无法关闭");
        System.out.println("   - 作用域：同一个 SqlSession 内有效");
        System.out.println("   - 存储：HashMap<statementId, HashMap<parameter, result>>");
        System.out.println("   - 命中时机：同一个 SqlSession 内执行相同 SQL + 相同参数");
        System.out.println("   - 失效时机：执行 insert/update/delete 后自动清空（防止脏读）");
        System.out.println("   - 可手动清空：sqlSession.clearCache()");
        System.out.println("   - 生命周期：随 SqlSession 创建，随 close() 销毁");
        System.out.println();

        System.out.println("2. 二级缓存 (Second Level Cache / Mapper 级别):");
        System.out.println("   - 默认不开启，需要配置 <cache/> 或 @CacheNamespace");
        System.out.println("   - 作用域：跨 SqlSession，同一 namespace 内共享");
        System.out.println("   - 存储：可插拔（默认 PerpetualCache，支持 Ehcache/Redis 等）");
        System.out.println("   - 命中时机：不同的 SqlSession 执行相同 SQL + 相同参数");
        System.out.println("   - 失效时机：同一 namespace 内执行 insert/update/delete 后清空");
        System.out.println("   - 序列化要求：实体必须实现 Serializable");
        System.out.println("   - 生命周期：应用运行期间（或缓存过期策略决定）");
        System.out.println();

        System.out.println("3. 缓存查询顺序:");
        System.out.println("   二级缓存 → 一级缓存 → 数据库");
        System.out.println("   先查二级（跨 Session 共享），未命中再查一级（Session 内），未命中再查库");
        System.out.println();

        // 缓存对比表
        System.out.println("| 特性             | 一级缓存                         | 二级缓存                         |");
        System.out.println("|------------------|----------------------------------|----------------------------------|");
        System.out.println("| 级别             | SqlSession 级别                  | Mapper(namespace) 级别           |");
        System.out.println("| 默认状态         | 开启，不可关闭                   | 关闭，需手动配置                  |");
        System.out.println("| 作用域           | 同一个 SqlSession                | 跨 SqlSession（同一 namespace）   |");
        System.out.println("| 存储结构         | HashMap（内存）                  | 可插拔（默认 PerpetualCache）     |");
        System.out.println("| 数据一致性       | 强（Session 内）                 | 弱（可能读到过期数据）            |");
        System.out.println("| 脏读风险         | update 自动清空，无风险          | 多节点部署时存在缓存不一致        |");
        System.out.println("| 序列化           | 不需要                           | 实体必须实现 Serializable         |");
        System.out.println("| 适用场景         | 同一 SqlSession 内重复查询       | 频繁查询且数据变更少的场景        |");
        System.out.println();
    }

    // ============================================================
    // 3. 插件原理
    // ============================================================

    static void printPluginPrinciple() {
        System.out.println("==================== MyBatis 插件原理 ====================");
        System.out.println();

        System.out.println("1. 插件接口：org.apache.ibatis.plugin.Interceptor");
        System.out.println("   - Object intercept(Invocation invocation)  // 拦截逻辑");
        System.out.println("   - Object plugin(Object target)             // 包装目标对象");
        System.out.println("   - void setProperties(Properties properties) // 获取配置参数");
        System.out.println();

        System.out.println("2. 可拦截的四大对象（通过 @Intercepts + @Signature 指定）：");
        System.out.println();

        String[][] interceptable = {
                {"Executor", "query/update/commit/rollback", "最常用：分页插件、SQL 性能监控"},
                {"StatementHandler", "prepare/parameterize/batch/update/query", "SQL 改写、分表路由"},
                {"ParameterHandler", "getParameterObject/setParameters", "参数加密、脱敏"},
                {"ResultSetHandler", "handleResultSets/handleOutputParameters", "结果集加密、解密"},
        };

        System.out.printf("| %-18s | %-45s | %-30s |%n", "拦截对象", "可拦截方法", "典型用途");
        System.out.println("|--------------------|-----------------------------------------------|--------------------------------|");
        for (String[] row : interceptable) {
            System.out.printf("| %-18s | %-45s | %-30s |%n", row[0], row[1], row[2]);
        }
        System.out.println();

        System.out.println("3. Plugin.wrap() 原理（JDK 动态代理 + 责任链）：");
        System.out.println("   - Plugin 实现 InvocationHandler");
        System.out.println("   - Plugin.wrap(target, interceptor) 使用 Proxy.newProxyInstance()");
        System.out.println("   - 检查 @Intercepts 注解，仅拦截匹配的类和方法");
        System.out.println("   - 多个插件形成责任链：PluginA → PluginB → PluginC → Target");
        System.out.println();

        System.out.println("4. 分页插件（PageHelper）原理：");
        System.out.println("   - 拦截 Executor.query(MappedStatement, parameter, RowBounds, ResultHandler)");
        System.out.println("   - 检测 ThreadLocal 中是否有分页参数");
        System.out.println("   - 如有分页参数：先执行 COUNT 查询 → 改写 SQL 追加 LIMIT → 执行分页查询");
        System.out.println("   - 将结果封装到 Page 对象返回");
        System.out.println();
    }

    // ============================================================
    // 4. #{ } vs ${ }
    // ============================================================

    static void printPlaceholderCompare() {
        System.out.println("==================== #{ } vs ${ } 本质区别 ====================");
        System.out.println();

        System.out.println("1. #{ } - 参数占位符（安全）:");
        System.out.println("   - 原理：编译为 PreparedStatement 的 ? 占位符");
        System.out.println("   - 示例：SELECT * FROM user WHERE id = #{id}");
        System.out.println("   - 实际：SELECT * FROM user WHERE id = ?");
        System.out.println("   - JDBC：pstmt.setLong(1, idValue);");
        System.out.println("   - 安全：参数值不会被解释为 SQL 的一部分，杜绝 SQL 注入");
        System.out.println("   - 适用：所有参数值（WHERE 条件、INSERT/UPDATE 的值）");
        System.out.println();

        System.out.println("2. ${ } - 字符串拼接（危险）:");
        System.out.println("   - 原理：直接将变量值拼接到 SQL 字符串中");
        System.out.println("   - 示例：SELECT * FROM user ORDER BY ${orderColumn}");
        System.out.println("   - 实际：SELECT * FROM user ORDER BY id");
        System.out.println("   - JDBC：直接字符串拼接，无参数化");
        System.out.println("   - 风险：SQL 注入攻击入口");
        System.out.println("   - 适用：表名、列名、ORDER BY 字段名（动态表名/列名）");
        System.out.println();

        System.out.println("3. SQL 注入攻击示例：");
        System.out.println("   原始 SQL: SELECT * FROM user WHERE name = '${name}'");
        System.out.println("   恶意输入: ' OR '1'='1");
        System.out.println("   拼接结果: SELECT * FROM user WHERE name = '' OR '1'='1'");
        System.out.println("   结果: 返回全部用户数据！");
        System.out.println();
        System.out.println("   恶意输入: '; DROP TABLE user; --");
        System.out.println("   拼接结果: SELECT * FROM user WHERE name = ''; DROP TABLE user; --'");
        System.out.println("   结果: user 表被删除！");
        System.out.println();

        System.out.println("4. 对比表:");
        System.out.println("| 特性               | #{ }                          | ${ }                          |");
        System.out.println("|--------------------|-------------------------------|-------------------------------|");
        System.out.println("| 底层实现           | PreparedStatement ? 占位符    | 字符串直接拼接                |");
        System.out.println("| 编译时机           | 预编译                         | 不预编译                      |");
        System.out.println("| SQL 注入           | 安全，可防止                   | 不安全，无法防止              |");
        System.out.println("| 类型处理           | TypeHandler 自动转换           | 手动 to.String() 拼接         |");
        System.out.println("| 参数位置           | WHERE/Having 条件值            | 表名/列名/ORDER BY 字段       |");
        System.out.println("| 单引号处理         | 自动加单引号（字符串类型）     | 需手动添加                    |");
        System.out.println("| 预编译缓存         | 可利用 SQL 预编译缓存          | 每次不同 SQL，无法缓存        |");
        System.out.println("| 推荐程度           | 强烈推荐                       | 仅必要时使用                  |");
        System.out.println();
    }

    // ============================================================
    // 5. MyBatis vs MyBatis Plus vs JPA/Hibernate
    // ============================================================

    static void printFrameworkCompare() {
        System.out.println("==================== MyBatis vs MyBatis Plus vs JPA/Hibernate 对比 ====================");
        System.out.println();

        String[][] compare = {
                {"ORM 类型", "半自动（SQL Mapping）", "半自动（增强 MyBatis）", "全自动 ORM"},
                {"SQL 控制", "完全手写 SQL", "手写 + 自动生成", "自动生成（JPQL/HQL）"},
                {"CRUD 效率", "需手写 SQL 或 XML", "BaseMapper 开箱即用", "JpaRepository 开箱即用"},
                {"复杂查询", "灵活强大，手写 SQL", "Wrapper 条件构造器", "Criteria API / @Query"},
                {"动态 SQL", "XML if/foreach 等标签", "Wrapper 动态条件", "Specification/CriteriaBuilder"},
                {"分页", "手动 LIMIT", "Page + 插件自动", "Pageable 自动分页"},
                {"乐观锁", "手动 version 字段", "@Version 注解自动", "@Version 注解自动"},
                {"逻辑删除", "手动标记字段", "@TableLogic 注解自动", "@SQLDelete + @Where 注解"},
                {"缓存", "一级 + 二级缓存", "一级 + 二级缓存", "一级 + 二级 + 查询缓存"},
                {"学习曲线", "中等（需学 XML/注解）", "低（封装 MyBatis）", "高（需学 JPA 规范）"},
                {"性能调优", "SQL 直接可调优", "SQL 直接可调优", "需分析自动生成的 SQL"},
                {"数据库移植", "SQL 方言需手动适配", "SQL 方言需手动适配", "自动适配（Dialect）"},
                {"适用场景", "复杂 SQL、高性能要求", "快速开发 + 复杂 SQL", "标准 CRUD、快速原型"},
                {"典型项目", "电商、金融、大数据", "后台管理、快速迭代", "微服务、快速原型"},
        };

        System.out.printf("| %-14s | %-28s | %-28s | %-28s |%n", "维度", "MyBatis", "MyBatis Plus", "JPA/Hibernate");
        System.out.println("|----------------|------------------------------|------------------------------|------------------------------|");
        for (String[] row : compare) {
            System.out.printf("| %-14s | %-28s | %-28s | %-28s |%n", row[0], row[1], row[2], row[3]);
        }
        System.out.println();

        System.out.println("选型建议:");
        System.out.println("  1. 复杂 SQL 多、性能要求高 → MyBatis / MyBatis Plus");
        System.out.println("  2. 快速开发、标准化 CRUD 多 → MyBatis Plus / Spring Data JPA");
        System.out.println("  3. 微服务架构、快速原型 → Spring Data JPA");
        System.out.println("  4. 大型互联网项目（高并发 + 复杂查询） → MyBatis Plus");
        System.out.println("  5. 需要完全控制 SQL 的业务 → MyBatis（原生）");
        System.out.println();
    }

    // ============================================================
    // 6. MyBatis 核心对象生命周期
    // ============================================================

    static void printLifecycle() {
        System.out.println("==================== MyBatis 核心对象生命周期 ====================");
        System.out.println();

        String[][] lifecycle = {
                {"SqlSessionFactoryBuilder", "方法级", "解析 XML 配置，构建 SqlSessionFactory，用后即弃"},
                {"SqlSessionFactory", "应用级（单例）", "整个应用生命周期，全局唯一"},
                {"SqlSession", "请求/方法级", "每次数据库操作创建，用后关闭（非线程安全！）"},
                {"MapperProxy", "随 SqlSession", "每次 getMapper() 创建新代理对象"},
                {"Executor", "随 SqlSession", "每次 openSession() 创建，close() 销毁"},
                {"一级缓存 (localCache)", "随 SqlSession", "随 SqlSession 创建，commit/close/update 清空"},
                {"二级缓存", "应用级", "应用运行期间，同一 namespace 共享"},
        };

        System.out.printf("| %-25s | %-16s | %-55s |%n", "对象", "生命周期", "说明");
        System.out.println("|---------------------------|------------------|---------------------------------------------------------|");
        for (String[] row : lifecycle) {
            System.out.printf("| %-25s | %-16s | %-55s |%n", row[0], row[1], row[2]);
        }
        System.out.println();
    }

    // ============================================================
    // 入口
    // ============================================================

    public static void main(String[] args) {
        printFullFlow();
        printCacheCompare();
        printPluginPrinciple();
        printPlaceholderCompare();
        printFrameworkCompare();
        printLifecycle();
    }
}