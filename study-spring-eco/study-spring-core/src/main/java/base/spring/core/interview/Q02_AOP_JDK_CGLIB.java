package base.spring.core.interview;

import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * 面试题: JDK 动态代理 vs CGLIB 代理 + @Transactional 自调用失效及修复
 * 三个独立演示: JDK/CGLIB 对比、自调用绕过代理、通过注入自身代理修复自调用
 * 核心: JDK 代理基于接口反射, CGLIB 基于 ASM 生成子类继承
 */
public class Q02_AOP_JDK_CGLIB {

    /**
     * 三个场景串行演示, 从对比到问题到修复, 覆盖 AOP 面试高频考点
     */
    public static void main(String[] args) {
        System.out.println("=== JDK vs CGLIB Comparison ===\n");
        demonstrateJdkVsCglib();
        System.out.println("\n=== @Transactional Self-Invocation Failure ===\n");
        demonstrateSelfInvocationFailure();
        System.out.println("\n=== Self-Invocation Fix: Inject Proxy ===\n");
        demonstrateSelfInvocationFix();
    }

    static void demonstrateJdkVsCglib() {
        UserService target = new UserServiceImpl();
        System.out.println("target class: " + target.getClass().getSimpleName());

        UserService jdkProxy = (UserService) Proxy.newProxyInstance(
                UserService.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                (proxy, method, args1) -> {
                    System.out.println("[JDK-proxy] before " + method.getName());
                    Object result = method.invoke(target, args1);
                    System.out.println("[JDK-proxy] after " + method.getName());
                    return result;
                });
        System.out.println("JDK proxy class: " + jdkProxy.getClass().getName());
        jdkProxy.save();

        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(UserServiceImpl.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args1, proxy) -> {
            System.out.println("[CGLIB-proxy] before " + method.getName());
            Object result = proxy.invokeSuper(obj, args1);
            System.out.println("[CGLIB-proxy] after " + method.getName());
            return result;
        });
        UserServiceImpl cglibProxy = (UserServiceImpl) enhancer.create();
        System.out.println("CGLIB proxy class: " + cglibProxy.getClass().getName());
        cglibProxy.save();

        System.out.println("\nJDK proxy: interface-based, " +
                "Proxy.newProxyInstance, $ProxyX extends Proxy implements Interface");
        System.out.println("CGLIB proxy: class-based, Enhancer, " +
                "$$EnhancerByCGLIB$$ extends TargetClass, uses ASM bytecode");
    }

    static void demonstrateSelfInvocationFailure() {
        TransactionalProxy proxy = new TransactionalProxy(new OrderService());
        OrderService proxiedService = proxy.getProxy();

        System.out.println("proxy class: " + proxiedService.getClass().getName());
        System.out.println("--- calling proxied version (proxy.createOrder) ---");
        proxiedService.createOrder();
        System.out.println("proxy.createOrder invocation count: "
                + proxy.getInvocationCount());

        System.out.println("\n--- calling unproxied raw (raw.createOrder) ---");
        OrderService rawService = new OrderService();
        rawService.createOrder();
    }

    static void demonstrateSelfInvocationFix() {
        SelfAwareServiceImpl service = new SelfAwareServiceImpl();
        final SelfAwareService[] selfRef = new SelfAwareService[1];

        SelfAwareService fixedProxy = (SelfAwareService) Proxy.newProxyInstance(
                SelfAwareService.class.getClassLoader(),
                new Class<?>[]{SelfAwareService.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        if ("setSelf".equals(method.getName())) {
                            selfRef[0] = (SelfAwareService) args[0];
                            return null;
                        }
                        System.out.println("[FIXED-proxy] transactional before "
                                + method.getName());
                        Object result = method.invoke(service, args);
                        System.out.println("[FIXED-proxy] transactional after "
                                + method.getName());
                        return result;
                    }
                });

        fixedProxy.setSelf(fixedProxy);
        System.out.println("proxy class: " + fixedProxy.getClass().getSimpleName());
        System.out.println("--- fixedProxy.createOrder() ---");
        fixedProxy.createOrder();
    }

    /** 用户服务接口 -- JDK 代理的载体 */
    interface UserService {
        void save();
    }

    /** 接口实现类 -- 同时作为 JDK 和 CGLIB 代理的目标对象 */
    static class UserServiceImpl implements UserService {
        @Override
        public void save() {
            System.out.println("  UserServiceImpl.save() doing DB insert");
        }
    }

    /**
     * 订单服务 -- createOrder() 内部调用 this.updateInventory(),
     * 当通过代理调用 createOrder() 时, this.updateInventory() 直接调用自身,
     * 不经过代理, 因此 updateInventory() 上的事务/日志增强全部失效
     */
    static class OrderService {
        void createOrder() {
            System.out.println("  OrderService.createOrder() -> this.updateInventory()");
            this.updateInventory();
        }

        void updateInventory() {
            System.out.println("  OrderService.updateInventory() - should be transactional "
                    + "but self-invocation bypasses proxy!");
        }
    }

    /**
     * 事务代理 -- 模拟 Spring @Transactional 代理, 在方法调用前后加入事务 begin/commit/rollback
     * invocationCount 用来证明: 只调用了一次 createOrder, 事务增强也只执行一次
     */
    static class TransactionalProxy {
        private final Object target;
        private int invocationCount;

        TransactionalProxy(Object target) {
            this.target = target;
        }

        @SuppressWarnings("unchecked")
        <T> T getProxy() {
            return (T) Proxy.newProxyInstance(
                    target.getClass().getClassLoader(),
                    target.getClass().getInterfaces(),
                    (proxy, method, args) -> {
                        invocationCount++;
                        System.out.println("[TransactionalProxy] TX begin for: "
                                + method.getName());
                        try {
                            Object result = method.invoke(target, args);
                            System.out.println("[TransactionalProxy] TX commit for: "
                                    + method.getName());
                            return result;
                        } catch (Exception e) {
                            System.out.println("[TransactionalProxy] TX rollback for: "
                                    + method.getName());
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                    });
        }

        int getInvocationCount() {
            return invocationCount;
        }
    }

    /**
     * 自感知服务接口 -- 通过 setSelf() 注入代理对象,
     * 然后用 self.xxx() 替代 this.xxx() 确保增强生效
     */
    interface SelfAwareService {
        void setSelf(SelfAwareService self);
        void createOrder();
        void updateInventory();
    }

    /**
     * 自感知服务的实现 -- 核心修复: 持有 self (代理引用),
     * createOrder() 通过 self.updateInventory() 调用而非 this.updateInventory()
     */
    static class SelfAwareServiceImpl implements SelfAwareService {
        private SelfAwareService self;

        @Override
        public void setSelf(SelfAwareService self) {
            this.self = self;
        }

        @Override
        public void createOrder() {
            System.out.println("  createOrder() -> self.updateInventory()");
            if (self == null) {
                System.out.println("  self is null, calling this.updateInventory()");
                this.updateInventory();
            } else {
                System.out.println("  self is proxy, calling self.updateInventory()");
                self.updateInventory();
            }
        }

        @Override
        public void updateInventory() {
            System.out.println("  updateInventory() executing");
        }
    }
}