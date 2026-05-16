package base.mybatis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * MyBatis 核心流程：SqlSessionFactory → SqlSession → Executor → StatementHandler → ResultSetHandler
 *
 * <p>手写简化版 MyBatis 核心组件，演示完整调用链：</p>
 * <ol>
 *   <li>SqlSessionFactoryBuilder 解析配置构建 SqlSessionFactory</li>
 *   <li>SqlSessionFactory 打开 SqlSession（含一级缓存 HashMap）</li>
 *   <li>MapperProxy（JDK 动态代理）拦截 Mapper 接口方法调用</li>
 *   <li>Executor 执行 SQL（含一级/二级缓存判断）</li>
 *   <li>插件拦截器链：Interceptor → Plugin.wrap() → 责任链模式</li>
 *   <li>StatementHandler → ResultSetHandler 处理结果</li>
 * </ol>
 *
 * @author study-tuling
 */
public class MyBatisCoreDemo {

    // ============================================================
    // 1. 领域模型
    // ============================================================

    /** 用户实体 */
    static class User {
        Long id;
        String name;
        Integer age;

        User() {}

        User(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        @Override
        public String toString() {
            return "User{id=" + id + ", name='" + name + "', age=" + age + "}";
        }
    }

    /** 模拟数据库中的行数据 */
    static final Map<Long, User> DATABASE = new LinkedHashMap<>();
    static {
        DATABASE.put(1L, new User(1L, "张三", 25));
        DATABASE.put(2L, new User(2L, "李四", 30));
        DATABASE.put(3L, new User(3L, "王五", 28));
    }

    // ============================================================
    // 2. 配置对象（对应 Configuration）
    // ============================================================

    /** 简化版 Configuration，持有 MappedStatement 注册表和缓存 */
    static class SimpleConfiguration {
        /** Mapper 接口 → XML/注解映射的 SQL 语句 */
        Map<String, SimpleMappedStatement> mappedStatements = new HashMap<>();
        /** 二级缓存（Mapper 级别，跨 SqlSession 共享） */
        Map<String, Map<Object, Object>> secondLevelCache = new HashMap<>();
        /** 拦截器链 */
        List<SimpleInterceptor> interceptors = new ArrayList<>();
        /** 是否启用二级缓存 */
        boolean cacheEnabled = true;

        void addMappedStatement(String id, SimpleMappedStatement ms) {
            mappedStatements.put(id, ms);
        }

        SimpleMappedStatement getMappedStatement(String id) {
            return mappedStatements.get(id);
        }

        void addInterceptor(SimpleInterceptor interceptor) {
            interceptors.add(interceptor);
        }
    }

    /** 映射语句：持有 SQL、参数类型、返回类型 */
    static class SimpleMappedStatement {
        String id;              // 如 "base.mybatis.MyBatisCoreDemo$UserMapper.selectById"
        String sql;             // 原始 SQL
        Class<?> parameterType; // 参数类型
        Class<?> resultType;    // 返回类型
        SqlCommandType sqlCommandType;

