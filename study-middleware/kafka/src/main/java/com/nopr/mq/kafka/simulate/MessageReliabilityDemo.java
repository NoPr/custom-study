package com.nopr.mq.kafka.simulate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】消息可靠性 —— ACK 机制·ISR·min.insync.replicas·生产者重试
 * 【描述】模拟 Kafka 消息可靠性保障：acks=0/1/-1(all) 三种级别对比、
 *         min.insync.replicas 最小同步副本数约束、生产者重试 + 幂等、
 *         消费者手动提交 offset。演示各种配置下的消息丢失与不丢失场景。
 * 【关键概念】acks、min.insync.replicas、ISR、生产者重试、enable.idempotence、
 *             手动提交 offset、可靠性矩阵
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.MessageReliabilityDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class MessageReliabilityDemo {

    enum FlushPolicy {SYNC_FLUSH, ASYNC_FLUSH}

    enum ReplicatePolicy {SYNC_MASTER, ASYNC_MASTER}

    enum ConsumeStatus {CONSUME_SUCCESS, RECONSUME_LATER}

    /** ACK 级别 (Kafka 特有) */
    enum AckLevel {ACKS_0, ACKS_1, ACKS_ALL}

    record Message(String msgId, String content, long timestamp) {}

    record BrokerConfig(FlushPolicy flushPolicy, ReplicatePolicy replicatePolicy, boolean slaveAvailable) {}

    static class ReliableBroker {
        final BrokerConfig config;
        final ConcurrentLinkedQueue<Message> memoryQueue = new ConcurrentLinkedQueue<>();
        final List<Message> diskStore = Collections.synchronizedList(new ArrayList<>());
        final List<Message> slaveStore = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger totalReceived = new AtomicInteger(0);
        final AtomicInteger flushedCount = new AtomicInteger(0);
        final AtomicInteger replicatedCount = new AtomicInteger(0);

        ReliableBroker(BrokerConfig config) {
            this.config = config;
        }

        boolean putMessage(Message msg) {
            totalReceived.incrementAndGet();
            memoryQueue.offer(msg);

            boolean flushed = flush(msg);
            boolean replicated = replicate(msg);

            if (config.flushPolicy == FlushPolicy.SYNC_FLUSH && !flushed) {
                System.out.printf("  [Broker] ERROR: %s 同步刷盘失败!%n", msg.msgId);
                return false;
            }
            if (config.replicatePolicy == ReplicatePolicy.SYNC_MASTER && !replicated) {
                System.out.printf("  [Broker] ERROR: %s 同步复制失败! Slave 不可用%n", msg.msgId);
                return false;
            }

            System.out.printf("  [Broker] 消息已接收: %s, flush=%s, replicate=%s%n",
                    msg.msgId, flushed ? "OK" : "ASYNC_PENDING", replicated ? "OK" : "ASYNC_PENDING");
            return true;
        }

        private boolean flush(Message msg) {
            diskStore.add(msg);
            flushedCount.incrementAndGet();
            return true;
        }

        private boolean replicate(Message msg) {
            if (config.replicatePolicy == ReplicatePolicy.SYNC_MASTER) {
                if (!config.slaveAvailable) {
                    return false;
                }
                slaveStore.add(msg);
                replicatedCount.incrementAndGet();
                return true;
            } else {
                if (config.slaveAvailable) {
                    slaveStore.add(msg);
                    replicatedCount.incrementAndGet();
                }
                return true;
            }
        }

        void printStats() {
            System.out.printf("""
                      Broker 统计: 接收=%d, 已刷盘=%d, 已复制=%d, 内存待处理=%d
                    %n""", totalReceived.get(), flushedCount.get(),
                    replicatedCount.get(), memoryQueue.size());
        }
    }

    static class ReliableProducer {
        final String name;
        final int maxRetries;
        final long retryIntervalMs;

        ReliableProducer(String name, int maxRetries, long retryIntervalMs) {
            this.name = name;
            this.maxRetries = maxRetries;
            this.retryIntervalMs = retryIntervalMs;
        }

        boolean sendWithRetry(Message msg, ReliableBroker broker) {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                System.out.printf("  [Producer] %s 第 %d 次发送: %s%n", name, attempt, msg.msgId);
                boolean success = broker.putMessage(msg);
                if (success) {
                    System.out.printf("  [Producer] %s 发送成功 (第 %d 次)%n", name, attempt);
                    return true;
                }
                if (attempt < maxRetries) {
                    System.out.printf("  [Producer] %s 重试等待 %dms...%n", name, retryIntervalMs);
                    sleep(retryIntervalMs);
                }
            }
            System.out.printf("  [Producer] %s 发送失败: %s (已重试 %d 次)%n", name, msg.msgId, maxRetries);
            return false;
        }
    }

    static class ReliableConsumer {
        final String group;
        final Set<String> processedMsgIds = Collections.synchronizedSet(new HashSet<>());
        final ConcurrentLinkedQueue<Message> retryQueue = new ConcurrentLinkedQueue<>();

        ReliableConsumer(String group) {
            this.group = group;
        }

        ConsumeStatus consume(Message msg, boolean bizSuccess) {
            if (processedMsgIds.contains(msg.msgId)) {
                System.out.printf("  [Consumer] %s 幂等跳过: %s (已处理)%n", group, msg.msgId);
                return ConsumeStatus.CONSUME_SUCCESS;
            }

            if (bizSuccess) {
                processedMsgIds.add(msg.msgId);
                System.out.printf("  [Consumer] %s 消费成功: %s → 手动 commit offset%n", group, msg.msgId);
                return ConsumeStatus.CONSUME_SUCCESS;
            } else {
                retryQueue.offer(msg);
                System.out.printf("  [Consumer] %s 消费失败 → RECONSUME_LATER: %s%n", group, msg.msgId);
                return ConsumeStatus.RECONSUME_LATER;
            }
        }

        void retryConsume(boolean bizSuccess) {
            Message msg = retryQueue.poll();
            if (msg != null) {
                consume(msg, bizSuccess);
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void ackLevelDemo() {
        System.out.println("--- Kafka ACK 级别对比 ---");
        System.out.println("""
                acks=0:   Producer 不等待 Broker 确认 (最快,可能丢消息)
                          用途: 日志采集等可容忍丢失场景
                         
                acks=1:   Leader 写入成功即返回
                          风险: Leader 宕机且 Follower 未同步 → 消息丢失
                          用途: 默认配置,适用于大多数场景
                         
                acks=all: 所有 ISR 副本确认 + min.insync.replicas 满足才返回
                          最可靠,但延迟最高
                          配合: min.insync.replicas=2 (最少 2 个 ISR)
                          用途: 金融/Payment 等不允许丢消息场景
                """);
    }

    public static void main(String[] args) {
        System.out.println("========== 消息可靠性模拟 (Kafka) ==========\n");

        System.out.println("--- 0. Kafka ACK 可靠性矩阵 ---");
        System.out.println("""
                ACK 级别 | ISR 要求        | 可靠性 | 延迟 | Kafka 默认
                acks=0   | 无              |   无   |  低  | -
                acks=1   | Leader 写入     |   中   |  中  | 默认
                acks=all | ISR 全部确认    |   高   |  高  | -
                """);

        ackLevelDemo();

        System.out.println("--- 1. ISR + min.insync.replicas 保障 ---");
        System.out.println("  min.insync.replicas=2, ISR=[0,1,2], Leader=0");
        System.out.println("  acks=all → 等待 ISR 中至少 2 个副本确认");
        System.out.println("  如果 ISR 缩容到 1 个 < min.insync.replicas → 拒绝写入");

        System.out.println("\n--- 2. 生产者重试 + 幂等 ---");
        ReliableBroker broker = new ReliableBroker(
                new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false));
        ReliableProducer retryProducer = new ReliableProducer("kafka-retry-producer", 3, 50);
        retryProducer.sendWithRetry(
                new Message("MSG-KAFKA-01", "retry+idempotent", System.currentTimeMillis()), broker);

        System.out.println("\n--- 3. Consumer 手动 commit offset + 幂等 ---");
        ReliableConsumer consumer = new ReliableConsumer("kafka-consumer-group");
        Message msg = new Message("MSG-K-001", "{\"order\":999}", System.currentTimeMillis());
        consumer.consume(msg, false);
        consumer.retryConsume(true);
        consumer.consume(msg, true);
        System.out.println("  enable.auto.commit=false → 手动提交 offset + 消费幂等");

        System.out.println("\n--- 4. Kafka 可靠性总结 ---");
        System.out.println("""
                Kafka 三层保障:
                  1. 生产端: acks=all + retries + enable.idempotence=true
                  2. Broker端: min.insync.replicas + ISR + (unclean.leader.election.enable=false)
                  3. 消费端: enable.auto.commit=false + 手动 commit + 消费幂等
                
                Kafka vs RocketMQ 可靠性对比:
                  Kafka:   依赖 ISR + min.insync.replicas (基于 Partitiion 副本)
                  RocketMQ: 依赖 SYNC_FLUSH + SYNC_MASTER (基于 CommitLog 物理文件)
                  共同:    都依赖副本同步 + 生产者重试 + 消费幂等
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}