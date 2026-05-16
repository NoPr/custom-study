package base.spring.core.interview;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 面试题: BeanFactory vs ApplicationContext + Spring 三级缓存解决循环依赖
 * 手写三级缓存: singletonObjects(L1) / earlySingletonObjects(L2) / singletonFactories(L3)
 * 演示 A 依赖 B、B 依赖 A 的循环引用被正确解析的全过程
 * 核心: 构造器注入无法解决循环依赖 (需要提前暴露引用), setter 注入可以
 */
public class Q01_BeanFactory_ApplicationContext {

    /**
     * 创建 A <-> B 循环依赖, 通过三级缓存逐级查找完成注入
     */
    public static void main(String[] args) {
        System.out.println("=== BeanFactory vs ApplicationContext ===\n");
        System.out.println("BeanFactory: lazy init, getBean() triggers creation");
        System.out.println("ApplicationContext: eager init by default, "
                + "all singletons created on startup\n");

        ThreeLevelCache cache = new ThreeLevelCache();
        cache.registerBeanDefinition("a", A.class);
        cache.registerBeanDefinition("b", B.class);

        System.out.println("--- resolving circular dependency A <-> B ---");
        A a = cache.getBean("a");
        System.out.println("A resolved: " + a);
        System.out.println("A.b = " + (a.b != null ? "resolved" : "null"));
        B b = cache.getBean("b");
        System.out.println("B resolved: " + b);
        System.out.println("B.a = " + (b.a != null ? "resolved" : "null"));
        System.out.println("A.b == b: " + (a.b == b));
    }

    /**
     * Spring 三级缓存手写版 -- 模拟 Spring 解决循环依赖的核心机制
     * L1 singletonObjects: 完全初始化好的单例
     * L2 earlySingletonObjects: 提前暴露的半成品 (已创建但未注入)
     * L3 singletonFactories: ObjectFactory 工厂, getObject() 产生提前引用
     */
    static class ThreeLevelCache {

        /** L1 缓存: 完全初始化好的单例 Bean */
        private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
        /** L2 缓存: 提前暴露的半成品引用 (用于解决循环依赖) */
        private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();
        /** L3 缓存: 可产生提前引用的 ObjectFactory */
        private final Map<String, Function<String, Object>> singletonFactories
                = new ConcurrentHashMap<>();
        private final Map<String, Class<?>> beanDefinitions = new ConcurrentHashMap<>();

        void registerBeanDefinition(String name, Class<?> clazz) {
            beanDefinitions.put(name, clazz);
        }

        <T> T getBean(String name) {
            Object singleton = getSingleton(name);
            if (singleton != null) {
                System.out.println("  [getBean] " + name + " found in cache, return directly");
                return (T) singleton;
            }
            System.out.println("  [getBean] " + name + " not in cache, creating...");
            return (T) createBean(name);
        }

        private Object getSingleton(String name) {
            Object bean = singletonObjects.get(name);
            if (bean != null) {
                System.out.println("  [getSingleton] " + name + " found in L1(singletonObjects)");
                return bean;
            }
            bean = earlySingletonObjects.get(name);
            if (bean != null) {
                System.out.println("  [getSingleton] " + name
                        + " found in L2(earlySingletonObjects)");
                return bean;
            }
            Function<String, Object> factory = singletonFactories.get(name);
            if (factory != null) {
                System.out.println("  [getSingleton] " + name
                        + " found in L3(singletonFactories), invoking factory");
                bean = factory.apply(name);
                earlySingletonObjects.put(name, bean);
                singletonFactories.remove(name);
                System.out.println("  [getSingleton] " + name
                        + " moved L3->L2 (earlySingletonObjects)");
                return bean;
            }
            return null;
        }

        private Object createBean(String name) {
            Class<?> clazz = beanDefinitions.get(name);
            if (clazz == null) {
                throw new RuntimeException("no bean definition: " + name);
            }

            String beforeCreation = name + "_beforeCreate";
            singletonFactories.put(name, (beanName) -> {
                System.out.println("  [L3-factory] creating early reference for " + beanName);
                try {
                    Object earlyObj = clazz.getDeclaredConstructor().newInstance();
                    populateEarlyProperties(earlyObj, beanName);
                    System.out.println("  [L3-factory] early ref ready, adding " + beanName
                            + " to L2");
                    return earlyObj;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            System.out.println("  [createBean] " + name + " added to L3(singletonFactories)");

            try {
                Object instance = clazz.getDeclaredConstructor().newInstance();
                System.out.println("  [createBean] " + name + " constructed");

                populateProperties(instance, name);

                singletonObjects.put(name, instance);
                earlySingletonObjects.remove(name);
                singletonFactories.remove(name);
                System.out.println("  [createBean] " + name
                        + " fully ready, added to L1(singletonObjects)");
                return instance;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void populateProperties(Object instance, String name) throws Exception {
            if (instance instanceof A) {
                B b = getBean("b");
                ((A) instance).b = b;
            } else if (instance instanceof B) {
                A a = getBean("a");
                ((B) instance).a = a;
            }
        }

        private void populateEarlyProperties(Object instance, String name) {
        }
    }

    /** 循环依赖中的 A -- 持有 B 的引用, 在 populateProperties 时注入 */
    static class A {
        B b;
    }

    /** 循环依赖中的 B -- 持有 A 的引用, 在 populateProperties 时注入 */
    static class B {
        A a;
    }
}