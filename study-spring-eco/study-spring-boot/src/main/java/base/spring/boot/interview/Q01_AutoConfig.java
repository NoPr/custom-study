package base.spring.boot.interview;

import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

/**
 * 面试高频题：Spring Boot 自动配置原理全流程。
 *
 * <p>从 @SpringBootApplication 一路追踪到 AutoConfigurationImportSelector.selectImports()，
 * 完整梳理自动配置的核心链路。</p>
 *
 * <p>核心源码链路（Spring Boot 3.x）：
 * 1. @SpringBootApplication → @EnableAutoConfiguration
 * 2. @EnableAutoConfiguration → @Import(AutoConfigurationImportSelector.class)
 * 3. AutoConfigurationImportSelector.selectImports() → getAutoConfigurationEntry()
 * 4. getCandidateConfigurations() → SpringFactoriesLoader.loadFactoryNames()
 *    或 ImportCandidates.load()
 * 5. 读取 spring.factories 或 .imports 文件
 * 6. 条件过滤（@ConditionalOnClass / @ConditionalOnMissingBean / @ConditionalOnProperty）
 * 7. 返回需要注册的自动配置类列表 → BeanDefinition 注册 → Bean 创建
 * </p>
 *
 * @author study-tuling
 */
public class Q01_AutoConfig {

    public static void main(String[] args) {
        System.out.println("=== Spring Boot 自动配置面试全解析 ===\n");

        q1SpringBootApplicationTrace();
        q2SpringFactoriesVsImports();
        q3ExclusionMechanism();
        q4AutoConfigConditions();
        q5Summary();
    }

    /**
     * Q1: 从 @SpringBootApplication 一路追踪到 AutoConfigurationImportSelector。
     *
     * <p>
     * 回答要点：
     * @SpringBootApplication 是组合注解，其中 @EnableAutoConfiguration
     * 触发了自动配置。@EnableAutoConfiguration 通过 @Import 导入
     * AutoConfigurationImportSelector，后者在 selectImports() 方法中
     * 读取 spring.factories / .imports 文件，经条件过滤后返回自动配置类列表。
     * </p>
     */
    private static void q1SpringBootApplicationTrace() {
        System.out.println("【Q1】从 @SpringBootApplication 到 AutoConfigurationImportSelector 全链路");
        System.out.println("─".repeat(60));

        System.out.println("第 1 层：@SpringBootApplication");
        System.out.println("  @SpringBootApplication");
        System.out.println("    └── @SpringBootConfiguration   ← 本质是一个 @Configuration");
        System.out.println("    └── @EnableAutoConfiguration   ← 自动配置的入口");
        System.out.println("    └── @ComponentScan             ← 组件扫描\n");

        System.out.println("第 2 层：@EnableAutoConfiguration");
        System.out.println("  @EnableAutoConfiguration");
        System.out.println("    └── @Import(AutoConfigurationImportSelector.class)");
        System.out.println("         ↑ 关键：通过 ImportSelector 机制实现延迟导入\n");

        System.out.println("第 3 层：AutoConfigurationImportSelector.selectImports()");
        System.out.println("  调用链：");
        System.out.println("    selectImports(AnnotationMetadata)");
        System.out.println("      → getAutoConfigurationEntry(annotationMetadata)");
        System.out.println("        → getCandidateConfigurations(annotationMetadata, attributes)");
        System.out.println("          → SpringFactoriesLoader.loadFactoryNames(...)");
        System.out.println("             或 ImportCandidates.load(AutoConfiguration.class, classLoader)");
        System.out.println("            → 读取 spring.factories 或 .imports 文件\n");

        System.out.println("第 4 层：条件过滤");
        System.out.println("  getAutoConfigurationEntry() 中：");
        System.out.println("    configurations = filter(configurations, autoConfigurationMetadata)");
        System.out.println("    → 对每个候选配置类检查 @ConditionalOnXxx 条件");
        System.out.println("    → 不满足条件的配置类被移除\n");

        System.out.println("第 5 层：返回配置类列表");
        System.out.println("  selectImports() 返回 String[]，每个元素是全限定类名");
        System.out.println("  → Spring 容器将其作为配置类处理");
        System.out.println("  → 其中的 @Bean 方法被解析为 BeanDefinition");
        System.out.println("  → Bean 实例被创建并放入容器");
        System.out.println();
    }