        SimpleMappedStatement(String id, String sql, Class<?> parameterType,
                              Class<?> resultType, SqlCommandType sqlCommandType) {
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
    // 3. Mapper 接口（用户定义）
    // ============================================================

    interface UserMapper {
        User selectById(Long id);
        List<User> selectAll();
        int insert(User user);
        int updateById(User user);
        int deleteById(Long id);
    }

    // ============================================================
    // 4. SqlSessionFactoryBuilder → SqlSessionFactory
    // ============================================================

    /** 构建器：解析配置（模拟 XML/注解解析），生成 SqlSessionFactory */
    static class SimpleSqlSessionFactoryBuilder {
        SimpleSqlSessionFactory build(SimpleConfiguration configuration) {
            // 注册 Mapper 对应的 MappedStatement（模拟 XML 解析）
            configuration.addMappedStatement(
                    UserMapper.class.getName() + ".selectById",
                    new SimpleMappedStatement(
                            "selectById", "SELECT * FROM user WHERE id = ?",
                            Long.class, User.class, SqlCommandType.SELECT));
            configuration.addMappedStatement(
                    UserMapper.class.getName() + ".selectAll",
                    new SimpleMappedStatement(
                            "selectAll", "SELECT * FROM user",
                            null, List.class, SqlCommandType.SELECT));
            configuration.addMappedStatement(
                    UserMapper.class.getName() + ".insert",
                    new SimpleMappedStatement(
                            "insert", "INSERT INTO user(name, age) VALUES(?, ?)",
                            User.class, Integer.class, SqlCommandType.INSERT));
            configuration.addMappedStatement(
                    UserMapper.class.getName() + ".updateById",
                    new SimpleMappedStatement(
                            "updateById", "UPDATE user SET name=?, age=? WHERE id=?",
                            User.class, Integer.class, SqlCommandType.UPDATE));
            configuration.addMappedStatement(
                    UserMapper.class.getName() + ".deleteById",
                    new SimpleMappedStatement(
                            "deleteById", "DELETE FROM user WHERE id = ?",
                            Long.class, Integer.class, SqlCommandType.DELETE));
            return new SimpleSqlSessionFactory(configuration);
        }
    }

    /** SqlSessionFactory：创建 SqlSession */
    static class SimpleSqlSessionFactory {
        final SimpleConfiguration configuration;

        SimpleSqlSessionFactory(SimpleConfiguration configuration) {
            this.configuration = configuration;
        }

        SimpleSqlSession openSession() {
            return new SimpleSqlSession(configuration);
        }
    }

    // ============================================================
    // 5. SqlSession（含一级缓存）
    // ============================================================

    /**
     * SqlSession：一次数据库会话，持有一级缓存（SqlSession 级别 HashMap）
     * 生命周期：打开 → 使用 → 关闭
     */
    static class SimpleSqlSession {
        final SimpleConfiguration configuration;
        /** 一级缓存：SqlSession 级别，key=statementId+参数, value=结果 */
        final Map<String, Object> localCache = new HashMap<>();
        final SimpleExecutor executor;

        SimpleSqlSession(SimpleConfiguration configuration) {
            this.configuration = configuration;
            this.executor = new SimpleExecutor(configuration, localCache);
        }

        /** 获取 Mapper 代理对象 */
        @SuppressWarnings("unchecked")
        <T> T getMapper(Class<T> mapperInterface) {
            return (T) Proxy.newProxyInstance(
                    mapperInterface.getClassLoader(),
                    new Class<?>[]{mapperInterface},
                    new MapperProxy<>(this, mapperInterface));
        }

        /** 清理一级缓存 */
        void clearCache() {
            localCache.clear();
            System.out.println("  [一级缓存] 已清空");
        }

        /** 关闭 SqlSession */
        void close() {
            localCache.clear();
        }
    }

    // ============================================================
    // 6. MapperProxy（JDK 动态代理）
    // ============================================================

    /**
     * MapperProxy：JDK 动态代理，拦截 Mapper 接口的每个方法调用
     * 将方法调用转换为 Executor 的查询/更新操作
     */
    static class MapperProxy<T> implements InvocationHandler {
        final SimpleSqlSession sqlSession;
        final Class<T> mapperInterface;

        MapperProxy(SimpleSqlSession sqlSession, Class<T> mapperInterface) {
            this.sqlSession = sqlSession;
            this.mapperInterface = mapperInterface;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object 类的方法直接调用（toString, hashCode 等）
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }

            String statementId = mapperInterface.getName() + "." + method.getName();
            SimpleMappedStatement ms = sqlSession.configuration.getMappedStatement(statementId);

            if (ms == null) {
                throw new RuntimeException("找不到 MappedStatement: " + statementId);
            }

            Object parameter = (args != null && args.length > 0) ? args[0] : null;

            if (ms.sqlCommandType == SqlCommandType.SELECT) {
                return sqlSession.executor.query(ms, parameter, method.getReturnType());
            } else {
                return sqlSession.executor.update(ms, parameter);
            }
        }
    }

