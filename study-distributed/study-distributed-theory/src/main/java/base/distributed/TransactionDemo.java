package base.distributed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式事务方案演示 —— 4 种主流方案对比。
 *
 * <ul>
 *   <li><b>2PC (XA)</b>：事务管理器(TM)协调多个资源管理器(RM)，Phase-1 prepare → Phase-2 commit/rollback</li>
 *   <li><b>TCC (Try-Confirm-Cancel)</b>：业务层补偿，Try 预留资源 → Confirm 确认 → Cancel 释放</li>
 *   <li><b>Saga</b>：长事务拆分多个本地事务，正向执行 + 补偿回滚 / 或事件驱动编排</li>
 *   <li><b>MQ 可靠消息最终一致性</b>：本地事务 + 消息表 + 定时重投 + 消费者幂等</li>
 *   <li><b>Seata AT 模式</b>：一阶段自动生成 undo_log，二阶段异步回滚/提交</li>
 * </ul>
 */
public class TransactionDemo {

    /* =========================================================================
     * 1. 2PC (Two-Phase Commit) —— 两阶段提交
     * ========================================================================= */

    /** 事务管理器 */
    static class TransactionManager {
        private final List<ResourceManager> participants = new ArrayList<>();

        void register(ResourceManager rm) { participants.add(rm); }

        boolean execute(String xid) {
            // Phase-1: PREPARE —— 所有参与方执行 SQL 但不提交
            boolean allPrepared = participants.stream().allMatch(rm -> rm.prepare(xid));
            // Phase-2: COMMIT / ROLLBACK
            participants.forEach(rm -> {
                if (allPrepared) rm.commit(xid); else rm.rollback(xid);
            });
            return allPrepared;
        }
    }

    /** 资源管理器（数据库） */
    static class ResourceManager {
        private final String name;
        private final Map<String, String> storage = new ConcurrentHashMap<>();
        private final Map<String, String> undoBuffer = new ConcurrentHashMap<>();

        ResourceManager(String name) { this.name = name; }

        boolean prepare(String xid) {
            // 模拟：写 redo + undo，锁定资源
            undoBuffer.put(xid, storage.getOrDefault("balance", "0"));
            System.out.printf("[2PC-PREPARE] %s → %s 预提交成功%n", name, xid);
            return true; // 返回是否可以提交
        }

        void commit(String xid) {
            storage.put("balance", "committed");
            undoBuffer.remove(xid);
            System.out.printf("[2PC-COMMIT]  %s → %s 提交成功%n", name, xid);
        }

        void rollback(String xid) {
            String before = undoBuffer.remove(xid);
            storage.put("balance", before);
            System.out.printf("[2PC-ROLLBACK]%s → %s 回滚至 %s%n", name, xid, before);
        }
    }

    /* =========================================================================
     * 2. TCC (Try-Confirm-Cancel)
     * ========================================================================= */

    interface TccAction {
        boolean tryPhase(String xid);   // 预留资源
        void confirm(String xid);       // 确认执行
        void cancel(String xid);        // 释放资源
    }

    static class TccOrderService implements TccAction {
        private final Set<String> frozenInventory = new HashSet<>();

        public boolean tryPhase(String xid) {
            frozenInventory.add(xid);
            System.out.printf("[TCC-TRY]     冻结库存 %s%n", xid);
            return true;
        }

        public void confirm(String xid) {
            frozenInventory.remove(xid);
            System.out.printf("[TCC-CONFIRM] 扣减库存 %s%n", xid);
        }

        public void cancel(String xid) {
            frozenInventory.remove(xid);
            System.out.printf("[TCC-CANCEL]  释放库存 %s%n", xid);
        }
    }

    static class TccCoordinator {
        private final List<TccAction> actions = new ArrayList<>();

        void register(TccAction action) { actions.add(action); }

        void execute(String xid) {
            // Phase-1: Try all
            boolean allTryOk = true;
            for (TccAction action : actions) {
                if (!action.tryPhase(xid)) {
                    allTryOk = false;
                    break;
                }
            }
            // Phase-2: Confirm or Cancel
            for (TccAction action : actions) {
                if (allTryOk) action.confirm(xid);
                else action.cancel(xid);
            }
        }
    }

    /* =========================================================================
     * 3. Saga —— 链式正交易 + 补偿
     * ========================================================================= */

    static class SagaOrchestrator {
        /** 正向操作（有序） */
        private final List<Runnable> forwardSteps = new ArrayList<>();
        /** 补偿操作（逆序），index 对应 forwardSteps */
        private final List<Runnable> compensateSteps = new ArrayList<>();

        void addStep(Runnable forward, Runnable compensate) {
            forwardSteps.add(forward);
            compensateSteps.add(compensate);
        }

