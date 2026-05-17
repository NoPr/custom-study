package com.nopr.mq.rocketmq.simulate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】RocketMQ 事务消息 —— 半消息·本地事务·回查机制
 * 【描述】模拟 RocketMQ 两阶段提交：阶段1 发送半消息（PREPARE）、阶段2 执行本地事务、
 *         阶段3 Commit/Rollback。Broker 端实现 CheckListener 回查机制，
 *         定时扫描超时半消息并回调 checkLocalTransaction。
 * 【关键概念】半消息、本地事务、Commit/Rollback、回查机制、CheckListener、
 *             事务状态（UNKNOWN/COMMIT/ROLLBACK）
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaIdempotentDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class TransactionDemo {

    /* ======================== 枚举与常量 ======================== */

    /** 事务状态 */
    enum TransactionStatus {
        /** 准备阶段 (半消息) */
        PREPARE,
        /** 已提交 (消费者可见) */
        COMMIT,
        /** 已回滚 (消息丢弃) */
        ROLLBACK,
        /** 未知 (需要回查) */
        UNKNOWN
    }

    /** 本地事务状态 */
    enum LocalTxState {
        COMMIT_MESSAGE,
        ROLLBACK_MESSAGE,
        UNKNOW
    }

    /* ======================== 数据模型 ======================== */

    /** 事务消息 */
    record TxMessage(String txId, String topic, String tags, String content,
                     TransactionStatus status, long createTime) {
        TxMessage withStatus(TransactionStatus newStatus) {
            return new TxMessage(txId, topic, tags, content, newStatus, createTime);
        }
    }

    /** 本地事务日志 */
    record LocalTxLog(String txId, LocalTxState state) {
    }

    /* ======================== CheckListener 回查接口 ======================== */

    /** 事务状态回查监听器（Broker 侧调用） */
    @FunctionalInterface
    interface CheckListener {
        LocalTxState checkLocalTransaction(String txId);
    }

    /* ======================== Broker 事务支持 ======================== */

    static class TxBroker {
        /** 半消息存储 (消费者不可见) */
        final ConcurrentHashMap<String, TxMessage> halfMessages = new ConcurrentHashMap<>();
        /** 已提交消息 (消费者可见) */
        final List<TxMessage> committedMessages = Collections.synchronizedList(new ArrayList<>());
        /** 已回滚消息 */
        final List<TxMessage> rollbackMessages = Collections.synchronizedList(new ArrayList<>());
        /** 回查监听器注册 */
        CheckListener checkListener;
        /** 回查超时时间 ms */
        final long checkTimeoutMs;
        /** 定时回查调度器 */
        final ScheduledExecutorService checkScheduler = Executors.newSingleThreadScheduledExecutor();
        /** 统计 */
        final AtomicInteger commitCount = new AtomicInteger(0);
        final AtomicInteger rollbackCount = new AtomicInteger(0);

        TxBroker(long checkTimeoutMs) {
            this.checkTimeoutMs = checkTimeoutMs;
            checkScheduler.scheduleAtFixedRate(this::checkTimeoutTransactions,
                    checkTimeoutMs, checkTimeoutMs, TimeUnit.MILLISECONDS);
        }

        /** 注册回查监听器 */
        void registerCheckListener(CheckListener listener) {
            this.checkListener = listener;
        }

        /** 阶段 1: 接收半消息 */
        boolean putHalfMessage(TxMessage msg) {
            halfMessages.put(msg.txId, msg);
            System.out.printf("  [Broker] 收到半消息: txId=%s, topic=%s (消费者不可见)%n",
                    msg.txId, msg.topic);
            return true;
        }

        /** 阶段 2: 处理 Commit/Rollback 请求 */
        boolean commit(String txId) {
            TxMessage msg = halfMessages.remove(txId);
            if (msg == null) {
                System.out.printf("  [Broker] Commit 失败: txId=%s 半消息不存在%n", txId);
                return false;
            }
            TxMessage committed = msg.withStatus(TransactionStatus.COMMIT);
            committedMessages.add(committed);
            commitCount.incrementAndGet();
            System.out.printf("  [Broker] 事务提交: txId=%s → 消费者可见%n", txId);
            return true;
        }

        boolean rollback(String txId) {
            TxMessage msg = halfMessages.remove(txId);
            if (msg == null) {
                System.out.printf("  [Broker] Rollback 失败: txId=%s 半消息不存在%n", txId);
                return false;
            }
            TxMessage rolled = msg.withStatus(TransactionStatus.ROLLBACK);
            rollbackMessages.add(rolled);
            rollbackCount.incrementAndGet();
            System.out.printf("  [Broker] 事务回滚: txId=%s → 消息丢弃%n", txId);
            return true;
        }

        /** 定时回查超时的半消息 */
        private void checkTimeoutTransactions() {
            long now = System.currentTimeMillis();
            List<String> timeoutTxIds = new ArrayList<>();

            for (TxMessage msg : halfMessages.values()) {
                if (now - msg.createTime > checkTimeoutMs) {
                    timeoutTxIds.add(msg.txId);
                }
            }

            for (String txId : timeoutTxIds) {
                if (checkListener != null) {
                    System.out.printf("  [Broker] 回查事务: txId=%s (超时 %dms)%n",
                            txId, checkTimeoutMs);
                    LocalTxState state = checkListener.checkLocalTransaction(txId);
                    switch (state) {
                        case COMMIT_MESSAGE -> commit(txId);
                        case ROLLBACK_MESSAGE -> rollback(txId);
                        case UNKNOW -> System.out.printf("  [Broker] 回查结果未知: txId=%s, 等待下次回查%n", txId);
                    }
                }
            }
        }

        /** 关闭 Broker */
        void shutdown() {
            checkScheduler.shutdown();
        }

        /** 统计 */
        void printStats() {
            System.out.printf("""
                    Broker 统计: 半消息=%d, 已提交=%d, 已回滚=%d
                  %n""", halfMessages.size(), commitCount.get(), rollbackCount.get());
        }
    }

    /* ======================== Producer 事务支持 ======================== */

    static class TxProducer {
        final String group;
        /** 本地事务日志 (模拟 DB 事务表) */
        final ConcurrentHashMap<String, LocalTxLog> localTxLog = new ConcurrentHashMap<>();

        TxProducer(String group) {
            this.group = group;
        }

        /**
         * 发送事务消息 (两阶段提交)
         *
         * @return 事务 ID, 如果半消息发送失败则返回 null
         */
        String sendMessageInTransaction(TxBroker broker, String topic, String tags, String content) {
            String txId = "TX-" + UUID.randomUUID().toString().substring(0, 8);
            TxMessage halfMsg = new TxMessage(txId, topic, tags, content,
                    TransactionStatus.PREPARE, System.currentTimeMillis());

            // 阶段 1: 发送半消息
            System.out.printf("  [Producer] 阶段1-发送半消息: txId=%s%n", txId);
            boolean halfSent = broker.putHalfMessage(halfMsg);
            if (!halfSent) {
                System.out.printf("  [Producer] 半消息发送失败%n");
                return null;
            }

            // 阶段 2: 执行本地事务
            System.out.printf("  [Producer] 阶段2-执行本地事务: txId=%s (模拟 DB 写)%n", txId);
            boolean localTxSuccess = executeLocalTransaction(txId);

            if (localTxSuccess) {
                broker.commit(txId);
                localTxLog.put(txId, new LocalTxLog(txId, LocalTxState.COMMIT_MESSAGE));
                System.out.printf("  [Producer] 本地事务成功 → Commit%n");
            } else {
                broker.rollback(txId);
                localTxLog.put(txId, new LocalTxLog(txId, LocalTxState.ROLLBACK_MESSAGE));
                System.out.printf("  [Producer] 本地事务失败 → Rollback%n");
            }

            return txId;
        }

        /** 模拟本地事务执行 */
        private boolean executeLocalTransaction(String txId) {
            boolean success = Math.random() > 0.3;
            System.out.printf("    [本地事务] txId=%s, 写入订单表... -> %s%n",
                    txId, success ? "成功" : "失败");
            return success;
        }

        /** 回查本地事务状态 (供 Broker CheckListener 调用) */
        LocalTxState checkLocalTx(String txId) {
            LocalTxLog log = localTxLog.get(txId);
            if (log != null) {
                System.out.printf("    [Producer] 回查本地事务: txId=%s, state=%s%n", txId, log.state);
                return log.state;
            }
            System.out.printf("    [Producer] 回查本地事务: txId=%s, 无记录 → UNKNOW%n", txId);
            return LocalTxState.UNKNOW;
        }
    }

    /* ======================== Consumer ======================== */

    static class TxConsumer {
        final String group;

        TxConsumer(String group) {
            this.group = group;
        }

        /** 拉取已提交消息 */
        void consume(TxBroker broker) {
            List<TxMessage> committed = new ArrayList<>(broker.committedMessages);
            System.out.printf("  [Consumer] 消费已提交消息 (共 %d 条):%n", committed.size());
            for (TxMessage msg : committed) {
                System.out.printf("    -> txId=%s, topic=%s, content=%s%n",
                        msg.txId, msg.topic, msg.content);
            }
        }
    }

    /* ======================== 超时回查场景演示 ======================== */

    /** 场景：Producer 发送半消息后宕机, Broker 超时触发回查 */
    static void timeoutCheckScenario() throws InterruptedException {
        System.out.println("\n--- 超时回查场景: Producer 宕机 → Broker.CheckListener ---");

        TxBroker broker = new TxBroker(200); // 200ms 超时
        TxProducer producer = new TxProducer("tx-producer");

        // 注册回查监听器
        broker.registerCheckListener(producer::checkLocalTx);

        // 只发半消息, 不 commit (模拟 Producer 宕机)
        String txId = "TX-CRASH-" + UUID.randomUUID().toString().substring(0, 8);
        TxMessage halfMsg = new TxMessage(txId, "OrderTopic", "TagA",
                "{\"orderId\":99}", TransactionStatus.PREPARE, System.currentTimeMillis());
        broker.putHalfMessage(halfMsg);

        // 写入本地事务日志 (但 Producer "宕机" 未 commit)
        producer.localTxLog.put(txId, new LocalTxLog(txId, LocalTxState.COMMIT_MESSAGE));
        System.out.println("  [Producer] 发送半消息后宕机... Broker 等待超时");

        // 等待超时回查
        Thread.sleep(500);
        broker.printStats();
        broker.shutdown();
    }

    /* ======================== Kafka 事务对比 ======================== */

    static void kafkaTransactionComparison() {
        System.out.println("\n--- Kafka 事务 vs RocketMQ 事务 ---");
        System.out.println("""
                Kafka 事务 (KIP-98):
                  1. initTransactions() → 获取 PID + epoch
                  2. beginTransaction() → 事务开始
                  3. send(record) → 消息写入但标记为未提交 (类似半消息)
                  4. sendOffsetsToTransaction() → 消费位移也纳入事务
                  5. commitTransaction() / abortTransaction()
                
                RocketMQ 事务:
                  1. sendMessageInTransaction() → 半消息
                  2. executeLocalTransaction() → 本地事务
                  3. commit / rollback
                  4. checkLocalTransaction() → 回查 (Kafka 无此机制)
                
                关键差异:
                  - Kafka: PID+SeqNum 去重, TransactionCoordinator 协调
                  - RocketMQ: 半消息 + CheckListener 回查, Broker 内置
                  - Kafka 事务 = 幂等 (单分区) + 原子多分区写入
                  - RocketMQ 事务 = 半消息机制, 不依赖幂等
                
                适用场景:
                  - RocketMQ 事务: 订单+库存+消息 三者一致性
                  - Kafka 事务: Kafka Streams EOS, 跨 Topic 原子写入
                """);
    }

    /* ======================== main ======================== */

    public static void main(String[] args) throws InterruptedException {
        System.out.println("========== RocketMQ 事务消息模拟 ==========\n");

        // 1. 正常事务流程
        System.out.println("--- 1. 正常事务消息流程 (两阶段提交) ---");
        TxBroker broker = new TxBroker(5000);
        TxProducer producer = new TxProducer("order-tx-producer");
        broker.registerCheckListener(producer::checkLocalTx);

        System.out.println("\n  >>> 成功事务 <<<");
        producer.sendMessageInTransaction(broker, "OrderTopic", "TagA",
                "{\"orderId\":1,\"amount\":100}");

        System.out.println("\n  >>> 失败事务 <<<");
        String failTxId = "TX-FAIL-" + UUID.randomUUID().toString().substring(0, 8);
        TxMessage failMsg = new TxMessage(failTxId, "OrderTopic", "TagB",
                "{\"orderId\":2,\"amount\":-1}", TransactionStatus.PREPARE, System.currentTimeMillis());
        broker.putHalfMessage(failMsg);
        broker.rollback(failTxId);

        // 2. Consumer 消费 (仅能看到 COMMIT 的消息)
        System.out.println("\n--- 2. Consumer 拉取 (仅已提交消息) ---");
        TxConsumer consumer = new TxConsumer("order-consumer");
        consumer.consume(broker);

        // 3. 超时回查场景
        broker.shutdown();
        timeoutCheckScenario();

        // 4. Kafka 对比
        kafkaTransactionComparison();

        // 总结
        System.out.println("\n--- 3. 事务消息总结 ---");
        System.out.println("""
                RocketMQ 事务消息核心:
                  1. 半消息: 发送方先发 PREPARE, Broker 标记不可见
                  2. 本地事务: 发送方执行本地事务 (写 DB)
                  3. EndTransaction: commit(消息可见) / rollback(消息删除)
                  4. 回查机制: Broker 定时扫半消息, 超时回调 CheckListener
                
                优点:
                  - 不依赖 XA/2PC 协议, 轻量级
                  - Broker 主动回查, 不会因 Producer 宕机永久阻塞
                  - 消费者无需感知事务, 只消费 COMMIT 消息
                
                注意:
                  - 本地事务 + 消息 并非强一致 (最终一致)
                  - CheckListener 需幂等: 多次回查返回同样结果
                  - 半消息也会占用 Broker 存储, 需配置过期清理
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}