package base.spring.core;

/**
 * @Transactional 事务传播行为 + 自调用失效原理手写模拟
 * 手写了 PlatformTransactionManager、TransactionDefinition、TransactionStatus
 * 演示 REQUIRED (复用已有事务) vs REQUIRES_NEW (挂起并新建) 的区别
 * 同时演示 this.xxx() 自调用绕过代理导致事务失效的经典面试场景
 */
public class TransactionalSourceAnalysis {

    /**
     * 依次演示 REQUIRED 传播、REQUIRES_NEW 传播、自调用失效三种场景
     */
    public static void main(String[] args) {
        System.out.println("=== Transaction Propagation Demo ===\n");

        SimpleDataSource dataSource = new SimpleDataSource();

        System.out.println("--- REQUIRED propagation ---");
        PlatformTransactionManager txManager = new PlatformTransactionManager(dataSource);
        outerMethodRequired(txManager);
        System.out.println("dataSource.commitCount=" + dataSource.commitCount
                + ", rollbackCount=" + dataSource.rollbackCount);

        dataSource.reset();
        System.out.println("\n--- REQUIRES_NEW propagation ---");
        outerMethodRequiresNew(txManager);
        System.out.println("dataSource.commitCount=" + dataSource.commitCount
                + ", rollbackCount=" + dataSource.rollbackCount);

        dataSource.reset();
        System.out.println("\n--- Self-invocation FAILURE demo ---");
        demonstrateSelfInvocationFailure();
    }

    static void outerMethodRequired(PlatformTransactionManager txManager) {
        TransactionStatus outerStatus = txManager.getTransaction(
                new TransactionDefinition(Propagation.REQUIRED));
        try {
            System.out.println("  outer: got tx, status=" + outerStatus);
            TransactionStatus innerStatus = txManager.getTransaction(
                    new TransactionDefinition(Propagation.REQUIRED));
            System.out.println("  inner(REQUIRED): got tx, status=" + innerStatus);
            System.out.println("  inner.isNewTransaction=" + innerStatus.newTransaction);
            txManager.commit(innerStatus);
            txManager.commit(outerStatus);
        } catch (Exception e) {
            txManager.rollback(outerStatus);
        }
    }

    static void outerMethodRequiresNew(PlatformTransactionManager txManager) {
        TransactionStatus outerStatus = txManager.getTransaction(
                new TransactionDefinition(Propagation.REQUIRED));
        try {
            System.out.println("  outer: got tx, status=" + outerStatus);
            TransactionStatus innerStatus = txManager.getTransaction(
                    new TransactionDefinition(Propagation.REQUIRES_NEW));
            System.out.println("  inner(REQUIRES_NEW): got tx, status=" + innerStatus);
            System.out.println("  inner.isNewTransaction=" + innerStatus.newTransaction);
            txManager.commit(innerStatus);
            txManager.commit(outerStatus);
        } catch (Exception e) {
            txManager.rollback(outerStatus);
        }
    }

    static void demonstrateSelfInvocationFailure() {
        EnhancedOrderService service = new EnhancedOrderService();
        service.placeOrderWithLogging();
    }

    /** 简化的传播行为枚举 -- 只保留最常见的两种 */
    enum Propagation {
        REQUIRED, REQUIRES_NEW
    }

    /** 事务定义对象 -- 携带传播行为配置, 对应 Spring 的 TransactionDefinition */
    static class TransactionDefinition {
        final Propagation propagation;

        TransactionDefinition(Propagation propagation) {
            this.propagation = propagation;
        }
    }

    /** 事务状态对象 -- 记录是否新事务、事务 ID, 对应 Spring 的 TransactionStatus */
    static class TransactionStatus {
        /** 是否是新创建的事务 (而非复用的已有事务) */
        final boolean newTransaction;
        final String txId;

        TransactionStatus(boolean newTransaction, String txId) {
            this.newTransaction = newTransaction;
            this.txId = txId;
        }

        @Override
        public String toString() {
            return "TransactionStatus{newTx=" + newTransaction + ", id=" + txId + "}";
        }
    }

    /** 模拟数据源 -- 只计数 commit/rollback 次数, 不操作真实数据库 */
    static class SimpleDataSource {
        int commitCount;
        int rollbackCount;

        void commit() {
            commitCount++;
        }

        void rollback() {
            rollbackCount++;
        }

        void reset() {
            commitCount = 0;
            rollbackCount = 0;
        }
    }

    /**
     * 手写事务管理器 -- 使用 ThreadLocal 绑定当前线程事务,
     * REQUIRED 时复用已有事务, REQUIRES_NEW 时挂起并新建
     */
    static class PlatformTransactionManager {
        private final SimpleDataSource dataSource;
        private final ThreadLocal<TransactionStatus> currentTx = new ThreadLocal<>();
        private int txCounter;

        PlatformTransactionManager(SimpleDataSource dataSource) {
            this.dataSource = dataSource;
        }

        TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionStatus existing = currentTx.get();
            if (existing != null && definition.propagation == Propagation.REQUIRED) {
                System.out.println("    [txManager] reusing existing tx: " + existing.txId);
                return existing;
            }
            if (existing != null && definition.propagation == Propagation.REQUIRES_NEW) {
                System.out.println("    [txManager] suspending " + existing.txId
                        + ", creating new tx");
            }
            String newTxId = "TX-" + (++txCounter);
            TransactionStatus newStatus = new TransactionStatus(true, newTxId);
            currentTx.set(newStatus);
            System.out.println("    [txManager] created new tx: " + newTxId);
            return newStatus;
        }

        void commit(TransactionStatus status) {
            dataSource.commit();
            System.out.println("    [txManager] commit " + status.txId);
        }

        void rollback(TransactionStatus status) {
            dataSource.rollback();
            System.out.println("    [txManager] rollback " + status.txId);
        }
    }

    /** 原始业务类 -- 模拟被 AOP 代理的 Service */
    static class OrderService {
        void placeOrder() {
            System.out.println("  OrderService.placeOrder() - actual business logic");
        }
    }

    /**
     * 模拟 AOP 生成的代理子类 -- 重写 placeOrder() 加入事务增强,
     * placeOrderWithLogging() 内部用 this.placeOrder() 调用绕过了代理,
     * 导致事务增强不生效 -- 这就是 @Transactional 自调用失效的根本原因
     */
    static class EnhancedOrderService extends OrderService {
        @Override
        void placeOrder() {
            System.out.println("  [AOP-proxy] transaction begin");
            super.placeOrder();
            System.out.println("  [AOP-proxy] transaction commit/rollback");
        }

        void placeOrderWithLogging() {
            System.out.println("  placeOrderWithLogging() -> this.placeOrder():");
            System.out.println("  this.getClass()=" + this.getClass().getName());
            this.placeOrder();
            System.out.println("  SELF-INVOCATION: this.placeOrder() bypasses proxy!");
        }
    }
}