        void execute() {
            int currentStep = 0;
            try {
                for (; currentStep < forwardSteps.size(); currentStep++) {
                    System.out.printf("[Saga-正向] 步骤%d 执行%n", currentStep + 1);
                    forwardSteps.get(currentStep).run();
                }
                System.out.println("[Saga] 全部成功");
            } catch (Exception e) {
                System.out.printf("[Saga-异常] 步骤%d 失败，启动补偿%n", currentStep + 1);
                // 逆序补偿已完成的步骤
                for (int i = currentStep - 1; i >= 0; i--) {
                    System.out.printf("[Saga-补偿] 步骤%d 回滚%n", i + 1);
                    compensateSteps.get(i).run();
                }
            }
        }
    }

    /* =========================================================================
     * 4. MQ 可靠消息最终一致性（本地消息表）
     * ========================================================================= */

    static class LocalMessageTable {
        /** 消息状态 */
        enum Status { PENDING, SENT, CONSUMED }

        private final Map<String, Status> table = new LinkedHashMap<>();

        /** 本地事务写入：业务操作 + 消息表（同一数据库事务） */
        void insertMessage(String msgId, String payload) {
            table.put(msgId, Status.PENDING);
            System.out.printf("[MQ-本地事务] 写入消息 %s, 状态=PENDING%n", msgId);
        }

        /** 定时任务扫描 PENDING 消息投递到 MQ */
        void scheduledScanAndSend() {
            table.forEach((msgId, status) -> {
                if (status == Status.PENDING) {
                    System.out.printf("[MQ-定时投递] %s → Broker%n", msgId);
                    table.put(msgId, Status.SENT);
                }
            });
        }

        /** 消费者处理，确保幂等 */
        boolean consume(String msgId) {
            if (table.getOrDefault(msgId, null) == Status.CONSUMED) {
                System.out.printf("[MQ-幂等] %s 已消费，跳过%n", msgId);
                return true;
            }
            table.put(msgId, Status.CONSUMED);
            System.out.printf("[MQ-消费] %s 消费成功%n", msgId);
            return true;
        }
    }

    /* =========================================================================
     * 5. Seata AT 模式核心流程
     * ========================================================================= */

    static class SeataATSimulator {
        private final Map<String, String> undoLog = new LinkedHashMap<>();

        /**
         * 一阶段：执行业务 SQL + 自动生成 undo_log 快照（before image）。
         */
        void phase1ExecuteAndSnapshot(String xid, String resource, String beforeData) {
            undoLog.put(xid, beforeData);
            System.out.printf("[Seata-AT-P1] %s 执行业务SQL, 保存undo_log: %s%n", resource, beforeData);
        }

        /**
         * 二阶段：异步删除 undo_log（提交） 或 回滚 undo_log（回滚）。
         */
        void phase2CommitOrRollback(String xid, boolean commit) {
            if (commit) {
                undoLog.remove(xid);
                System.out.printf("[Seata-AT-P2] %s 异步提交，清理undo_log%n", xid);
            } else {
                String before = undoLog.get(xid);
                System.out.printf("[Seata-AT-P2] %s 回滚，恢复至 %s%n", xid, before);
            }
        }
    }

    /* =========================================================================
     * main
     * ========================================================================= */

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("1. 2PC 两阶段提交");
        System.out.println("=".repeat(60));
        {
            TransactionManager tm = new TransactionManager();
            ResourceManager db1 = new ResourceManager("订单库");
            ResourceManager db2 = new ResourceManager("库存库");
            tm.register(db1);
            tm.register(db2);
            tm.execute("XID-001");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("2. TCC Try-Confirm-Cancel");
        System.out.println("=".repeat(60));
        {
            TccCoordinator coordinator = new TccCoordinator();
            coordinator.register(new TccOrderService());
            coordinator.execute("XID-TCC-001");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("3. Saga 编排模式");
        System.out.println("=".repeat(60));
        {
            SagaOrchestrator saga = new SagaOrchestrator();

            saga.addStep(
                () -> System.out.println("  创建订单"),
                () -> System.out.println("  取消订单")
            );
            saga.addStep(
                () -> System.out.println("  扣减库存"),
                () -> System.out.println("  归还库存")
            );
            saga.addStep(
                () -> {
                    System.out.println("  扣款");
                    if (System.currentTimeMillis() % 2 == 0) {
                        throw new RuntimeException("扣款失败");
                    }
                },
                () -> System.out.println("  退款")
            );

            saga.execute();
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("4. MQ 可靠消息最终一致性");
        System.out.println("=".repeat(60));
        {
            LocalMessageTable msgTable = new LocalMessageTable();
            msgTable.insertMessage("MSG-001", "{orderId:1}");
            msgTable.scheduledScanAndSend();
            msgTable.consume("MSG-001");
            msgTable.consume("MSG-001"); // 幂等
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("5. Seata AT 模式");
        System.out.println("=".repeat(60));
        {
            SeataATSimulator seata = new SeataATSimulator();
            seata.phase1ExecuteAndSnapshot("XID-SEATA-001", "订单库", "balance=100");
            seata.phase1ExecuteAndSnapshot("XID-SEATA-001", "库存库", "stock=10");
            seata.phase2CommitOrRollback("XID-SEATA-001", true);
        }
    }
}