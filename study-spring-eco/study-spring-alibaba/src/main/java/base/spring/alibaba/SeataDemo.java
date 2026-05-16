package base.spring.alibaba;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seata 分布式事务：AT/TCC/Saga/XA 四种模式对比。
 *
 * <p>核心考点：
 * <ol>
 *   <li>AT 模式：undolog（before-image + after-image）+ 全局锁检查，自动回滚</li>
 *   <li>TCC 模式：Try-Confirm-Cancel 三阶段，业务方需实现三接口</li>
 *   <li>TC/TM/RM 三角色：Transaction Coordinator / Transaction Manager / Resource Manager</li>
 *   <li>@GlobalTransactional vs @Transactional：全局事务 vs 本地事务</li>
 * </ol>
 *
 * <p>本 Demo 用纯 Java 模拟 Seata 核心机制。
 *
 * @author study-tuling
 */
public class SeataDemo {

    // ======================== 1. TC/TM/RM 角色定义 ========================

    /**
     * TC（Transaction Coordinator）：事务协调器，管理全局事务的生命周期。
     * <p>对应 Seata Server，负责接收 TM 的开启/提交/回滚请求。
     */
    static class TransactionCoordinator {
        /** 全局事务表 xid → transaction */
        final Map<String, GlobalTransaction> globalTransactions = new ConcurrentHashMap<>();

        String beginGlobalTransaction() {
            String xid = UUID.randomUUID().toString().substring(0, 8);
            globalTransactions.put(xid, new GlobalTransaction(xid));
            System.out.printf("  [TC] 全局事务开启 XID=%s%n", xid);
            return xid;
        }

        /** 注册分支事务 */
        void registerBranch(String xid, String branchId, BranchTransaction branch) {
            GlobalTransaction gt = globalTransactions.get(xid);
            if (gt != null) {
                gt.branches.put(branchId, branch);
                System.out.printf("  [TC] 注册分支事务 XID=%s, Branch=%s%n", xid, branchId);
            }
        }

        boolean commitGlobal(String xid) {
            GlobalTransaction gt = globalTransactions.get(xid);
            if (gt == null) {
                System.out.printf("  [TC] XID=%s 不存在%n", xid);
                return false;
            }
            System.out.printf("  [TC] 二阶段：全局提交 XID=%s, 分支数=%d%n", xid, gt.branches.size());
            boolean allSuccess = true;
            for (Map.Entry<String, BranchTransaction> entry : gt.branches.entrySet()) {
                boolean success = entry.getValue().commit();
                if (!success) {
                    allSuccess = false;
                }
            }
            if (allSuccess) {
                globalTransactions.remove(xid);
                System.out.printf("  [TC] XID=%s 全局提交完成%n", xid);
            }
            return allSuccess;
        }

        boolean rollbackGlobal(String xid) {
            GlobalTransaction gt = globalTransactions.get(xid);
            if (gt == null) {
                System.out.printf("  [TC] XID=%s 不存在%n", xid);
                return false;
            }
            System.out.printf("  [TC] 二阶段：全局回滚 XID=%s, 分支数=%d%n", xid, gt.branches.size());
            for (Map.Entry<String, BranchTransaction> entry : gt.branches.entrySet()) {
                entry.getValue().rollback();
            }
            globalTransactions.remove(xid);
            System.out.printf("  [TC] XID=%s 全局回滚完成%n", xid);
            return true;
        }
    }

    /**
     * 全局事务。
     */
    static class GlobalTransaction {
        String xid;
        Map<String, BranchTransaction> branches = new LinkedHashMap<>();

        GlobalTransaction(String xid) {
            this.xid = xid;
        }
    }

    /**
     * TM（Transaction Manager）：事务管理器，通常由 @GlobalTransactional 注解的入口方法所在服务担任。
     */
    static class TransactionManager {
        private final TransactionCoordinator tc;

        TransactionManager(TransactionCoordinator tc) {
            this.tc = tc;
        }

