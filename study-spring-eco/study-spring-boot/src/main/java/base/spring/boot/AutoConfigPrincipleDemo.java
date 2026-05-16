package base.spring.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自动配置原理演示。
 * <p>
 * 核心链路：@SpringBootApplication → @EnableAutoConfiguration
 * → AutoConfigurationImportSelector.selectImports()
 * → 读取 spring.factories 或 .imports 文件
 * → 条件过滤（@ConditionalOnClass / @ConditionalOnMissingBean）
 * → 注册 Bean 定义 → 创建 Bean 实例。
 * </p>
 *
 * <p>spring.factories 到 imports 文件演进：
 * JDK 8：spring.factories（键值对，单文件含多种配置）
 * JDK 11：仍用 spring.factories，但开始支持 imports 文件
 * JDK 17+：推荐 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * </p>
 *
 * @author study-tuling
 */
public class AutoConfigPrincipleDemo {

    public static void main(String[] args) {
        System.out.println("=== 1. @SpringBootApplication 拆解 ===");
        demoSpringBootApplicationSplit();

        System.out.println("\n=== 2. 手写 SimpleEnableAutoConfig + ImportSelector 机制 ===");
        demoSimpleAutoConfig();

        System.out.println("\n=== 3. SpringApplication.run() vs new SpringApplication().run() ===");
        demoSpringApplicationDifference();

        System.out.println("\n=== 4. spring.factories vs .imports 文件演进 ===");
        demoFactoriesVsImports();
    }

    /**
     * 拆解 @SpringBootApplication 注解。
     * <p>
     * @SpringBootApplication = @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan
     * 三个核心职责分离：配置类标识 / 自动配置触发 / 组件扫描。
     * </p>
     */
    private static void demoSpringBootApplicationSplit() {
        System.out.println("@SpringBootApplication 等价于：");
        System.out.println("  @SpringBootConfiguration  ← 继承 @Configuration，标记配置类");
        System.out.println("  @EnableAutoConfiguration  ← 触发自动配置，导入 AutoConfigurationImportSelector");
        System.out.println("  @ComponentScan            ← 扫描当前包及子包的 @Component / @Service 等");
        System.out.println("\n每个部分可独立使用：");
        System.out.println("  @Configuration + @ComponentScan(\"com.example\") + @EnableAutoConfiguration");
    }

    /**
     * 手写 SimpleEnableAutoConfig 注解 + SimpleAutoConfigImportSelector 模拟 ImportSelector 机制。
     * 演示自定义注解如何通过 @Import 导入 ImportSelector 实现类，从而动态注册 Bean。
     */
    private static void demoSimpleAutoConfig() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(SimpleDemoConfig.class);
        System.out.println("容器中所有 Bean：");
        for (String name : context.getBeanDefinitionNames()) {
            System.out.println("  -> " + name);
        }
        /* SimpleAutoConfigImportSelector 应注册了 autoHelloService */
        if (context.containsBean("autoHelloService")) {
            Object bean = context.getBean("autoHelloService");
            System.out.println("自定义 ImportSelector 注册成功: " + bean);
        }
        context.close();
    }

    /**
     * 演示 SpringApplication.run() 与 new SpringApplication().run() 的区别。
     * new SpringApplication() 可以先自定义配置（Banner、监听器、环境等），再 run。
     */
    private static void demoSpringApplicationDifference() {
        System.out.println("方式一: SpringApplication.run(主类, args)");
        System.out.println("  → 内部 new SpringApplication(primarySources).run(args)，");
        System.out.println("    无法在 run 前自定义 ApplicationContext。");

        System.out.println("\n方式二: new SpringApplication(主类).run(args)");
        System.out.println("  → 可以在 run 前 setBannerMode() / addListeners() / setEnvironment() 等。");
        System.out.println("  → 适合需要精细控制启动流程的场景。");

        System.out.println("\n实际演示（非 Web 模式，快速启动）：");
        SpringApplication app = new SpringApplication(AutoConfigPrincipleDemo.class);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        try (var context = app.run()) {
            System.out.println("SpringApplication 启动成功，类型: " + context.getClass().getSimpleName());
        }
    }

    /**
     * spring.factories vs META-INF/spring/...AutoConfiguration.imports 文件演进说明。
     */
    private static void demoFactoriesVsImports() {
        System.out.println("JDK 8 时代：META-INF/spring.factories");
        System.out.println("  格式: org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\");
        System.out.println("        com.example.FooAutoConfig,\\");
        System.out.println("        com.example.BarAutoConfig");
        System.out.println("  问题：单一文件承载多种 SPI，key 冲突风险，文件膨胀。");

        System.out.println("\nJDK 11-17 时代：META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        System.out.println("  格式：每行一个全限定类名，简洁清晰");
        System.out.println("  com.example.FooAutoConfig");
        System.out.println("  com.example.BarAutoConfig");
        System.out.println("  优点：职责单一，无 key 概念，编辑器友好。");

        System.out.println("\nSpring Boot 3.x 同时兼容两种格式，但推荐使用 .imports 文件。");
    }

    /* ========== 自定义 ImportSelector 演示 ========== */

    /**
     * 手写 SimpleAutoConfigImportSelector，实现 ImportSelector 接口。
     * 模拟 Spring 的 AutoConfigurationImportSelector 核心逻辑。
     */
    static class SimpleAutoConfigImportSelector implements ImportSelector {

        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            System.out.println("[SimpleAutoConfigImportSelector] selectImports() 被调用");
            /* 模拟从 spring.factories / imports 文件读取配置类 */
            return new String[]{SimpleAutoConfiguration.class.getName()};
        }
    }

    /**
     * 被 ImportSelector 导入的模拟自动配置类。
     */
    @Configuration
    static class SimpleAutoConfiguration {

        @Bean
        String autoHelloService() {
            System.out.println("[SimpleAutoConfiguration] 注册 autoHelloService Bean");
            return "Hello from SimpleAutoConfiguration!";
        }
    }

    /**
     * 手写 SimpleEnableAutoConfig 注解，模拟 @EnableAutoConfiguration。
     * 通过 @Import(SimpleAutoConfigImportSelector.class) 触发 ImportSelector。
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @Import(SimpleAutoConfigImportSelector.class)
    @interface SimpleEnableAutoConfig {
    }

    /**
     * 演示用配置类，使用手写 @SimpleEnableAutoConfig。
     */
    @Configuration
    @SimpleEnableAutoConfig
    static class SimpleDemoConfig {
    }
}