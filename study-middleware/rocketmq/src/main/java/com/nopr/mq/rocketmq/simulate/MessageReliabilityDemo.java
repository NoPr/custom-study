package com.nopr.mq.rocketmq.simulate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消息可靠性 —— 刷盘策略·主从复制·ACK 机制·重试队列
 * 【描述】模拟 RocketMQ 消息可靠性三重保障：SYNC_FLUSH vs ASYNC_FLUSH 刷盘策略、
 *         SYNC_MASTER vs ASYNC_MASTER 主从复制、四种可靠性矩阵对比。
 *         生产者重试 + Broker 持久化 + 消费者手动 ACK 全链路保障。
 * 【关键概念】同步刷盘、异步刷盘、同步复制、异步复制、ACK 确认、重试队列、
 *             消费幂等、可靠性矩阵
 * 【关联类】@see com.nopr.mq.kafka.simulate.MessageReliabilityDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class MessageReliabilityDemo {

    enum FlushPolicy {SYNC_FLUSH, ASYNC_FLUSH}

    enum ReplicatePolicy {SYNC_MASTER, ASYNC_MASTER}

    enum ConsumeStatus {CONSUME_SUCCESS, RECONSUME_LATER}

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
                System.out.printf("  [Consumer] %s 消费成功: %s, content=%s%n",
                        group, msg.msgId, msg.content);
                return ConsumeStatus.CONSUME_SUCCESS;
            } else {
                retryQueue.offer(msg);
                System.out.printf("  [Consumer] %s 消费失败 -> RECONSUME_LATER: %s%n", group, msg.msgId);
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

    static void scenario1_ProducerToBrokerLoss() {
        System.out.println("--- 场景 1: Producer -> Broker 网络抖动 ---");
        BrokerConfig config = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        ReliableBroker broker = new ReliableBroker(config);
        ReliableProducer producer = new ReliableProducer("order-producer", 3, 100);
        System.out.println("  模拟 Broker 宕机后重启...");
        boolean sent = producer.sendWithRetry(
                new Message("MSG-001", "{\"orderId\":1,\"amount\":100}", System.currentTimeMillis()), broker);
        System.out.printf("  结果: %s ← 重试机制保证不丢失%n%n", sent ? "最终成功" : "丢失");
    }

    static void scenario2_BrokerCrashLoss() {
        System.out.println("--- 场景 2: Broker 宕机 → 异步刷盘丢失 vs 同步刷盘安全 ---");

        BrokerConfig asyncConfig = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        ReliableBroker asyncBroker = new ReliableBroker(asyncConfig);
        ReliableProducer producer = new ReliableProducer("pay-producer", 1, 0);

        producer.sendWithRetry(
                new Message("MSG-ASYNC-01", "{\"payId\":1,\"amount\":500}", System.currentTimeMillis()), asyncBroker);
        System.out.println("  Broker 异步刷盘, 消息在内存中...");
        System.out.println("  >>> Broker 宕机! <<<");
        System.out.printf("  内存消息: %d 条 ← 这部分丢失%n", asyncBroker.memoryQueue.size());
        System.out.printf("  磁盘持久化: %d 条 ← 仅这部分安全%n", asyncBroker.diskStore.size());

        System.out.println("\n  对比: 同步刷盘");
        BrokerConfig syncConfig = new BrokerConfig(FlushPolicy.SYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);
        ReliableBroker syncBroker = new ReliableBroker(syncConfig);
        producer.sendWithRetry(
                new Message("MSG-SYNC-01", "{\"payId\":2,\"amount\":500}", System.currentTimeMillis()), syncBroker);
        System.out.printf("  磁盘持久化: %d 条 ← 同步刷盘, 消息不丢失%n%n", syncBroker.diskStore.size());
    }

    static void scenario3_ConsumerIdempotent() {
        System.out.println("--- 场景 3: Consumer 消费 + 业务幂等 ---");
        ReliableConsumer consumer = new ReliableConsumer("order-consumer");
        Message msg = new Message("MSG-ORDER-001", "{\"orderId\":10,\"amount\":999}", System.currentTimeMillis());
        consumer.consume(msg, false);
        consumer.retryConsume(true);
        consumer.consume(msg, true);
        System.out.println("  幂等机制: 基于 msgId 去重, 保证重复消费安全\n");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        System.out.println("========== 消息可靠性模拟 (RocketMQ) ==========\n");

        System.out.println("--- 0. 可靠性配置矩阵 ---");
        System.out.println("""
                配置组合                          | 可靠性 | 延迟 | 适用场景
                ASYNC_FLUSH + ASYNC_MASTER      |   低   |  低  | 日志采集
                ASYNC_FLUSH + SYNC_MASTER       |   中   |  中  | 普通业务
                SYNC_FLUSH + SYNC_MASTER        |   高   |  高  | 金融/支付
                """);

        System.out.println("--- 1. 刷盘策略对比 (SYNC_FLUSH vs ASYNC_FLUSH) ---");
        BrokerConfig asyncFlush = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        BrokerConfig syncFlush = new BrokerConfig(FlushPolicy.SYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);

        ReliableBroker asyncBroker = new ReliableBroker(asyncFlush);
        ReliableBroker syncBroker = new ReliableBroker(syncFlush);
        ReliableProducer producer = new ReliableProducer("test-producer", 1, 0);

        producer.sendWithRetry(new Message("MSG-F-01", "async flush test", System.currentTimeMillis()), asyncBroker);
        producer.sendWithRetry(new Message("MSG-F-02", "sync flush test", System.currentTimeMillis()), syncBroker);

        System.out.printf("%n  异步刷盘: %d 条在磁盘 (MappedFile 默认 4KB 页缓存写入)%n", asyncBroker.diskStore.size());
        System.out.printf("  同步刷盘: %d 条在磁盘 (force() 强制 fsync)%n%n", syncBroker.diskStore.size());

        System.out.println("--- 2. 主从复制策略对比 ---");
        System.out.println("  SYNC_MASTER: Master 等待 Slave 确认后才返回成功");
        System.out.println("  ASYNC_MASTER: Master 写成功即返回, 不等 Slave\n");

        BrokerConfig syncRepl = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);
        BrokerConfig asyncRepl = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        BrokerConfig slaveDown = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, false);

        ReliableBroker syncRepBroker = new ReliableBroker(syncRepl);
        ReliableBroker asyncRepBroker = new ReliableBroker(asyncRepl);
        producer.sendWithRetry(new Message("MSG-R-01", "sync replicate", System.currentTimeMillis()), syncRepBroker);
        producer.sendWithRetry(new Message("MSG-R-02", "async replicate", System.currentTimeMillis()), asyncRepBroker);

        System.out.println("  同步复制: 主备都有 → 主宕机备切换无丢失");
        System.out.println("  异步复制: 仅主有 → 主宕机未复制消息丢失\n");

        System.out.println("--- 3. 生产者重试机制 ---");
        ReliableProducer retryProducer = new ReliableProducer("retry-producer", 3, 50);
        retryProducer.sendWithRetry(new Message("MSG-RETRY-01", "retry test", System.currentTimeMillis()), asyncBroker);

        scenario1_ProducerToBrokerLoss();
        scenario2_BrokerCrashLoss();
        scenario3_ConsumerIdempotent();

        System.out.println("--- 4. 消息可靠性总结 ---");
        System.out.println("""
                三层保障 (RocketMQ):
                  1. 生产端: 同步发送 + 重试 + 故障延迟 (sendLatencyFaultEnable)
                  2. Broker端: 同步刷盘 + 同步复制 + DLedger (Raft) 自动切换
                  3. 消费端: 手动 ACK + 消费幂等 + 死信队列 (DLQ)
                关键原则:
                  - RocketMQ 同步双写 > 同步刷盘 (磁盘顺序写远快于网络)
                  - 消息 ID 全局唯一, 消费端基于 msgId 幂等
                  - at-least-once + 幂等消费 = exactly-once 效果
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}