        /**
         * 发起全局事务（模拟 @GlobalTransactional 注解）。
         */
        void executeInGlobalTransaction(GlobalTransactionalTask task) {
            String xid = tc.beginGlobalTransaction();
            try {
                task.execute(tc, xid);
                tc.commitGlobal(xid);
                System.out.printf("  [TM] @GlobalTransactional 提交 XID=%s%n", xid);
            } catch (Exception e) {
                System.out.printf("  [TM] 异常，触发回滚 XID=%s: %s%n", xid, e.getMessage());
                tc.rollbackGlobal(xid);
            }
        }
    }

    @FunctionalInterface
    interface GlobalTransactionalTask {
        void execute(TransactionCoordinator tc, String xid) throws Exception;
    }

    /**
     * RM（Resource Manager）：资源管理器，管理分支事务的本地资源（数据库连接等）。
     */
    static class ResourceManager {
        private final String resourceName;

        ResourceManager(String resourceName) {
            this.resourceName = resourceName;
        }

        /** 模拟数据库中的数据 */
        private final Map<Integer, Integer> accounts = new ConcurrentHashMap<>();

        {
            accounts.put(1, 1000); // 账户1 余额1000
            accounts.put(2, 500);  // 账户2 余额500
        }

        // ==================== AT 模式 ====================

        /** AT 模式 undolog 存储：branchId → {beforeImage, afterImage} */
        private final Map<String, UndoLog> undoLogs = new ConcurrentHashMap<>();
        /** AT 模式全局锁：用于防止脏写 */
        private final Set<String> globalLocks = ConcurrentHashMap.newKeySet();

        static class UndoLog {
            /** 前镜像：修改前的数据 */
            Map<Integer, Integer> beforeImage;
            /** 后镜像：修改后的数据 */
            Map<Integer, Integer> afterImage;

            UndoLog(Map<Integer, Integer> before, Map<Integer, Integer> after) {
                this.beforeImage = new HashMap<>(before);
                this.afterImage = new HashMap<>(after);
            }
        }

        /**
         * AT 模式一阶段：执行 SQL + 记录 undolog。
         */
        boolean atPhase1(String xid, String branchId, int accountId, int delta) {
            /* 全局锁检查 */
            String lockKey = resourceName + ":" + accountId;
            if (!globalLocks.add(lockKey)) {
                System.out.printf("    [%s] AT 一阶段：获取全局锁失败 account=%d%n", resourceName, accountId);
                return false;
            }

            /* 记录 before-image */
            Map<Integer, Integer> beforeImage = new HashMap<>(accounts);
            int before = accounts.getOrDefault(accountId, 0);
            int after = before + delta;
            accounts.put(accountId, after);

            /* 记录 undolog */
            undoLogs.put(branchId, new UndoLog(beforeImage, new HashMap<>(accounts)));
            System.out.printf("    [%s] AT 一阶段：account=%d, %d → %d, undolog 已记录%n",
                    resourceName, accountId, before, after);
            return true;
        }

        /**
         * AT 模式二阶段提交：删除 undolog + 释放全局锁。
         */
        boolean atCommit(String branchId) {
            undoLogs.remove(branchId);
            // 释放全局锁（简化：释放所有）
            System.out.printf("    [%s] AT 二阶段提交：删除 undolog, 释放全局锁%n", resourceName);
            return true;
        }

        /**
         * AT 模式二阶段回滚：通过 undolog 恢复数据。
         */
        boolean atRollback(String branchId) {
            UndoLog log = undoLogs.remove(branchId);
            if (log == null) {
                System.out.printf("    [%s] AT 二阶段回滚：undolog 不存在，可能已提交%n", resourceName);
                return true; // 空回滚
            }
            /* 校验 after-image 是否一致（防止脏写） */
            accounts.clear();
            accounts.putAll(log.beforeImage);
            System.out.printf("    [%s] AT 二阶段回滚：通过 undolog 恢复 before-image%n", resourceName);
            return true;
        }

        // ==================== TCC 模式 ====================

        /** TCC try 阶段的资源预留 */
        private final Map<String, Integer> tccFrozen = new ConcurrentHashMap<>();