    /**
     * Q2: spring.factories vs spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 对比。
     *
     * <p>
     * 回答要点：
     * spring.factories 是 Java SPI 风格的多用途配置文件，键值对格式；
     * .imports 文件是 Spring Boot 2.7+ 引入的专用格式，每行一个类名。
     * Spring Boot 3.x 同时兼容两种，但推荐 .imports。
     * </p>
     */
    private static void q2SpringFactoriesVsImports() {
        System.out.println("【Q2】spring.factories vs .imports 文件对比");
        System.out.println("─".repeat(60));

        System.out.println("对比维度          | spring.factories          | .imports 文件");
        System.out.println("─────────────────┼───────────────────────────┼─────────────────────────");
        System.out.println("引入版本          | Spring Boot 1.x           | Spring Boot 2.7+");
        System.out.println("文件路径          | META-INF/spring.factories | META-INF/spring/...AutoConfiguration.imports");
        System.out.println("格式              | key=value1,\\nvalue2       | 每行一个全限定类名");
        System.out.println("用途              | 通用 SPI（多种 key）       | 专用于自动配置");
        System.out.println("加载方式          | SpringFactoriesLoader     | ImportCandidates.load()");
        System.out.println("key 冲突风险      | 高（单文件多用途）         | 无（专一职责）");
        System.out.println("可读性            | 较差                       | 好");
        System.out.println("Spring Boot 3.x   | 仍支持，向后兼容            | 推荐使用");

        System.out.println("\n示例对比：");

        System.out.println("\nspring.factories:");
        System.out.println("  org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\");
        System.out.println("  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\\");
        System.out.println("  org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration");

        System.out.println("\n.imports 文件:");
        System.out.println("  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
        System.out.println("  org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration");
        System.out.println();
    }

    /**
     * Q3: 为什么需要 exclusion 排除自动配置类。
     *
     * <p>
     * 回答要点：
     * 排除不需要的自动配置类可以避免冲突（如自定义 DataSource 与默认的冲突）、
     * 减少启动时间、防止意外的 Bean 创建。
     * 常见场景：排除 Redis 自动配置（当使用自己的 Redis 客户端）、
     * 排除 AOP 自动配置（当不需要代理）、排除 DataSource 自动配置（多数据源场景）。
     * </p>
     */
    private static void q3ExclusionMechanism() {
        System.out.println("【Q3】自动配置排除机制（exclusion）");
        System.out.println("─".repeat(60));

        System.out.println("排除方式一：@SpringBootApplication(exclude = ...)");
        System.out.println("  @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)");
        System.out.println("  适用场景：不需要默认 DataSource 时");

        System.out.println("\n排除方式二：@EnableAutoConfiguration(exclude = ...)");
        System.out.println("  @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class})");
        System.out.println("  适用场景：不需要 Redis 自动配置时");

        System.out.println("\n排除方式三：spring.autoconfigure.exclude 配置属性");
        System.out.println("  spring.autoconfigure.exclude=");
        System.out.println("    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
        System.out.println("  适用场景：通过外部化配置控制，无需修改代码");

        System.out.println("\n常见排除场景：");
        System.out.println("  1. 排除 DataSourceAutoConfiguration");
        System.out.println("     → 原因：多数据源、手动配置 JdbcTemplate、使用 JNDI");
        System.out.println("  2. 排除 RedisAutoConfiguration");
        System.out.println("     → 原因：自定义 Redis 客户端、不使用 Redis");
        System.out.println("  3. 排除 AopAutoConfiguration");
        System.out.println("     → 原因：不需要 AOP 代理，减少启动开销");
        System.out.println("  4. 排除 SecurityAutoConfiguration");
        System.out.println("     → 原因：自定义安全配置、无安全需求");
        System.out.println("  5. 排除 ThymeleafAutoConfiguration");
        System.out.println("     → 原因：使用其他模板引擎或纯 REST API\n");

        System.out.println("排除原理（源码层面）：");
        System.out.println("  AutoConfigurationImportSelector.getAutoConfigurationEntry() 中：");
        System.out.println("    exclusions = getExclusions(annotationMetadata, attributes)");
        System.out.println("    configurations.removeAll(exclusions)");
        System.out.println("  → 被排除的配置类从候选列表中直接移除，不会被条件过滤、不会被注册");
        System.out.println();
    }

    /**
     * Q4: 自动配置的条件过滤机制详解。
     *
     * <p>
     * 常用条件注解及其执行时机：
     * @ConditionalOnClass — 类路径检查（最优先）
     * @ConditionalOnMissingBean — Bean 存在性检查
     * @ConditionalOnProperty — 配置属性检查
     * @ConditionalOnBean — 依赖 Bean 存在性检查
     * @ConditionalOnResource — 资源文件存在性检查
     * @ConditionalOnWebApplication — Web 环境检查
     * </p>
     */
    private static void q4AutoConfigConditions() {
        System.out.println("【Q4】自动配置条件过滤机制");
        System.out.println("─".repeat(60));

        System.out.println("自动配置类的典型条件组合模式：");
        System.out.println();

        System.out.println("示例一：DataSourceAutoConfiguration（数据源自动配置）");
        System.out.println("  @AutoConfiguration");
        System.out.println("  @ConditionalOnClass({DataSource.class, EmbeddedDatabaseType.class})");
        System.out.println("  @EnableConfigurationProperties(DataSourceProperties.class)");
        System.out.println("  @Import({DataSourcePoolMetadataProvidersConfiguration.class, ...})");
        System.out.println("  条件解读：");
        System.out.println("    - javax.sql.DataSource 必须在类路径上（有 JDBC 依赖）");
        System.out.println("    - 绑定 spring.datasource.* 属性");

        System.out.println("\n示例二：RedisAutoConfiguration（Redis 自动配置）");
        System.out.println("  @AutoConfiguration");
        System.out.println("  @ConditionalOnClass(RedisOperations.class)");
        System.out.println("  @EnableConfigurationProperties(RedisProperties.class)");
        System.out.println("  @Import({LettuceConnectionConfiguration.class, JedisConnectionConfiguration.class})");
        System.out.println("  条件解读：");
        System.out.println("    - org.springframework.data.redis.core.RedisOperations 必须在类路径上");

        System.out.println("\n条件装配的短路求值规则：");
        System.out.println("  多个 @ConditionalOnXxx 之间是 AND 关系");
        System.out.println("  任何一个条件不满足 → 整个配置类被跳过");
        System.out.println("  可以利用此特性做组合条件控制\n");
    }

    /**
     * Q5: 面试总结 —— 一句话概括 + 关键源码位置。
     */
    private static void q5Summary() {
        System.out.println("【Q5】面试总结");
        System.out.println("─".repeat(60));

        System.out.println("一句话概括：");
        System.out.println("  Spring Boot 通过 @EnableAutoConfiguration 注解导入 ");
        System.out.println("  AutoConfigurationImportSelector，该选择器在 selectImports() 中");
        System.out.println("  读取 spring.factories / .imports 文件获取所有自动配置类，");
        System.out.println("  经 @Conditional 条件过滤后，将满足条件的配置类导入容器，");
        System.out.println("  实现\"按需自动配置\"。");

        System.out.println("\n面试时必背的关键类：");
        System.out.println("  1. @SpringBootApplication     — 启动类注解");
        System.out.println("  2. @EnableAutoConfiguration   — 自动配置开关");
        System.out.println("  3. AutoConfigurationImportSelector — 核心选择器");
        System.out.println("  4. SpringFactoriesLoader      — 加载 spring.factories");
        System.out.println("  5. ImportCandidates           — 加载 .imports 文件（Spring Boot 2.7+）");
        System.out.println("  6. @ConditionalOnClass        — 最常用的条件注解");
        System.out.println("  7. @ConditionalOnMissingBean  — 允许用户覆盖的保障");

        System.out.println("\n源码核心方法调用链：");
        System.out.println("  @SpringBootApplication");
        System.out.println("    → @EnableAutoConfiguration");
        System.out.println("      → @Import(AutoConfigurationImportSelector.class)");
        System.out.println("        → selectImports(AnnotationMetadata)");
        System.out.println("          → getAutoConfigurationEntry(AnnotationMetadata)");
        System.out.println("            → getCandidateConfigurations(...)");
        System.out.println("              → SpringFactoriesLoader.loadFactoryNames(...)");
        System.out.println("                → 读取 META-INF/spring.factories");
        System.out.println("              → 或 ImportCandidates.load(AutoConfiguration.class)");
        System.out.println("                → 读取 META-INF/spring/...AutoConfiguration.imports");
        System.out.println("            → filter(configurations)  # 条件过滤");
        System.out.println("            → 返回过滤后的配置类列表");
        System.out.println("          → 配置类被 Spring 容器处理 → Bean 注册 → Bean 创建");
        System.out.println();
    }
}