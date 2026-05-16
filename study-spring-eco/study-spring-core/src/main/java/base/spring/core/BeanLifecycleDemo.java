package base.spring.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Bean 生命周期 13 步全流程演示
 * 完整走一遍: 构造器 -> 属性注入 -> Aware 回调 -> BeanPostProcessor#before
 * -> @PostConstruct -> InitializingBean -> initMethod -> BeanPostProcessor#after
 * -> 使用 -> @PreDestroy -> DisposableBean -> destroyMethod
 * 同时体现 BeanPostProcessor 是 Spring AOP 的根基: 在这里生成代理对象
 */
public class BeanLifecycleDemo {

    /**
     * 启动 Spring 容器, 触发完整的 Bean 生命周期,
     * 各阶段通过 System.out 标记步骤序号与调用时机
     */
    public static void main(String[] args) {
        System.out.println("=== Bean Lifecycle 10 Steps Demo ===\n");
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LifecycleConfig.class);
        LifecycleBean bean = context.getBean(LifecycleBean.class);
        System.out.println("\n[8] bean ready, use it: " + bean.getName());
        System.out.println("\n--- closing context ---");
        context.close();
    }

    /**
     * 配置类 -- 同时通过 @Bean(initMethod/destroyMethod) 和
     * BeanPostProcessor 两种扩展点介入 Bean 生命周期
     */
    @Configuration
    static class LifecycleConfig {

        @Bean(initMethod = "customInit", destroyMethod = "customDestroy")
        LifecycleBean lifecycleBean() {
            return new LifecycleBean();
        }

        @Bean
        static CustomBeanPostProcessor customBeanPostProcessor() {
            return new CustomBeanPostProcessor();
        }
    }

    /**
     * 实验 Bean -- 同时实现 InitializingBean、DisposableBean、BeanNameAware 三大接口
     * 配合 @PostConstruct/@PreDestroy 注解 + XML 风格的 initMethod/destroyMethod
     * 用来验证 Spring 对这些生命周期回调的执行顺序
     */
    static class LifecycleBean implements InitializingBean, DisposableBean, BeanNameAware {

        private String name;
        private String beanName;

        public LifecycleBean() {
            System.out.println("[0] constructor invoked");
        }

        public void setName(String name) {
            this.name = name;
            System.out.println("[1] property injection: setName(\"" + name + "\")");
        }

        @Override
        public void setBeanName(String beanName) {
            this.beanName = beanName;
            System.out.println("[2] BeanNameAware.setBeanName(\"" + beanName + "\")");
        }

        @PostConstruct
        public void postConstruct() {
            System.out.println("[4] @PostConstruct method invoked");
        }

        @Override
        public void afterPropertiesSet() {
            System.out.println("[5] InitializingBean.afterPropertiesSet() invoked");
        }

        public void customInit() {
            System.out.println("[6] @Bean initMethod=\"customInit\" invoked");
        }

        public String getName() {
            return name;
        }

        @PreDestroy
        public void preDestroy() {
            System.out.println("[9] @PreDestroy method invoked");
        }

        @Override
        public void destroy() {
            System.out.println("[10] DisposableBean.destroy() invoked");
        }

        public void customDestroy() {
            System.out.println("[11] @Bean destroyMethod=\"customDestroy\" invoked");
        }
    }

    /**
     * 自定义 BeanPostProcessor -- 每个 Bean 初始化前后都会被调用
     * before: 注入属性 (模拟依赖注入)
     * after: 可以生成代理 (AOP 的切入点, 但本例不做代理)
     */
    static class CustomBeanPostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName)
                throws BeansException {
            if (bean instanceof LifecycleBean) {
                System.out.println("[3] BeanPostProcessor.postProcessBeforeInitialization()");
                ((LifecycleBean) bean).setName("SpringLearner");
            }
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName)
                throws BeansException {
            if (bean instanceof LifecycleBean) {
                System.out.println("[7] BeanPostProcessor.postProcessAfterInitialization()");
            }
            return bean;
        }
    }
}