package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】Kafka 幂等与事务 —— PID·SeqNum·TransactionCoordinator
 * 【描述】模拟 Kafka 幂等（PID + SeqNum 去重，Broker 侧维护 per-partition 序列号）
 *         和事务（initTransactions→begin→send→commit/abort），
 *         与 Kafka Transaction (KIP-98) 概念对齐。
 * 【关键概念】幂等、PID(Producer ID)、SeqNum、事务、TransactionCoordinator、
 *             initTransactions、KIP-98、消费端幂等
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.TransactionDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class KafkaIdempotentDemo {

    record ProducerId(long pid, short epoch) {}

    record Record(String topic, int partition, String key, String value,
                  ProducerId pid, long seqNum, boolean committed) {}

    static class IdempotentBroker {
        final Map<String, Long> lastCommittedSeqNum = new ConcurrentHashMap<>();
        final List<Record> store = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<Record>> transactionalBuffers = new ConcurrentHashMap<>();
        final AtomicLong pidGenerator = new AtomicLong(1000);
        final AtomicInteger duplicateCount = new AtomicInteger(0);
        final AtomicInteger acceptedCount = new AtomicInteger(0);
        final AtomicInteger outOfOrderCount = new AtomicInteger(0);

        ProducerId allocatePID() {
            return new ProducerId(pidGenerator.incrementAndGet(), (short) 0);
        }

        boolean acceptIdempotent(Record record) {
            String key = record.pid.pid + "-" + record.topic + "-" + record.partition;
            Long lastSeq = lastCommittedSeqNum.get(key);

            if (lastSeq != null && record.seqNum <= lastSeq) {
                System.out.printf("  [Broker] 幂等去重: PID=%d, SeqNum=%d (已提交=%d)%n",
                        record.pid.pid, record.seqNum, lastSeq);
                duplicateCount.incrementAndGet();
                return false;
            }

            if (lastSeq != null && record.seqNum > lastSeq + 1) {
                System.out.printf("  [Broker] 乱序检测: PID=%d, 期望 SeqNum=%d, 实际=%d%n",
                        record.pid.pid, lastSeq + 1, record.seqNum);
                outOfOrderCount.incrementAndGet();
            }

            lastCommittedSeqNum.put(key, record.seqNum);
            store.add(record);
            acceptedCount.incrementAndGet();
            System.out.printf("  [Broker] 接收: PID=%d, SeqNum=%d, topic=%s-%d, value=%s%n",
                    record.pid.pid, record.seqNum, record.topic, record.partition, record.value);
            return true;
        }

        void transactionalWrite(String transactionalId, Record record) {
            transactionalBuffers.computeIfAbsent(transactionalId, k -> new ArrayList<>()).add(record);
            System.out.printf("  [Broker] 事务写入(未提交): txId=%s, %s%n", transactionalId, record.value);
        }

        void commitTransaction(String transactionalId) {
            List<Record> records = transactionalBuffers.remove(transactionalId);
            if (records != null) {
                for (Record r : records) {
                    Record committed = new Record(r.topic, r.partition, r.key, r.value, r.pid, r.seqNum, true);
                    store.add(committed);
                }
                System.out.printf("  [Broker] 事务提交: txId=%s, %d 条消息%n", transactionalId, records.size());
            }
        }

        void abortTransaction(String transactionalId) {
            List<Record> records = transactionalBuffers.remove(transactionalId);
            if (records != null) {
                System.out.printf("  [Broker] 事务回滚: txId=%s, %d 条消息丢弃%n", transactionalId, records.size());
            }
        }

        void printStats() {
            System.out.printf("""
                    Broker 统计: 接收=%d, 去重=%d, 乱序=%d, 事务缓冲=%d
                  %n""", acceptedCount.get(), duplicateCount.get(), outOfOrderCount.get(), transactionalBuffers.size());
        }
    }

    static class IdempotentProducer {
        final ProducerId pid;
        final Map<String, Long> seqNumMap = new ConcurrentHashMap<>();
        boolean idempotenceEnabled;

        IdempotentProducer(IdempotentBroker broker, boolean idempotenceEnabled) {
            this.pid = broker.allocatePID();
            this.idempotenceEnabled = idempotenceEnabled;
            System.out.printf("  [Producer] 初始化: PID=%d, idempotence=%s%n", pid.pid, idempotenceEnabled);
        }

        void send(IdempotentBroker broker, String topic, int partition, String key, String value) {
            String tpKey = topic + "-" + partition;
            long seqNum = seqNumMap.compute(tpKey, (k, v) -> v == null ? 1 : v + 1);
            Record record = new Record(topic, partition, key, value, pid, seqNum, true);
            System.out.printf("  [Producer] 发送: PID=%d, SeqNum=%d, %s=%s%n", pid.pid, seqNum, key, value);
            broker.acceptIdempotent(record);
        }

        void retrySendDuplicate(IdempotentBroker broker, String topic, int partition, String key, String value) {
            String tpKey = topic + "-" + partition;
            long currentSeq = seqNumMap.getOrDefault(tpKey, 0L);
            Record duplicate = new Record(topic, partition, key, value,
                    new ProducerId(pid.pid, pid.epoch), currentSeq, true);
            System.out.printf("  [Producer] 重试(重复): PID=%d, SeqNum=%d (相同!), %s=%s%n",
                    pid.pid, currentSeq, key, value);
            boolean accepted = broker.acceptIdempotent(duplicate);
            if (!accepted) {
                System.out.println("  [Producer] 幂等生效: Broker 丢弃重复消息");
            }
        }
    }

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

        void beginTransaction() {
            pendingRecords.clear();
            System.out.printf("  [TxProducer] beginTransaction: txId=%s%n", transactionalId);
        }

        void send(String topic, int partition, String key, String value) {
            long seqNum = idempotentProducer.seqNumMap.compute(
                    topic + "-" + partition, (k, v) -> v == null ? 1 : v + 1);
            Record record = new Record(topic, partition, key, value,
                    idempotentProducer.pid, seqNum, false);
            pendingRecords.add(record);
            broker.transactionalWrite(transactionalId, record);
            System.out.printf("  [TxProducer] send(事务中): PID=%d, SeqNum=%d, %s=%s%n",
                    idempotentProducer.pid.pid, seqNum, key, value);
        }

        void commitTransaction() {
            broker.commitTransaction(transactionalId);
            System.out.printf("  [TxProducer] commitTransaction: txId=%s, 共 %d 条%n",
                    transactionalId, pendingRecords.size());
            pendingRecords.clear();
        }

        void abortTransaction() {
            broker.abortTransaction(transactionalId);
            System.out.printf("  [TxProducer] abortTransaction: txId=%s%n", transactionalId);
            pendingRecords.clear();
        }
    }

    static class IdempotentConsumer {
        final String groupId;
        final Set<String> consumedIds = new HashSet<>();

        IdempotentConsumer(String groupId) { this.groupId = groupId; }

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

    public static void main(String[] args) {
        System.out.println("========== Kafka 幂等与事务模拟 ==========\n");

        System.out.println("--- 1. 幂等 Producer (PID + SeqNum) ---");
        IdempotentBroker broker = new IdempotentBroker();
        IdempotentProducer producer = new IdempotentProducer(broker, true);

        producer.send(broker, "orders", 0, "order-1", "{\"id\":1}");
        producer.send(broker, "orders", 0, "order-2", "{\"id\":2}");
        producer.send(broker, "orders", 0, "order-3", "{\"id\":3}");

        System.out.println("\n  模拟: Producer 发送 order-2 后 ACK 丢失, 重试...");
        producer.retrySendDuplicate(broker, "orders", 0, "order-2", "{\"id\":2}");

        List<Record> allRecords = new ArrayList<>(broker.store);
        IdempotentConsumer consumer = new IdempotentConsumer("order-group");
        consumer.consume(allRecords);
        broker.printStats();

        System.out.println("\n--- 2. Kafka 事务模拟 (init→begin→send→commit) ---");
        IdempotentBroker txBroker = new IdempotentBroker();

        System.out.println("\n  >>> 事务 1: 成功提交 <<<");
        TransactionalProducer txProducer1 = new TransactionalProducer("tx-order-1", txBroker);
        txProducer1.beginTransaction();
        txProducer1.send("orders", 0, "tx-order-1", "{\"id\":101,\"amount\":500}");
        txProducer1.send("payments", 0, "tx-pay-1", "{\"payId\":201,\"amount\":500}");
        txProducer1.commitTransaction();

        System.out.println("\n  >>> 事务 2: 回滚 <<<");
        TransactionalProducer txProducer2 = new TransactionalProducer("tx-order-2", txBroker);
        txProducer2.beginTransaction();
        txProducer2.send("orders", 0, "tx-order-2", "{\"id\":102,\"amount\":-1}");
        txProducer2.abortTransaction();

        txBroker.printStats();

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