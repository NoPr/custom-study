package base.mq;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka 幂等 + 事务：PID + Sequence Number + Kafka Transaction
 *
 * <p>幂等 Producer：enable.idempotence=true → PID + SeqNum 去重。
 * Kafka Transaction：initTransactions → begin → send → commit。
 * exactly-once = 幂等 (Broker端) + 事务 (跨分区)。
 *
 * <p>幂等 Producer 原理：
 * <pre>
 * Producer 初始化:
 *   → 向 Broker 申请 PID (Producer ID)
 *   → 每个 TopicPartition 维护 Sequence Number
 *
 * 发送消息流程:
 *   Producer → Broker: {PID, SeqNum, message}
 *   Broker 维护: (PID, TopicPartition) → 最后提交的 SeqNum
 *   去重规则: 新 SeqNum > 已提交 SeqNum + 1 → 乱序错误
 *            新 SeqNum == 已提交 SeqNum + 1 → 新消息
 *            新 SeqNum <= 已提交 SeqNum → 重复消息, 丢弃
 * </pre>
 *
 * <p>Kafka 事务流程:
 * <pre>
 * initTransactions() → 获取 TransactionalId → PID + TransactionCoordinator
 * beginTransaction() → 事务开始
 * send(record) → 消息写入, 标记为未提交
 * sendOffsetsToTransaction() → 消费位移也纳入事务(consume-transform-produce)
 * commitTransaction() → 提交 (消息对 Consumer 可见)
 * </pre>
 *
 * @author study-tuling
 */
public class KafkaIdempotentDemo {

    /* ======================== 数据模型 ======================== */

    /** Producer ID */
    record ProducerId(long pid, short epoch) {
    }

    /** 消息记录 */
    record Record(String topic, int partition, String key, String value,
                  ProducerId pid, long seqNum, boolean committed) {
    }

    /* ======================== Broker 侧幂等去重 ======================== */

    static class IdempotentBroker {
        /** (PID, TopicPartition) → 最后已提交 SeqNum */
        final Map<String, Long> lastCommittedSeqNum = new ConcurrentHashMap<>();
        /** 消息存储 */
        final List<Record> store = Collections.synchronizedList(new ArrayList<>());
        /** 事务日志 */
        final Map<String/* transactionalId */, List<Record>> transactionalBuffers = new ConcurrentHashMap<>();
        /** PID 分配器 */
        final AtomicLong pidGenerator = new AtomicLong(1000);
        /** 统计 */
        final AtomicInteger duplicateCount = new AtomicInteger(0);
        final AtomicInteger acceptedCount = new AtomicInteger(0);
        final AtomicInteger outOfOrderCount = new AtomicInteger(0);

        /** 分配 PID */
        ProducerId allocatePID() {
            return new ProducerId(pidGenerator.incrementAndGet(), (short) 0);
        }

        /** 接收幂等消息, Broker 侧去重 */
        boolean acceptIdempotent(Record record) {
            String key = record.pid.pid + "-" + record.topic + "-" + record.partition;
            Long lastSeq = lastCommittedSeqNum.get(key);

            // 幂等去重检查
            if (lastSeq != null && record.seqNum <= lastSeq) {
                System.out.printf("  [Broker] 幂等去重: PID=%d, SeqNum=%d (已提交=%d)%n",
                        record.pid.pid, record.seqNum, lastSeq);
                duplicateCount.incrementAndGet();
                return false; // 丢弃重复消息
            }

            // 乱序检查
            if (lastSeq != null && record.seqNum > lastSeq + 1) {
                System.out.printf("  [Broker] 乱序检测: PID=%d, 期望 SeqNum=%d, 实际=%d%n",
                        record.pid.pid, lastSeq + 1, record.seqNum);
                outOfOrderCount.incrementAndGet();
                // 实际 Kafka 会抛出 OutOfOrderSequenceException
            }

            lastCommittedSeqNum.put(key, record.seqNum);
            store.add(record);
            acceptedCount.incrementAndGet();
            System.out.printf("  [Broker] 接收: PID=%d, SeqNum=%d, topic=%s-%d, value=%s%n",
                    record.pid.pid, record.seqNum, record.topic, record.partition, record.value);
            return true;
        }

        /** 事务写入 (未提交, 消费者不可见) */
        void transactionalWrite(String transactionalId, Record record) {
            transactionalBuffers
                    .computeIfAbsent(transactionalId, k -> new ArrayList<>())
                    .add(record);
            System.out.printf("  [Broker] 事务写入(未提交): txId=%s, %s%n", transactionalId, record.value);
        }

        /** 事务提交 */
        void commitTransaction(String transactionalId) {
            List<Record> records = transactionalBuffers.remove(transactionalId);
            if (records != null) {
                for (Record r : records) {
                    Record committed = new Record(r.topic, r.partition, r.key, r.value,
                            r.pid, r.seqNum, true);
                    store.add(committed);
                }
                System.out.printf("  [Broker] 事务提交: txId=%s, %d 条消息%n", transactionalId, records.size());
            }
        }

