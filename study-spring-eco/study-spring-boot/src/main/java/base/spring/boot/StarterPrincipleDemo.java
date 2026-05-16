package base.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Map;

/**
 * Starter 机制原理演示。
 * <p>
 * 核心要点：
 * 1. @ConfigurationProperties + prefix 实现属性自动绑定
 * 2. @EnableConfigurationProperties 将属性类注册为 Bean
 * 3. META-INF/spring.factories 中注册 EnableAutoConfiguration
 * 4. @ConditionalOnClass / @ConditionalOnMissingBean 等条件装配确保健壮性
 * </p>
 *
 * <p>本例手写一个 mini starter，定义 CustomProperties（prefix="custom"），
 * 演示属性绑定与自动配置注册的完整流程。</p>
 *
 * @author study-tuling
 */
public class StarterPrincipleDemo {

    public static void main(String[] args) {
        System.out.println("=== 1. @ConfigurationProperties 属性绑定演示 ===");
        demoConfigurationPropertiesBinding();

        System.out.println("\n=== 2. @EnableConfigurationProperties 注册 Bean ===");
        demoEnableConfigurationProperties();

        System.out.println("\n=== 3. application.yml 模拟配置读取 ===");
        demoYamlConfigSimulation();

        System.out.println("\n=== 4. META-INF/spring.factories 注册说明 ===");
        demoSpringFactoriesRegistration();
    }

    /**
     * 通过手动设置 PropertySource 模拟 application.yml 的属性注入，
     * 验证 @ConfigurationProperties 自动绑定机制。
     */
    private static void demoConfigurationPropertiesBinding() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.register(CustomPropertiesConfig.class);

        /* 模拟 application.yml 中的 custom.host / custom.port / custom.timeout */
        ConfigurableEnvironment env = context.getEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("mockYaml", Map.of(
                "custom.host", "192.168.1.100",
                "custom.port", "9090",
                "custom.timeout", "30s"
        )));

        context.refresh();
        CustomProperties properties = context.getBean(CustomProperties.class);
        System.out.println("属性绑定结果：");
        System.out.println("  host    = " + properties.getHost());
        System.out.println("  port    = " + properties.getPort());
        System.out.println("  timeout = " + properties.getTimeout());
        context.close();
    }

    /**
     * 验证 @EnableConfigurationProperties 将配置属性类注册为 Spring Bean。
     */
    private static void demoEnableConfigurationProperties() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(EnablePropsConfig.class);

        System.out.println("通过 @EnableConfigurationProperties 注册的 Bean：");
        for (String name : context.getBeanDefinitionNames()) {
            if (name.contains("custom")) {
                System.out.println("  -> " + name + " : " + context.getBean(name).getClass().getSimpleName());
            }
        }
        CustomProperties props = context.getBean(CustomProperties.class);
        System.out.println("CustomProperties Bean 获取成功，host=" + props.getHost());
        context.close();
    }

    /**
     * 模拟 application.yml 中多个配置项的读取，展示默认值与覆盖行为。
     */
    private static void demoYamlConfigSimulation() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.register(CustomPropertiesConfig.class);

        /* 模拟只配置部分属性的场景 */
        ConfigurableEnvironment env = context.getEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("partialYaml", Map.of(
                "custom.host", "prod-server.example.com"
                /* 不配置 port 和 timeout，验证默认值 */
        )));

        context.refresh();
        CustomProperties props = context.getBean(CustomProperties.class);
        System.out.println("部分配置场景：");
        System.out.println("  host    = " + props.getHost() + "   ← 来自 application.yml");
        System.out.println("  port    = " + props.getPort() + "   ← 使用默认值");
        System.out.println("  timeout = " + props.getTimeout() + "   ← 使用默认值");
        context.close();
    }

    /**
     * 说明 META-INF/spring.factories 中 EnableAutoConfiguration 注册机制。
     * 以及 Spring Boot 3.x 推荐的新格式。
     */
    private static void demoSpringFactoriesRegistration() {
        System.out.println("传统 spring.factories 注册（Spring Boot 2.x）：");
        System.out.println("  文件位置：META-INF/spring.factories");
        System.out.println("  内容示例：");
        System.out.println("    org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\");
        System.out.println("    com.example.CustomAutoConfiguration");

        System.out.println("\nSpring Boot 3.x 推荐新格式：");
        System.out.println("  文件位置：META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        System.out.println("  内容示例：");
        System.out.println("    com.example.CustomAutoConfiguration");
        System.out.println("    com.example.AnotherAutoConfiguration");

        System.out.println("\nmini starter 结构：");
        System.out.println("  custom-spring-boot-starter/");
        System.out.println("    pom.xml                  ← 引入 spring-boot-starter + 所需依赖");
        System.out.println("    src/main/java/");
        System.out.println("      CustomProperties.java           ← @ConfigurationProperties(prefix=\"custom\")");
        System.out.println("      CustomAutoConfiguration.java    ← @Configuration + @EnableConfigurationProperties");
        System.out.println("      CustomService.java              ← 对外暴露的核心服务");
        System.out.println("    src/main/resources/");
        System.out.println("      META-INF/spring/");
        System.out.println("        org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    }

    /* ========== 配置属性类 ========== */

    /**
     * 自定义配置属性类，prefix 为 "custom"。
     * 自动绑定 application.yml / application.properties 中的
     * custom.host、custom.port、custom.timeout。
     */
    @ConfigurationProperties(prefix = "custom")
    static class CustomProperties {

        /** 服务主机地址，默认 localhost */
        private String host = "localhost";

        /** 服务端口，默认 8080 */
        private int port = 8080;

        /** 超时时间，默认 5s */
        private String timeout = "5s";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getTimeout() {
            return timeout;
        }

        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }
    }

    /* ========== 配置类 ========== */

    /**
     * 使用 @EnableConfigurationProperties 注册 CustomProperties 为 Bean。
     * Spring Boot 2.2+ 推荐用 @ConfigurationPropertiesScan 替代。
     */
    @Configuration
    @EnableConfigurationProperties(CustomProperties.class)
    static class CustomPropertiesConfig {

        @Bean
        String customServiceStub(CustomProperties properties) {
            System.out.println("[CustomPropertiesConfig] 创建 customServiceStub");
            return "StubService -> " + properties.getHost() + ":" + properties.getPort();
        }
    }

    /**
     * 仅使用 @EnableConfigurationProperties，无额外 Bean，
     * 验证属性类本身被正确注册。
     */
    @Configuration
    @EnableConfigurationProperties(CustomProperties.class)
    static class EnablePropsConfig {
    }
}