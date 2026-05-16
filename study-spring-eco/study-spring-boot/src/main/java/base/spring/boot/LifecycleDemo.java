package base.spring.boot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot 生命周期演示。
 * <p>
 * 涵盖：
 * 1. ApplicationRunner vs CommandLineRunner 执行顺序
 * 2. SpringApplication 自定义：禁用 Banner、设置 WebApplicationType
 * 3. 内嵌 Tomcat 启动流程证明
 * 4. Spring Boot 优雅关闭机制
 * </p>
 *
 * <p>生命周期顺序：
 * SpringApplication 构造 → Environment 准备 → Banner 打印
 * → ApplicationContext 创建 → Bean 注册 → 内嵌容器启动
 * → ApplicationRunner / CommandLineRunner 执行 → 服务就绪
 * → 关闭钩子 → Bean 销毁 → 容器关闭。
 * </p>
 *
 * @author study-tuling
 */
public class LifecycleDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 1. ApplicationRunner vs CommandLineRunner ===");
        demoRunners();

        System.out.println("\n=== 2. SpringApplication 自定义 ===");
        demoCustomSpringApplication();

        System.out.println("\n=== 3. 内嵌 Tomcat 启动证明 ===");
        demoEmbeddedTomcat();

        System.out.println("\n=== 4. 优雅关闭 GracefulShutdown ===");
        demoGracefulShutdown();
    }

    /**
     * ApplicationRunner vs CommandLineRunner 执行顺序演示。
     * <p>
     * ApplicationRunner：接收 ApplicationArguments，提供解析后的参数访问（optionArgs / nonOptionArgs）。
     * CommandLineRunner：接收原始 String[] args，更底层。
     * </p>
     * <p>
     * 多个 Runner 可通过 @Order 控制顺序，数字越小越先执行。
     * </p>
     */
    private static void demoRunners() {
        SpringApplication app = new SpringApplication(RunnerConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setLogStartupInfo(false);

        try (ConfigurableApplicationContext context =
                     app.run("--server.port=9999", "--app.name=LifecycleDemo")) {
            System.out.println("\nRunner 执行完毕，容器正常运行中。");
            System.out.println("ApplicationRunner 适合需要解析参数的场景。");
            System.out.println("CommandLineRunner 适合简单拿原始 args 的场景。");
        }
    }

    /**
     * SpringApplication 自定义：禁用 Banner、设置 WebApplicationType.NONE。
     * 演示非 Web 模式下 Spring Boot 依然能正常运行。
     */
    private static void demoCustomSpringApplication() {
        SpringApplication app = new SpringApplication(CustomAppConfig.class);

        /* 设置为 NONE，不会启动内嵌 Web 服务器 */
        app.setWebApplicationType(WebApplicationType.NONE);

        /* 关闭启动 Banner */
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);

        /* 关闭启动日志 */
        app.setLogStartupInfo(false);

        /* 允许 Bean 定义覆盖（默认 false，用于开发调试） */
        app.setAllowBeanDefinitionOverriding(true);

        try (ConfigurableApplicationContext context = app.run()) {
            System.out.println("WebApplicationType 设置: "
                    + app.getWebApplicationType());
            System.out.println("容器类型: " + context.getClass().getSimpleName());
            System.out.println("是否为 Web 环境: NONE → 不启动内嵌容器");
        }
    }

    /**
     * 证明 Spring Boot 使用的是内嵌 Tomcat，而非外部容器。
     * <p>
     * 内嵌容器启动流程：
     * ServletWebServerApplicationContext.onRefresh()
     * → createWebServer()
     * → ServletWebServerFactory.getWebServer()
     * → TomcatServletWebServerFactory.getWebServer()
     * → new Tomcat() → tomcat.start()
     * </p>
     */
    private static void demoEmbeddedTomcat() {
        System.out.println("内嵌 Tomcat 启动流程（代码证明）：");
        System.out.println("  1. SpringApplication.run()");
        System.out.println("  2.   → refreshContext(context)");
        System.out.println("  3.     → ServletWebServerApplicationContext.onRefresh()");
        System.out.println("  4.       → createWebServer()");
        System.out.println("  5.         → getWebServerFactory() 获取 TomcatServletWebServerFactory");
        System.out.println("  6.         → factory.getWebServer() 创建 WebServer");
        System.out.println("  7.           → new Tomcat()  ← 内嵌 Tomcat 实例");
        System.out.println("  8.           → tomcat.start() ← 启动内嵌容器");
        System.out.println();
        System.out.println("判断依据：");
        System.out.println("  - 不依赖外部 Tomcat 安装 → 独立 JAR 可直接运行");
        System.out.println("  - spring-boot-starter-web 依赖 spring-boot-starter-tomcat");
        System.out.println("  - 控制台日志: o.s.b.w.embedded.tomcat.TomcatWebServer");
        System.out.println("  - 可通过 spring-boot-starter-undertow/jetty 替换");
    }

    /**
     * Spring Boot 优雅关闭机制演示。
     * <p>
     * 优雅关闭配置：
     * server.shutdown=graceful
     * spring.lifecycle.timeout-per-shutdown-phase=30s
     * </p>
     * <p>
     * 流程：收到 SIGTERM → 拒绝新请求 → 等待进行中的请求完成 → 销毁 Bean → 关闭容器。
     * </p>
     */
    private static void demoGracefulShutdown() throws InterruptedException {
        SpringApplication app = new SpringApplication(GracefulShutdownConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setLogStartupInfo(false);

        ConfigurableApplicationContext context = app.run();

        System.out.println("\n优雅关闭流程：");
        System.out.println("  1. 收到关闭信号（SIGTERM / kill / context.close()）");
        System.out.println("  2. Spring Boot 发布 ContextClosedEvent");
        System.out.println("  3. 停止接收新请求（Web 容器层面）");
        System.out.println("  4. 等待进行中的请求完成（server.shutdown=graceful）");
        System.out.println("  5. 超时控制（spring.lifecycle.timeout-per-shutdown-phase）");
        System.out.println("  6. 按顺序销毁 Bean（@PreDestroy → DisposableBean.destroy()）");
        System.out.println("  7. 关闭 ApplicationContext → JVM 退出\n");

        /* 模拟处理中的请求 */
        System.out.println("模拟：处理中的请求需 2 秒完成...");
        TimeUnit.SECONDS.sleep(2);
        System.out.println("模拟：请求处理完毕，开始关闭...");

        /* 触发优雅关闭 */
        context.close();
        System.out.println("优雅关闭完成。");
    }

    /* ========== Runner 演示配置 ========== */

    @Configuration
    static class RunnerConfig {

        @Bean
        ApplicationRunner applicationRunner() {
            return args -> {
                System.out.println("[ApplicationRunner] 执行，优先级高于 CommandLineRunner");
                System.out.println("  参数解析:");
                System.out.println("    optionArgs: " + args.getOptionNames());
                System.out.println("    server.port = " + args.getOptionValues("server.port"));
                System.out.println("    nonOptionArgs: " + args.getNonOptionArgs());
            };
        }

        @Bean
        CommandLineRunner commandLineRunner() {
            return args -> {
                System.out.println("[CommandLineRunner] 执行，接收原始 String[]");
                System.out.println("  原始参数: " + Arrays.toString(args));
            };
        }
    }

    /* ========== 自定义 SpringApplication 演示配置 ========== */

    @Configuration
    static class CustomAppConfig {

        @Bean
        String customAppBean() {
            System.out.println("[CustomAppConfig] customAppBean 已注册");
            return "CustomAppConfig-Bean";
        }
    }

    /* ========== 优雅关闭演示配置 ========== */

    @Configuration
    static class GracefulShutdownConfig {

        @Bean
        String shutdownDemoBean() {
            System.out.println("[GracefulShutdownConfig] shutdownDemoBean 已注册");
            return "GracefulShutdown-Demo";
        }
    }
}