        /** 事务回滚 */
        void abortTransaction(String transactionalId) {
            List<Record> records = transactionalBuffers.remove(transactionalId);
            if (records != null) {
                System.out.printf("  [Broker] 事务回滚: txId=%s, %d 条消息丢弃%n",
                        transactionalId, records.size());
            }
        }

        void printStats() {
            System.out.printf("""
                    Broker 统计: 接收=%d, 去重=%d, 乱序=%d, 事务缓冲=%d
                  %n""", acceptedCount.get(), duplicateCount.get(),
                    outOfOrderCount.get(), transactionalBuffers.size());
        }
    }

    /* ======================== 幂等 Producer ======================== */

    static class IdempotentProducer {
        final ProducerId pid;
        /** (TopicPartition) → 下一个 SeqNum */
        final Map<String, Long> seqNumMap = new ConcurrentHashMap<>();
        boolean idempotenceEnabled;

        IdempotentProducer(IdempotentBroker broker, boolean idempotenceEnabled) {
            this.pid = broker.allocatePID();
            this.idempotenceEnabled = idempotenceEnabled;
            System.out.printf("  [Producer] 初始化: PID=%d, idempotence=%s%n",
                    pid.pid, idempotenceEnabled);
        }

        /** 发送消息 */
        void send(IdempotentBroker broker, String topic, int partition, String key, String value) {
            String tpKey = topic + "-" + partition;
            long seqNum = seqNumMap.compute(tpKey, (k, v) -> v == null ? 1 : v + 1);

            Record record = new Record(topic, partition, key, value, pid, seqNum, true);
            System.out.printf("  [Producer] 发送: PID=%d, SeqNum=%d, %s=%s%n",
                    pid.pid, seqNum, key, value);
            broker.acceptIdempotent(record);
        }

        /** 模拟重试导致重复发送 */
        void retrySendDuplicate(IdempotentBroker broker, String topic, int partition,
                                String key, String value) {
            // 模拟: 发送成功但 ACK 丢失 → Producer 重试 → 相同 SeqNum
            String tpKey = topic + "-" + partition;
            long currentSeq = seqNumMap.getOrDefault(tpKey, 0L);

            // 使用相同 SeqNum 再次发送 (模拟网络重试)
            Record duplicate = new Record(topic, partition, key, value,
                    new ProducerId(pid.pid, pid.epoch), currentSeq, true);
            System.out.printf("  [Producer] 重试(重复): PID=%d, SeqNum=%d (相同!)，%s=%s%n",
                    pid.pid, currentSeq, key, value);
            boolean accepted = broker.acceptIdempotent(duplicate);
            if (!accepted) {
                System.out.println("  [Producer] 幂等生效: Broker 丢弃重复消息");
            }
        }
    }

    /* ======================== 事务 Producer ======================== */

    static class TransactionalProducer {
        final String transactionalId;
        final IdempotentBroker broker;
        final IdempotentProducer idempotentProducer;
        final List<Record> pendingRecords = new ArrayList<>();

        TransactionalProducer(String transactionalId, IdempotentBroker broker) {
            this.transactionalId = transactionalId;
            this.broker = broker;
            this.idempotentProducer = new IdempotentProducer(broker, true);
            System.out.printf("  [TxProducer] initTransactions: txId=%s, PID=%d%n",
                    transactionalId, idempotentProducer.pid.pid);
        }

        /** 开始事务 */
        void beginTransaction() {
            pendingRecords.clear();
            System.out.printf("  [TxProducer] beginTransaction: txId=%s%n", transactionalId);
        }

        /** 发送消息 (事务中) */
        void send(String topic, int partition, String key, String value) {
            long seqNum = idempotentProducer.seqNumMap.compute(
                    topic + "-" + partition, (k, v) -> v == null ? 1 : v + 1);
            Record record = new Record(topic, partition, key, value,
                    idempotentProducer.pid, seqNum, false); // committed=false
            pendingRecords.add(record);
            broker.transactionalWrite(transactionalId, record);
            System.out.printf("  [TxProducer] send(事务中): PID=%d, SeqNum=%d, %s=%s%n",
                    idempotentProducer.pid.pid, seqNum, key, value);
        }

        /** 提交事务 */
        void commitTransaction() {
            broker.commitTransaction(transactionalId);
            System.out.printf("  [TxProducer] commitTransaction: txId=%s, 共 %d 条%n",
                    transactionalId, pendingRecords.size());
            pendingRecords.clear();
        }

        /** 回滚事务 */
        void abortTransaction() {
            broker.abortTransaction(transactionalId);
            System.out.printf("  [TxProducer] abortTransaction: txId=%s%n", transactionalId);
            pendingRecords.clear();
        }
    }

