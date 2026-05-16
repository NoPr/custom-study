package base.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * @Conditional 全家桶演示。
 * <p>
 * 展示 Spring Boot 条件装配的核心注解及其判断逻辑，
 * 并手写 SimpleCondition 接口 + SimpleConditional 注解模拟 Spring 机制。
 * </p>
 *
 * <p>条件装配优先级顺序（从高到低）：
 * 1. @ConditionalOnClass — 类路径中是否存在指定类
 * 2. @ConditionalOnMissingBean — 容器中是否缺少指定 Bean
 * 3. @ConditionalOnProperty — 配置文件中是否存在指定属性及值
 * 4. @Profile — 当前激活的 Profile（优先级最低）
 * </p>
 *
 * @author study-tuling
 */
public class ConditionalDemo {

    public static void main(String[] args) {
        System.out.println("=== 1. @ConditionalOnClass 演示 ===");
        demoConditionalOnClass();

        System.out.println("\n=== 2. @ConditionalOnMissingBean 演示 ===");
        demoConditionalOnMissingBean();

        System.out.println("\n=== 3. @ConditionalOnProperty 演示 ===");
        demoConditionalOnProperty();

        System.out.println("\n=== 4. 手写 SimpleCondition + SimpleConditional 注解 ===");
        demoSimpleConditional();

        System.out.println("\n=== 5. 条件装配优先级验证 ===");
        demoConditionalPriority();
    }

    /**
     * @ConditionalOnClass：类路径中有某个类时才装配。
     * 演示 RedisTemplate 仅在 Redis 依赖存在时创建。
     * 本例用 String.class 代替（一定存在），演示肯定场景；
     * 用不存在的类名演示否定场景。
     */
    private static void demoConditionalOnClass() {
        /* 场景一：String.class 存在，所以 bean 被注册 */
        AnnotationConfigApplicationContext ctx1 =
                new AnnotationConfigApplicationContext(OnClassConfig.class);
        System.out.println("[String.class 存在] hasConditionalBean = "
                + ctx1.containsBean("conditionalOnClassBean"));
        ctx1.close();

        /* 场景二：com.nonexist.Clazz 不存在，所以 bean 不会被注册 */
        System.out.println("[com.nonexist.Clazz 不存在] 控制台应无 'nonexist bean registered' "
                + "消息（bean 未创建）");
        AnnotationConfigApplicationContext ctx2 =
                new AnnotationConfigApplicationContext(OnMissingClassConfig.class);
        System.out.println("[不存在的类] hasConditionalBean = "
                + ctx2.containsBean("conditionalOnMissingClassBean"));
        ctx2.close();
    }

    /**
     * @ConditionalOnMissingBean：容器中没有指定 Bean 时才装配。
     * 典型场景：用户自定义 DataSource 时，自动配置的 DataSource 就不会创建。
     */
    private static void demoConditionalOnMissingBean() {
        /* 场景一：容器中没有 defaultDS，所以装配默认 DataSource */
        AnnotationConfigApplicationContext ctx1 =
                new AnnotationConfigApplicationContext(DefaultDataSourceConfig.class);
        System.out.println("[无用户自定义 DS] hasDefaultDataSource = "
                + ctx1.containsBean("dataSource"));
        if (ctx1.containsBean("dataSource")) {
            System.out.println("  → 使用自动配置的默认 DataSource: " + ctx1.getBean("dataSource"));
        }
        ctx1.close();

        /* 场景二：用户定义了 customDataSource，默认 DataSource 不会被创建 */
        AnnotationConfigApplicationContext ctx2 =
                new AnnotationConfigApplicationContext(CustomDataSourceConfig.class);
        System.out.println("[有用户自定义 DS] hasDefaultDataSource = "
                + ctx2.containsBean("dataSource"));
        if (ctx2.containsBean("customDataSource")) {
            System.out.println("  → 使用用户自定义的 DataSource: " + ctx2.getBean("customDataSource"));
        }
        ctx2.close();
    }

    /**
     * @ConditionalOnProperty：根据配置开关控制功能启用。
     * 演示 feature.enabled=true 时注册 Bean，false 时不注册。
     */
    private static void demoConditionalOnProperty() {
        /* 场景一：feature.enabled=true */
        AnnotationConfigApplicationContext ctx1 =
                new AnnotationConfigApplicationContext();
        ctx1.register(FeatureToggleConfig.class);
        ctx1.getEnvironment().getSystemProperties()
                .put("feature.enabled", "true");
        ctx1.refresh();
        System.out.println("[feature.enabled=true] hasFeatureBean = "
                + ctx1.containsBean("featureService"));
        ctx1.close();

        /* 场景二：feature.enabled=false */
        AnnotationConfigApplicationContext ctx2 =
                new AnnotationConfigApplicationContext();
        ctx2.register(FeatureToggleConfig.class);
        ctx2.getEnvironment().getSystemProperties()
                .put("feature.enabled", "false");
        ctx2.refresh();
        System.out.println("[feature.enabled=false] hasFeatureBean = "
                + ctx2.containsBean("featureService"));
        ctx2.close();
    }

    /**
     * 手写 SimpleCondition 接口 + SimpleConditional 注解，
     * 完整模仿 Spring @Conditional 的条件装配机制。
     */
    private static void demoSimpleConditional() {
        /* 场景一：系统属性 os.name 包含 "Windows"，条件满足 */
        AnnotationConfigApplicationContext ctx1 =
                new AnnotationConfigApplicationContext(SimpleConditionalConfig.class);
        System.out.println("[os.name 包含 Windows] hasSimpleBean = "
                + ctx1.containsBean("simpleConditionalBean"));
        if (ctx1.containsBean("simpleConditionalBean")) {
            System.out.println("  → " + ctx1.getBean("simpleConditionalBean"));
        }
        ctx1.close();
    }