    // ============================================================
    // 7. Executor（含一级缓存 + 二级缓存判断）
    // ============================================================

    static class SimpleExecutor {
        final SimpleConfiguration configuration;
        final Map<String, Object> localCache;

        SimpleExecutor(SimpleConfiguration configuration, Map<String, Object> localCache) {
            this.configuration = configuration;
            this.localCache = localCache;
        }

        /**
         * 查询执行流程：
         * 1. 先查一级缓存（localCache）
         * 2. 再查二级缓存（secondLevelCache）
         * 3. 都未命中则真正执行查询（StatementHandler → ResultSetHandler）
         */
        @SuppressWarnings("unchecked")
        Object query(SimpleMappedStatement ms, Object parameter, Class<?> returnType) {
            String cacheKey = ms.id + ":" + parameter;

            // 步骤 1：一级缓存
            if (localCache.containsKey(cacheKey)) {
                System.out.println("  [一级缓存命中] key=" + cacheKey + " -> " + localCache.get(cacheKey));
                return localCache.get(cacheKey);
            }

            // 步骤 2：二级缓存（跨 SqlSession 共享）
            if (configuration.cacheEnabled) {
                Map<Object, Object> secondCache = configuration.secondLevelCache
                        .computeIfAbsent(ms.id, k -> new HashMap<>());
                if (secondCache.containsKey(cacheKey)) {
                    System.out.println("  [二级缓存命中] key=" + cacheKey + " -> " + secondCache.get(cacheKey));
                    // 同步到一级缓存
                    localCache.put(cacheKey, secondCache.get(cacheKey));
                    return secondCache.get(cacheKey);
                }
            }

            // 步骤 3：真正查询
            System.out.println("  [查询数据库] SQL: " + ms.sql + ", 参数: " + parameter);

            // 步骤 4：插件拦截器链（责任链模式）
            SimpleStatementHandler handler = new SimpleStatementHandler(ms);
            handler = (SimpleStatementHandler) wrapWithInterceptors(handler);

            Object result = handler.query(parameter, returnType);

            // 步骤 5：放入一级缓存
            localCache.put(cacheKey, result);
            System.out.println("  [一级缓存写入] key=" + cacheKey);

            // 步骤 6：放入二级缓存
            if (configuration.cacheEnabled) {
                Map<Object, Object> secondCache = configuration.secondLevelCache
                        .computeIfAbsent(ms.id, k -> new HashMap<>());
                secondCache.put(cacheKey, result);
                System.out.println("  [二级缓存写入] key=" + cacheKey);
            }

            return result;
        }

        /** 更新操作：执行并清空相关缓存 */
        Object update(SimpleMappedStatement ms, Object parameter) {
            System.out.println("  [执行更新] SQL: " + ms.sql + ", 参数: " + parameter);

            // 清空一级缓存（更新后本地缓存失效）
            localCache.clear();

            // 清空对应 Mapper 的二级缓存
            if (configuration.cacheEnabled) {
                configuration.secondLevelCache.remove(ms.id);
            }

            return 1; // 模拟影响行数
        }

        /** 用拦截器链包装 Handler */
        private Object wrapWithInterceptors(Object target) {
            Object result = target;
            for (SimpleInterceptor interceptor : configuration.interceptors) {
                result = interceptor.plugin(result);
            }
            return result;
        }
    }

    // ============================================================
    // 8. StatementHandler + ResultSetHandler
    // ============================================================

    /** StatementHandler：处理 SQL 语句 */
    static class SimpleStatementHandler {
        final SimpleMappedStatement mappedStatement;

        SimpleStatementHandler(SimpleMappedStatement mappedStatement) {
            this.mappedStatement = mappedStatement;
        }

        Object query(Object parameter, Class<?> returnType) {
            // 模拟 JDBC 调用
            System.out.println("    [StatementHandler] 创建 PreparedStatement: " + mappedStatement.sql);
            System.out.println("    [StatementHandler] 设置参数: " + parameter);
            System.out.println("    [StatementHandler] 执行 executeQuery()");

            // 委托给 ResultSetHandler
            return new SimpleResultSetHandler().handle(parameter, returnType);
        }
    }

