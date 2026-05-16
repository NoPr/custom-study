package base.spring.core;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IoC（控制反转）原理手写版 + Spring 容器验证
 * 手写 SimpleBeanFactory 模拟 Bean 定义注册、单例缓存、反射实例化
 * 再通过 AnnotationConfigApplicationContext 对照验证 Spring 容器的实际行为
 * 核心: BeanDefinition 存储元信息, ConcurrentHashMap 实现单例池, 反射完成解耦
 */
public class IoCDemo {

    /**
     * 对比手写容器与 Spring 容器: 先用手写 SimpleBeanFactory 验证单例/反射机制，
     * 再用 AnnotationConfigApplicationContext 演示组件扫描、@Bean 注册、依赖注入
     */
    public static void main(String[] args) {
        System.out.println("=== 1. SimpleBeanFactory ===");
        SimpleBeanFactory simpleFactory = new SimpleBeanFactory();
        simpleFactory.registerBean("userService", UserService.class);
        UserService userService1 = simpleFactory.getBean("userService");
        UserService userService2 = simpleFactory.getBean("userService");
        System.out.println("singleton check: " + (userService1 == userService2));
        userService1.greet();

        System.out.println("\n=== 2. AnnotationConfigApplicationContext ===");
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(AppConfig.class);
        UserService springUserService = context.getBean(UserService.class);
        springUserService.greet();
        OrderService orderService = context.getBean(OrderService.class);
        orderService.placeOrder();
        System.out.println("component scan found: " +
                context.getBeanDefinitionNames().length + " beans");
        context.close();
    }

    /**
     * 手写版 BeanFactory -- 模拟 Spring IoC 容器的核心骨架
     * BeanDefinition 存储元信息 + ConcurrentHashMap 单例池 + 反射 newInstance
     */
    static class SimpleBeanFactory {

        /** Bean 定义存储: beanName -> BeanDefinition */
        private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentHashMap<>();
        /** 单例池: beanName -> 实例对象 */
        private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();

        public void registerBean(String name, Class<?> clazz) {
            beanDefinitions.put(name, new BeanDefinition(clazz));
        }

        public <T> T getBean(String name) {
            BeanDefinition definition = beanDefinitions.get(name);
            if (definition == null) {
                throw new RuntimeException("no bean: " + name);
            }
            if (definition.scope.equals("singleton")) {
                Object instance = singletonObjects.get(name);
                if (instance == null) {
                    instance = createInstance(definition);
                    singletonObjects.put(name, instance);
                }
                return (T) instance;
            }
            return (T) createInstance(definition);
        }

        private Object createInstance(BeanDefinition definition) {
            try {
                return definition.clazz.getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException |
                     InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * 模拟 Spring 的 BeanDefinition: 持有目标类的 Class 对象和 scope 信息
         */
        static class BeanDefinition {
            final Class<?> clazz;
            final String scope = "singleton";

            BeanDefinition(Class<?> clazz) {
                this.clazz = clazz;
            }
        }
    }

    /**
     * 普通 JavaBean -- 用来验证手写容器和 Spring 容器的实例化、单例行为
     */
    static class UserService {
        void greet() {
            System.out.println("UserService.greet() invoked, hash: " + this.hashCode());
        }
    }

    /**
     * Spring 配置类 -- @Bean 显式声明 + @ComponentScan 自动扫描
     */
    @Configuration
    @ComponentScan("base.spring.core")
    static class AppConfig {
        @Bean
        UserService userService() {
            return new UserService();
        }
    }

    /**
     * 被 @Component 标记的类 -- 由 @ComponentScan 自动发现并注册到容器
     */
    @Component
    static class OrderService {
        void placeOrder() {
            System.out.println("OrderService.placeOrder() invoked");
        }
    }
}