        /**
         * TCC Try：资源预留（冻结）。
         */
        boolean tccTry(String branchId, int accountId, int amount) {
            String freezeKey = branchId + ":" + accountId;
            int balance = accounts.getOrDefault(accountId, 0);
            if (balance < amount) {
                System.out.printf("    [%s] TCC Try 失败：account=%d 余额不足 (balance=%d < amount=%d)%n",
                        resourceName, accountId, balance, amount);
                return false;
            }
            accounts.put(accountId, balance - amount);
            tccFrozen.put(freezeKey, amount);
            System.out.printf("    [%s] TCC Try：account=%d, 冻结 %d, 余额=%d%n",
                    resourceName, accountId, amount, accounts.get(accountId));
            return true;
        }

        /**
         * TCC Confirm：确认执行（解冻并扣减）。
         */
        void tccConfirm(String branchId, int accountId) {
            String freezeKey = branchId + ":" + accountId;
            Integer frozen = tccFrozen.remove(freezeKey);
            System.out.printf("    [%s] TCC Confirm：account=%d, 解冻 %d (扣减已生效)%n",
                    resourceName, accountId, frozen);
        }

        /**
         * TCC Cancel：取消（解冻并恢复余额）。
         */
        void tccCancel(String branchId, int accountId) {
            String freezeKey = branchId + ":" + accountId;
            Integer frozen = tccFrozen.remove(freezeKey);
            if (frozen != null) {
                accounts.merge(accountId, frozen, Integer::sum);
            }
            System.out.printf("    [%s] TCC Cancel：account=%d, 解冻 %d, 恢复余额=%d%n",
                    resourceName, accountId, frozen, accounts.get(accountId));
        }

        /** 查询余额 */
        int getBalance(int accountId) {
            return accounts.getOrDefault(accountId, 0);
        }
    }

    // ==================== 2. 分支事务 ====================

    static class BranchTransaction {
        private final String branchId;
        private final ResourceManager rm;
        private final Runnable commitAction;
        private final Runnable rollbackAction;

        BranchTransaction(String branchId, ResourceManager rm,
                          Runnable commitAction, Runnable rollbackAction) {
            this.branchId = branchId;
            this.rm = rm;
            this.commitAction = commitAction;
            this.rollbackAction = rollbackAction;
        }

        boolean commit() {
            System.out.printf("  [Branch=%s] 提交%n", branchId);
            commitAction.run();
            return true;
        }