    /** ResultSetHandler：处理结果集 */
    static class SimpleResultSetHandler {
        @SuppressWarnings("unchecked")
        Object handle(Object parameter, Class<?> returnType) {
            System.out.println("    [ResultSetHandler] 处理 ResultSet → 映射为 " + returnType.getSimpleName());

            if (returnType == List.class) {
                // selectAll 返回全部
                List<User> result = new ArrayList<>(DATABASE.values());
                System.out.println("    [ResultSetHandler] 返回 " + result.size() + " 条记录");
                return result;
            }

            if (parameter instanceof Long id) {
                User user = DATABASE.get(id);
                System.out.println("    [ResultSetHandler] 返回: " + user);
                return user;
            }

            return null;
        }
    }

    // ============================================================
    // 9. 插件拦截器（Interceptor 接口 + Plugin 包装 + 责任链）
    // ============================================================

    /** 拦截器接口 */
    interface SimpleInterceptor {
        /** 拦截方法，返回代理对象 */
        Object intercept(Invocation invocation);

        /** 包装目标对象，生成代理 */
        Object plugin(Object target);
    }

    /** 拦截器调用上下文 */
    static class Invocation {
        final Object target;
        final Method method;
        final Object[] args;

        Invocation(Object target, Method method, Object[] args) {
            this.target = target;
            this.method = method;
            this.args = args;
        }

        Object proceed() throws Exception {
            return method.invoke(target, args);
        }
    }

