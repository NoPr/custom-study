package base.reflect.interview;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 面试题 02：注解 vs XML 配置，各自的优缺点是什么？
 *
 * 答案要点：
 * 1. 注解：类型安全、编译期检查、代码内聚、零 xml 模板文件
 * 2. XML：不侵入源码、外部化可热加载、统一管理、复杂配置清晰
 * 3. 现代框架趋势：注解为主（Spring Boot）、XML 为辅（遗留系统兼容）
 * 4. 核心取舍：注解牺牲了可配置性换取开发效率
 * 5. 实际案例：Spring 的 @Service vs XML 声明 Bean、MyBatis 注解 vs XML Mapper
 *
 * 这段代码同时演示注解方式和 XML 模拟方式，直观对比两者的差异。
 */
public class Q02_AnnotationVsXML {

    public static void main(String[] args) {
        System.out.println("=== 面试题 02：注解 vs XML 配置对比 ===\n");

        annotationApproach();
        xmlApproach();
        comparisonTable();
    }

    /**
     * 注解方式：配置和代码紧密绑定
     * 优势：一处修改，代码和配置同步。IDE 智能提示。
     * 劣势：修改配置需要重新编译，不灵活。
     */
    static void annotationApproach() {
        System.out.println("【方式 1：注解配置】\n");

        DataSourceConfig config = new DataSourceConfig();
        ServiceConfig serviceConfig = config.getClass().getAnnotation(ServiceConfig.class);
        Database db = config.getClass().getAnnotation(Database.class);

        System.out.println("服务名:   " + serviceConfig.name());
        System.out.println("端口:     " + serviceConfig.port());
        System.out.println("数据库URL: " + db.url());
        System.out.println("用户名:   " + db.username());
        System.out.println("连接池:    " + db.poolSize());

        executeService(config);
        System.out.println();
    }

    /**
     * XML 方式（模拟，用 Java 代码表示 XML 配置的加载过程）
     * 优势：配置文件可脱离源码修改（热加载、环境切换）。
     * 劣势：配置和代码分离，大型 XML 可读性差，无编译检查。
     */
    static void xmlApproach() {
        System.out.println("【方式 2：XML 配置（模拟）】\n");

        System.out.println("<!-- application.xml -->");
        System.out.println("<service name=\"OrderService\" port=\"9090\">");
        System.out.println("  <database url=\"jdbc:mysql://db2/order\" username=\"order_user\" "
                + "poolSize=\"50\"/>");
        System.out.println("</service>");

        System.out.println();

        SimulatedXmlConfig xmlConfig = new SimulatedXmlConfig();
        xmlConfig.set("service.name", "OrderService");
        xmlConfig.set("service.port", "9090");
        xmlConfig.set("database.url", "jdbc:mysql://db2/order");
        xmlConfig.set("database.username", "order_user");
        xmlConfig.set("database.poolSize", "50");

        System.out.println("XML 加载后的配置对象:");
        System.out.println("服务名:   " + xmlConfig.get("service.name"));
        System.out.println("端口:     " + xmlConfig.get("service.port"));
        System.out.println("数据库URL: " + xmlConfig.get("database.url"));
        System.out.println("用户名:   " + xmlConfig.get("database.username"));
        System.out.println("连接池:    " + xmlConfig.get("database.poolSize"));

        System.out.println("\n关键差异：修改 XML 不需要重新编译代码。");
        System.out.println("但配置项的名称是字符串，写错了编译期不会报错！");
        System.out.println();
    }

    /**
     * 对比总结表
     */
    static void comparisonTable() {
        System.out.println("=== 注解 vs XML 对比总结 ===\n");

        String[][] table = {
                {"维度",         "注解",                "XML"},
                {"类型安全",     "✅ 编译期检查",        "❌ 字符串，运行时才知道错误"},
                {"代码侵入性",   "❌ 侵入源码",          "✅ 零侵入"},
                {"开发效率",     "✅ 高（一处搞定）",     "❌ 低（两处维护）"},
                {"热修改",       "❌ 需重新编译",        "✅ 修改即生效"},
                {"IDE 支持",     "✅ 跳转、重构、提示",   "⚠️ 有限"},
                {"可读性",       "✅ 配置就近",          "❌ 分散时难追踪"},
                {"版本管理",     "✅ 与源码同步",        "❌ 可能与代码版本不一致"},
                {"环境切换",     "⚠️ 需 @Profile",      "✅ 替换文件即可"},
                {"复杂结构配置", "⚠️ 嵌套注解可读性差",  "✅ 层级清晰"},
                {"框架代表",     "Spring Boot, JPA",    "旧版 Spring, MyBatis XML"},
        };

        for (String[] row : table) {
            System.out.printf("%-14s | %-28s | %-28s\n", row[0], row[1], row[2]);
        }

        System.out.println("\n现代实践：注解为主（90%）、XML 为辅（10%）");
        System.out.println("示例：Spring Boot 默认注解驱动，但可通过 @ImportResource 引入 XML");
    }

    static void executeService(DataSourceConfig config) {
        ServiceConfig sc = config.getClass().getAnnotation(ServiceConfig.class);
        Database db = config.getClass().getAnnotation(Database.class);
        System.out.println("\n启动服务: " + sc.name() + " -> " + db.url());
    }
}

/**
 * 注解方式：用自定义注解声明配置
 */
@ServiceConfig(name = "UserService", port = 8080)
@Database(
        url = "jdbc:mysql://localhost:3306/user_db",
        username = "root",
        poolSize = 20
)
class DataSourceConfig {
}

/**
 * 服务配置注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface ServiceConfig {
    String name();
    int port() default 8080;
}

/**
 * 数据库配置注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface Database {
    String url();
    String username();
    int poolSize() default 10;
}

/**
 * 模拟 XML 配置加载后的键值存储
 */
class SimulatedXmlConfig {
    private final java.util.Map<String, String> map = new java.util.HashMap<>();

    void set(String key, String value) {
        map.put(key, value);
    }

    String get(String key) {
        return map.get(key);
    }
}