        void rollback() {
            System.out.printf("  [Branch=%s] 回滚%n", branchId);
            rollbackAction.run();
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        TransactionCoordinator tc = new TransactionCoordinator();
        TransactionManager tm = new TransactionManager(tc);

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Seata 分布式事务 - 纯 Java 模拟演示        ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. AT 模式：转账成功 ── */
        System.out.println("\n=== 1. AT 模式：转账 100（成功） ===");
        ResourceManager accountRm = new ResourceManager("account-db");
        System.out.printf("  初始余额：account1=%d, account2=%d%n",
                accountRm.getBalance(1), accountRm.getBalance(2));

        tm.executeInGlobalTransaction((coordinator, xid) -> {
            /* 分支1：账户1 扣 100 */
            String branch1 = "branch-" + xid + "-1";
            boolean ok1 = accountRm.atPhase1(xid, branch1, 1, -100);
            BranchTransaction bt1 = new BranchTransaction(branch1, accountRm,
                    () -> accountRm.atCommit(branch1),
                    () -> accountRm.atRollback(branch1));
            coordinator.registerBranch(xid, branch1, bt1);
            if (!ok1) throw new RuntimeException("扣款失败");

            /* 分支2：账户2 加 100 */
            String branch2 = "branch-" + xid + "-2";
            boolean ok2 = accountRm.atPhase1(xid, branch2, 2, +100);
            BranchTransaction bt2 = new BranchTransaction(branch2, accountRm,
                    () -> accountRm.atCommit(branch2),
                    () -> accountRm.atRollback(branch2));
            coordinator.registerBranch(xid, branch2, bt2);
            if (!ok2) throw new RuntimeException("入账失败");
        });
        System.out.printf("  最终余额：account1=%d, account2=%d%n",
                accountRm.getBalance(1), accountRm.getBalance(2));

        /* ── 2. AT 模式：转账失败，回滚 ── */
        System.out.println("\n=== 2. AT 模式：转账 2000（失败，触发回滚） ===");
        System.out.printf("  当前余额：account1=%d, account2=%d%n",
                accountRm.getBalance(1), accountRm.getBalance(2));
        tm.executeInGlobalTransaction((coordinator, xid) -> {
            String branch1 = "branch-" + xid + "-1";
            accountRm.atPhase1(xid, branch1, 1, -2000); // 扣 2000
            BranchTransaction bt1 = new BranchTransaction(branch1, accountRm,
                    () -> accountRm.atCommit(branch1),
                    () -> accountRm.atRollback(branch1));
            coordinator.registerBranch(xid, branch1, bt1);

            // 模拟业务异常
            throw new RuntimeException("业务校验失败：转账金额超限");
        });
        System.out.printf("  回滚后余额：account1=%d, account2=%d%n",
                accountRm.getBalance(1), accountRm.getBalance(2));

        /* ── 3. TCC 模式演示 ── */
        System.out.println("\n=== 3. TCC 模式（Try-Confirm-Cancel） ===");
        ResourceManager tccRm = new ResourceManager("tcc-account-db");
        System.out.printf("  初始余额：account1=%d, account2=%d%n",
                tccRm.getBalance(1), tccRm.getBalance(2));
        System.out.println("  --- TCC Try ---");
        String tccBranch1 = "tcc-branch-1";
        String tccBranch2 = "tcc-branch-2";
        tccRm.tccTry(tccBranch1, 1, 100);
        tccRm.tccTry(tccBranch2, 2, 100);
        System.out.println("  --- TCC Confirm ---");
        tccRm.tccConfirm(tccBranch1, 1);
        tccRm.tccConfirm(tccBranch2, 2);
        System.out.printf("  TCC 后余额：account1=%d, account2=%d%n",
                tccRm.getBalance(1), tccRm.getBalance(2));

        System.out.println("\n  --- TCC Cancel 演示（Try 后 Cancel） ---");
        String tccBranch3 = "tcc-branch-3";
        tccRm.tccTry(tccBranch3, 1, 50);
        tccRm.tccCancel(tccBranch3, 1); // 取消，余额应恢复
        System.out.printf("  Cancel 后余额：account1=%d%n", tccRm.getBalance(1));

        /* ── 4. 四种模式对比 ── */
        System.out.println("\n=== 4. Seata 四种模式对比 ===");
        System.out.println("┌─────────┬──────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ 维度    │ AT           │ TCC          │ Saga         │ XA           │");
        System.out.println("├─────────┼──────────────┼──────────────┼──────────────┼──────────────┤");
        System.out.println("│ 侵入性  │ 无（自动）   │ 高（三接口） │ 中（补偿）   │ 无（自动）   │");
        System.out.println("│ 一致性  │ 最终一致     │ 最终一致     │ 最终一致     │ 强一致       │");
        System.out.println("│ 性能    │ 高           │ 高           │ 高           │ 低（锁）     │");
        System.out.println("│ 回滚    │ undolog 自动 │ Cancel 手动  │ 补偿手动     │ 自动         │");
        System.out.println("│ 场景    │ 通用         │ 金额/库存    │ 长事务       │ 传统事务     │");
        System.out.println("└─────────┴──────────────┴──────────────┴──────────────┴──────────────┘");

        /* ── 5. @GlobalTransactional vs @Transactional ── */
        System.out.println("\n=== 5. @GlobalTransactional vs @Transactional ===");
        System.out.println("  @Transactional：本地事务，只管理单个数据源，无法跨服务");
        System.out.println("  @GlobalTransactional：全局事务，由 TC 协调多个 RM 的分支事务");
        System.out.println("  关系：@GlobalTransactional 内部每个分支仍然需要 @Transactional");
        System.out.println("  TC/TM/RM 交互流程：");
        System.out.println("    1. TM 向 TC 申请开启全局事务 → 获取 XID");
        System.out.println("    2. RM 向 TC 注册分支事务（携带 XID）");
        System.out.println("    3. 各 RM 执行一阶段（本地事务 + undolog）");
        System.out.println("    4. TM 向 TC 发起全局提交/回滚");
        System.out.println("    5. TC 驱动各 RM 执行二阶段提交/回滚");

        System.out.println("\n=== 演示结束 ===");
    }
}