    /* ======================== Consumer ======================== */

    static class IdempotentConsumer {
        final String groupId;
        final Set<String/* messageId */> consumedIds = new HashSet<>();

        IdempotentConsumer(String groupId) {
            this.groupId = groupId;
        }

        /** 消费消息 (仅已提交) */
        void consume(List<Record> records) {
            System.out.printf("  [Consumer-%s] 拉取消息:%n", groupId);
            for (Record record : records) {
                if (!record.committed) {
                    System.out.printf("    SKIP(未提交): %s%n", record.value);
                    continue;
                }
                String msgId = record.pid.pid + "-" + record.seqNum;
                if (consumedIds.contains(msgId)) {
                    System.out.printf("    SKIP(幂等): %s%n", record.value);
                    continue;
                }
                consumedIds.add(msgId);
                System.out.printf("    CONSUME: %s%n", record.value);
            }
        }
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== Kafka 幂等与事务模拟 ==========\n");

        // 1. 幂等 Producer
        System.out.println("--- 1. 幂等 Producer (PID + SeqNum) ---");
        IdempotentBroker broker = new IdempotentBroker();
        IdempotentProducer producer = new IdempotentProducer(broker, true);

        // 正常发送 3 条消息
        producer.send(broker, "orders", 0, "order-1", "{\"id\":1}");
        producer.send(broker, "orders", 0, "order-2", "{\"id\":2}");
        producer.send(broker, "orders", 0, "order-3", "{\"id\":3}");

        // 模拟重复发送 (网络重试)
        System.out.println("\n  模拟: Producer 发送 order-2 后 ACK 丢失, 重试...");
        producer.retrySendDuplicate(broker, "orders", 0, "order-2", "{\"id\":2}");

        List<Record> allRecords = new ArrayList<>(broker.store);
        IdempotentConsumer consumer = new IdempotentConsumer("order-group");
        consumer.consume(allRecords);

        broker.printStats();

        // 2. Kafka 事务
        System.out.println("\n--- 2. Kafka 事务模拟 (init→begin→send→commit) ---");
        IdempotentBroker txBroker = new IdempotentBroker();

        // 成功事务
        System.out.println("\n  >>> 事务 1: 成功提交 <<<");
        TransactionalProducer txProducer1 = new TransactionalProducer("tx-order-1", txBroker);
        txProducer1.beginTransaction();
        txProducer1.send("orders", 0, "tx-order-1", "{\"id\":101,\"amount\":500}");
        txProducer1.send("payments", 0, "tx-pay-1", "{\"payId\":201,\"amount\":500}");
        txProducer1.commitTransaction();

        // 失败事务
        System.out.println("\n  >>> 事务 2: 回滚 <<<");
        TransactionalProducer txProducer2 = new TransactionalProducer("tx-order-2", txBroker);
        txProducer2.beginTransaction();
        txProducer2.send("orders", 0, "tx-order-2", "{\"id\":102,\"amount\":-1}");
        txProducer2.abortTransaction();

        txBroker.printStats();

        // 3. exactly-once 语义
        System.out.println("\n--- 3. Exactly-Once 语义分解 ---");
        System.out.println("""
                概念:
                  exactly-once = 幂等 (Broker端去重) + 事务 (跨分区原子写入)
                
                幂等 Producer:
                  - enable.idempotence=true → Broker 分配 PID
                  - 每个 TopicPartition 维护 SeqNum
                  - Broker 记录 (PID, TopicPartition) → 最后 SeqNum
                  - 重复 SeqNum → 丢弃; SeqNum 跳跃 → OutOfOrderSequenceException
                  - 保证: 单分区内 exactly-once
                
                事务 Producer:
                  - transactional.id → PID 持久化 (重启不变)
                  - initTransactions → 获取 PID + 找到 TransactionCoordinator
                  - begin → send → commit/abort
                  - 保证: 跨分区原子写入 (Kafka Streams EOS)
                
                幂等 vs 事务:
                  | 特性       | 幂等        | 事务              |
                  |----------|-----------|------------------|
                  | 范围       | 单分区      | 跨分区/跨 Topic    |
                  | 配置       | enable.idempotence | transactional.id |
                  | 语义       | 无重复      | 原子性 + 无重复     |
                  | PID 持久化 | 重启变化    | 重启不变           |
                  | 开销       | 低         | 中 (Coordinator)  |
                
                Kafka 事务典型场景 (consume-transform-produce):
                  1. Consumer 读取 topic-A
                  2. 处理/转换
                  3. Producer 写入 topic-B
                  4. sendOffsetsToTransaction() ← 消费位移也纳入事务
                  5. commitTransaction()
                  效果: 要么 A的offset+B的消息 都成功, 要么都失败 → exactly-once
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}