    /**
     * 验证条件装配的优先级顺序：
     * @ConditionalOnClass > @ConditionalOnMissingBean > @ConditionalOnProperty。
     * 当多个条件同时不满足时，最先检查的条件决定是否注册。
     */
    private static void demoConditionalPriority() {
        System.out.println("条件装配优先级（Spring Boot 实际判断顺序）：");
        System.out.println("  1. @ConditionalOnClass       ← 类路径检查（最高优先级）");
        System.out.println("  2. @ConditionalOnMissingBean ← 容器中 Bean 存在性检查");
        System.out.println("  3. @ConditionalOnProperty    ← 配置属性匹配检查");
        System.out.println("  4. @Profile                  ← Profile 匹配检查（最低优先级）");
        System.out.println();
        System.out.println("规则：一旦某个条件不满足，后续条件不再检查，Bean 直接跳过。");
        System.out.println("      所有条件都满足，Bean 才会被注册。");
        System.out.println("      这体现了短路求值（short-circuit）思想。");
    }

    /* ========== @ConditionalOnClass 演示配置 ========== */

    @Configuration
    static class OnClassConfig {

        @Bean
        @ConditionalOnClass(name = "java.lang.String")
        String conditionalOnClassBean() {
            System.out.println("  [OnClass] conditionalOnClassBean registered (String exists)");
            return "conditionalOnClassBean";
        }
    }

    @Configuration
    static class OnMissingClassConfig {

        @Bean
        @ConditionalOnClass(name = "com.nonexist.Clazz")
        String conditionalOnMissingClassBean() {
            System.out.println("  [OnClass] conditionalOnMissingClassBean registered (never)");
            return "conditionalOnMissingClassBean";
        }
    }

    /* ========== @ConditionalOnMissingBean 演示配置 ========== */

    @Configuration
    static class DefaultDataSourceConfig {

        @Bean
        @ConditionalOnMissingBean(name = "customDataSource")
        String dataSource() {
            System.out.println("  [OnMissingBean] 自动装配默认 DataSource");
            return "Default-HikariCP-DataSource";
        }
    }

    @Configuration
    static class CustomDataSourceConfig {

        @Bean
        String customDataSource() {
            System.out.println("  [Custom] 用户自定义 DataSource");
            return "Custom-Druid-DataSource";
        }

        @Bean
        @ConditionalOnMissingBean(name = "customDataSource")
        String dataSource() {
            System.out.println("  [OnMissingBean] 默认 DataSource (不会执行)");
            return "Default-HikariCP-DataSource";
        }
    }

    /* ========== @ConditionalOnProperty 演示配置 ========== */

    @Configuration
    static class FeatureToggleConfig {

        @Bean
        @ConditionalOnProperty(name = "feature.enabled", havingValue = "true")
        String featureService() {
            System.out.println("  [OnProperty] featureService registered");
            return "FeatureService-Active";
        }
    }

    /* ========== 手写条件装配机制 ========== */

    /**
     * 手写 SimpleCondition 接口，模仿 Spring Condition 接口。
     * matches 方法返回 true 表示条件满足，Bean 会被注册。
     */
    @FunctionalInterface
    interface SimpleCondition {

        /**
         * 判断条件是否满足。
         *
         * @param context  条件上下文（环境、类加载器等）
         * @param metadata 注解元数据
         * @return true 表示条件满足
         */
        boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata);
    }

    /**
     * 手写 SimpleConditional 注解，模仿 Spring @Conditional 注解。
     * value 指定条件实现类，容器启动时按条件决定是否注册 Bean。
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Conditional(SimpleConditionalDemoCondition.class)
    @interface SimpleConditional {

        /** 条件实现类 */
        Class<? extends SimpleCondition> value();
    }

    /**
     * 演示用的条件实现：仅当操作系统为 Windows 时返回 true。
     */
    static class WindowsCondition implements SimpleCondition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String osName = context.getEnvironment()
                    .getProperty("os.name", "").toLowerCase();
            boolean matches = osName.contains("windows");
            System.out.println("  [WindowsCondition] os.name=" + osName
                    + ", matches=" + matches);
            return matches;
        }
    }

    /**
     * 桥接类：将手写 SimpleCondition 适配到 Spring 的 Condition 接口。
     * 从 @SimpleConditional 注解中读取 value() 并调用 matches()。
     */
    static class SimpleConditionalDemoCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (metadata instanceof AnnotationMetadata annotationMetadata) {
                Map<String, Object> attrs = annotationMetadata
                        .getAnnotationAttributes(SimpleConditional.class.getName());
                if (attrs != null && attrs.containsKey("value")) {
                    Class<?> conditionClass = (Class<?>) attrs.get("value");
                    try {
                        SimpleCondition condition =
                                (SimpleCondition) conditionClass.getDeclaredConstructor().newInstance();
                        return condition.matches(context, metadata);
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    @Configuration
    static class SimpleConditionalConfig {

        @Bean
        @SimpleConditional(WindowsCondition.class)
        String simpleConditionalBean() {
            System.out.println("  [SimpleConditional] simpleConditionalBean registered");
            return "SimpleConditionalBean-Windows";
        }
    }
}