package base.spring.core;

import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * AOP 底层代理机制: JDK 动态代理 vs CGLIB 代理对比
 * JDK 代理只能代理接口 (Proxy.newProxyInstance + InvocationHandler)
 * CGLIB 代理可以代理普通类 (Enhancer + MethodInterceptor, 通过 ASM 生成子类)
 * Spring 默认: 有接口用 JDK, 无接口用 CGLIB; Spring Boot 2.x+ 默认统一用 CGLIB
 */
public class AOPDemo {

    /**
     * 分别演示 JDK 代理和 CGLIB 代理, 并故意尝试用 JDK 代理一个没有接口的类来证明限制
     */
    public static void main(String[] args) {
        System.out.println("=== JDK Dynamic Proxy ===");
        Greeting target = new GreetingImpl();
        System.out.println("target class: " + target.getClass().getName());
        System.out.println("implements Greeting interface: " + (target instanceof Greeting));

        Greeting jdkProxy = (Greeting) Proxy.newProxyInstance(
                Greeting.class.getClassLoader(),
                new Class<?>[]{Greeting.class},
                new LogInvocationHandler(target));
        System.out.println("jdk proxy class: " + jdkProxy.getClass().getName());
        System.out.println("jdk proxy instanceof Greeting: " + (jdkProxy instanceof Greeting));
        String jdkResult = jdkProxy.sayHello("JDK");
        System.out.println("jdk result: " + jdkResult);

        System.out.println("\n=== JDK Proxy ONLY works with interfaces ===");
        try {
            Proxy.newProxyInstance(
                    NoInterfaceService.class.getClassLoader(),
                    new Class<?>[]{NoInterfaceService.class},
                    new LogInvocationHandler(new NoInterfaceService()));
            System.out.println("UNEXPECTED: proxy created for class without interface");
        } catch (IllegalArgumentException e) {
            System.out.println("EXPECTED: " + e.getMessage());
        }

        System.out.println("\n=== CGLIB Proxy ===");
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(GreetingImpl.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args1, proxy) -> {
            System.out.println("[CGLIB] before " + method.getName());
            Object result = proxy.invokeSuper(obj, args1);
            System.out.println("[CGLIB] after " + method.getName() + ", result=" + result);
            return result;
        });
        GreetingImpl cglibProxy = (GreetingImpl) enhancer.create();
        System.out.println("cglib proxy class: " + cglibProxy.getClass().getName());
        String cglibResult = cglibProxy.sayHello("CGLIB");
        System.out.println("cglib result: " + cglibResult);

        System.out.println("\n=== CGLIB works with class (no interface) ===");
        Enhancer enhancer2 = new Enhancer();
        enhancer2.setSuperclass(NoInterfaceService.class);
        enhancer2.setCallback((MethodInterceptor) (obj, method, args1, proxy) -> {
            System.out.println("[CGLIB-class] before " + method.getName());
            return proxy.invokeSuper(obj, args1);
        });
        NoInterfaceService noInterfaceProxy = (NoInterfaceService) enhancer2.create();
        System.out.println("cglib class proxy created: " + noInterfaceProxy.getClass().getName());
        noInterfaceProxy.doSomething();
    }

    /** 业务接口 -- JDK 代理要求目标类必须实现接口 */
    interface Greeting {
        String sayHello(String name);
    }

    /** 实现了接口的业务类 -- JDK 和 CGLIB 都能代理 */
    static class GreetingImpl implements Greeting {
        @Override
        public String sayHello(String name) {
            return "Hello, " + name + "!";
        }
    }

    /** 没有接口的普通类 -- 只有 CGLIB 能代理, JDK 代理会抛 IllegalArgumentException */
    static class NoInterfaceService {
        void doSomething() {
            System.out.println("NoInterfaceService.doSomething()");
        }
    }

    /**
     * JDK 动态代理的 InvocationHandler -- 在 method.invoke 前后插入增强逻辑
     * 核心: 持有 target 引用, 反射调用, 打印 before/after 日志
     */
    static class LogInvocationHandler implements InvocationHandler {
        private final Object target;

        LogInvocationHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            System.out.println("[JDK] before " + method.getName());
            Object result = method.invoke(target, args);
            System.out.println("[JDK] after " + method.getName() + ", result=" + result);
            return result;
        }
    }
}