    /** SQL 日志拦截器：在 StatementHandler.query() 前后打印日志 */
    static class SqlLogInterceptor implements SimpleInterceptor {
        @Override
        public Object intercept(Invocation invocation) {
            System.out.println("    [拦截器-SqlLog] >>> 执行前: " + invocation.method.getName());
            try {
                Object result = invocation.proceed();
                System.out.println("    [拦截器-SqlLog] <<< 执行后，结果: " + result);
                return result;
            } catch (Exception e) {
                System.out.println("    [拦截器-SqlLog] <<< 异常: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }
    }

    /** 分页拦截器：模拟在查询前改写 SQL */
    static class PageInterceptor implements SimpleInterceptor {
        @Override
        public Object intercept(Invocation invocation) {
            System.out.println("    [拦截器-Page] 分页参数检查...");
            try {
                return invocation.proceed();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }
    }

    /** Plugin：JDK 动态代理包装，责任链模式 */
    static class Plugin implements InvocationHandler {
        final Object target;
        final SimpleInterceptor interceptor;

        Plugin(Object target, SimpleInterceptor interceptor) {
            this.target = target;
            this.interceptor = interceptor;
        }

        /**
         * 包装目标对象：仅当目标对象类型匹配时才生成代理
         * 简化版：直接对所有 query/update 方法生成代理
         */
        static Object wrap(Object target, SimpleInterceptor interceptor) {
            return Proxy.newProxyInstance(
                    target.getClass().getClassLoader(),
                    target.getClass().getInterfaces().length > 0
                            ? target.getClass().getInterfaces()
                            : new Class<?>[]{target.getClass()},
                    new Plugin(target, interceptor));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 仅拦截 query 和 update 方法
            if ("query".equals(method.getName()) || "update".equals(method.getName())) {
                return interceptor.intercept(new Invocation(target, method, args));
            }
            return method.invoke(target, args);
        }
    }

    // ============================================================
    // 10. 演示入口
    // ============================================================

    static void demoFullFlow() {
        System.out.println("==================== MyBatis 核心流程完整演示 ====================");
        System.out.println();

        // 步骤 1：构建 Configuration
        SimpleConfiguration configuration = new SimpleConfiguration();
        // 注册拦截器（责任链顺序：SqlLog → Page）
        configuration.addInterceptor(new SqlLogInterceptor());
        configuration.addInterceptor(new PageInterceptor());

        // 步骤 2：SqlSessionFactoryBuilder 构建 SqlSessionFactory
        SimpleSqlSessionFactory sqlSessionFactory =
                new SimpleSqlSessionFactoryBuilder().build(configuration);
        System.out.println("[1] SqlSessionFactory 构建完成");

        // 步骤 3：打开 SqlSession
        SimpleSqlSession sqlSession = sqlSessionFactory.openSession();
        System.out.println("[2] SqlSession 打开（一级缓存初始化）");

        // 步骤 4：获取 Mapper 代理
        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
        System.out.println("[3] MapperProxy 代理创建完成（JDK 动态代理）");
        System.out.println();

        // 步骤 5：第一次查询（查数据库）
        System.out.println("--- 第一次查询 selectById(1) ---");
        User user1 = userMapper.selectById(1L);
        System.out.println("结果: " + user1);
        System.out.println();

        // 步骤 6：第二次相同查询（一级缓存命中）
        System.out.println("--- 第二次相同查询 selectById(1) [期待一级缓存命中] ---");
        User user2 = userMapper.selectById(1L);
        System.out.println("结果: " + user2);
        System.out.println();

        // 步骤 7：查询全部
        System.out.println("--- 查询全部 selectAll() ---");
        List<User> allUsers = userMapper.selectAll();
        System.out.println("结果: " + allUsers);
        System.out.println();

        // 步骤 8：插入操作（清空一级缓存）
        System.out.println("--- 插入 insert(new User) ---");
        int insertCount = userMapper.insert(new User(4L, "赵六", 35));
        System.out.println("影响行数: " + insertCount);
        System.out.println("（插入后一级缓存被清空）");
        System.out.println();

        // 步骤 9：再次查询（缓存已清空，重新查库）
        System.out.println("--- 插入后查询 selectById(1) [缓存已清空，重新查库] ---");
        User user3 = userMapper.selectById(1L);
        System.out.println("结果: " + user3);
        System.out.println();

        // 步骤 10：二级缓存跨 SqlSession 演示
        System.out.println("--- 二级缓存跨 SqlSession 演示 ---");
        SimpleSqlSession sqlSession2 = sqlSessionFactory.openSession();
        UserMapper userMapper2 = sqlSession2.getMapper(UserMapper.class);
        System.out.println("新 SqlSession 中查询 selectById(1) [期待二级缓存命中]:");
        User user4 = userMapper2.selectById(1L);
        System.out.println("结果: " + user4);
        sqlSession2.close();

        sqlSession.close();
        System.out.println();
    }

    static void demoTables() {
        System.out.println("==================== 核心组件对比表 ====================");
        System.out.println();
        System.out.println("| 组件                  | 职责                                       | 生命周期         |");
        System.out.println("|-----------------------|--------------------------------------------|------------------|");
        System.out.println("| SqlSessionFactory     | 创建 SqlSession，全局一个                  | 应用级           |");
        System.out.println("| SqlSession            | 数据库会话，一级缓存                       | 请求/方法级      |");
        System.out.println("| MapperProxy           | JDK 动态代理，拦截接口方法                 | 随 SqlSession    |");
        System.out.println("| Executor              | 执行 SQL，管理一二级缓存                   | 随 SqlSession    |");
        System.out.println("| StatementHandler      | 处理 PreparedStatement                     | 每次调用         |");
        System.out.println("| ResultSetHandler      | 处理 ResultSet → Java 对象映射             | 每次调用         |");
        System.out.println("| InterceptorChain      | 插件责任链：Log → Page → ...               | 应用级           |");
        System.out.println();
        System.out.println("| 缓存           | 级别           | 作用域           | 生命周期               |");
        System.out.println("|----------------|----------------|------------------|------------------------|");
        System.out.println("| 一级缓存       | SqlSession     | 同一个 SqlSession | commit/close/update 清空 |");
        System.out.println("| 二级缓存       | Mapper/全局    | 跨 SqlSession     | 整个应用运行期间       |");
    }

    public static void main(String[] args) {
        demoFullFlow();
        System.out.println();
        demoTables();
    }
}