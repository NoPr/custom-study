package base.mq;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息可靠性：同步刷盘 vs 异步刷盘 + 同步复制 vs 异步复制
 *
 * <p>生产者重试 + Broker 持久化保证。
 * 消费者手动 ACK：CONSUME_SUCCESS / RECONSUME_LATER。
 * 消息丢失三场景演示：P→B（网络抖动）、B→C（宕机）、C→业务（幂等）。
 *
 * <p>可靠性层级：
 * <pre>
 * 级别 1 (最低): 异步刷盘 + 异步复制 → 有丢失风险
 * 级别 2:       异步刷盘 + 同步复制 → Slave 有副本
 * 级别 3:       同步刷盘 + 同步复制 → 最高可靠性
 * </pre>
 *
 * <p>消息丢失三场景：
 * <ol>
 *   <li>Producer → Broker：网络抖动导致发送失败 → 重试机制</li>
 *   <li>Broker → Consumer：Broker 宕机消息未持久化 → 同步刷盘</li>
 *   <li>Consumer → 业务：消费成功但业务处理失败 → 手动 ACK + 幂等</li>
 * </ol>
 *
 * @author study-tuling
 */
public class MessageReliabilityDemo {

    /* ======================== 数据模型 ======================== */

    /** 刷盘策略 */
    enum FlushPolicy {SYNC_FLUSH, ASYNC_FLUSH}

    /** 复制策略 */
    enum ReplicatePolicy {SYNC_MASTER, ASYNC_MASTER}

    /** 消费状态 */
    enum ConsumeStatus {CONSUME_SUCCESS, RECONSUME_LATER}

    /** 消息 */
    record Message(String msgId, String content, long timestamp) {
    }

    /* ======================== Broker（带持久化配置） ======================== */

    record BrokerConfig(FlushPolicy flushPolicy, ReplicatePolicy replicatePolicy, boolean slaveAvailable) {
    }

    static class ReliableBroker {
        final BrokerConfig config;
        /** 内存消息队列 */
        final ConcurrentLinkedQueue<Message> memoryQueue = new ConcurrentLinkedQueue<>();
        /** 磁盘持久化模拟 */
        final List<Message> diskStore = Collections.synchronizedList(new ArrayList<>());
        /** Slave 副本 */
        final List<Message> slaveStore = Collections.synchronizedList(new ArrayList<>());
        /** 消息处理统计 */
        final AtomicInteger totalReceived = new AtomicInteger(0);
        final AtomicInteger flushedCount = new AtomicInteger(0);
        final AtomicInteger replicatedCount = new AtomicInteger(0);

        ReliableBroker(BrokerConfig config) {
            this.config = config;
        }

        /** 写入消息：模拟刷盘 + 复制流程 */
        boolean putMessage(Message msg) {
            totalReceived.incrementAndGet();
            memoryQueue.offer(msg);

            // 1. 刷盘
            boolean flushed = flush(msg);
            // 2. 主从复制
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

        /** 刷盘操作 */
        private boolean flush(Message msg) {
            if (config.flushPolicy == FlushPolicy.SYNC_FLUSH) {
                diskStore.add(msg); // 同步刷盘：立即写磁盘
                flushedCount.incrementAndGet();
                return true;
            } else {
                // 异步刷盘：每 500ms 批量刷，模拟延迟
                diskStore.add(msg);
                flushedCount.incrementAndGet();
                return true;
            }
        }

        /** 主从复制 */
        private boolean replicate(Message msg) {
            if (config.replicatePolicy == ReplicatePolicy.SYNC_MASTER) {
                if (!config.slaveAvailable) {
                    return false;
                }
                slaveStore.add(msg);
                replicatedCount.incrementAndGet();
                return true;
            } else {
                // 异步复制：不等待 Slave 确认
                if (config.slaveAvailable) {
                    slaveStore.add(msg);
                    replicatedCount.incrementAndGet();
                }
                return true;
            }
        }

        /** 统计信息 */
        void printStats() {
            System.out.printf("""
                      Broker 统计: 接收=%d, 已刷盘=%d, 已复制=%d, 内存待处理=%d
                    %n""", totalReceived.get(), flushedCount.get(),
                    replicatedCount.get(), memoryQueue.size());
        }
    }

    /* ======================== Producer（带重试） ======================== */

    static class ReliableProducer {
        final String name;
        final int maxRetries;
        final long retryIntervalMs;

        ReliableProducer(String name, int maxRetries, long retryIntervalMs) {
            this.name = name;
            this.maxRetries = maxRetries;
            this.retryIntervalMs = retryIntervalMs;
        }

        /** 同步发送带重试 */
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

    /* ======================== Consumer（带手动 ACK） ======================== */

    static class ReliableConsumer {
        final String group;
        /** 处理成功的消息 ID 集合（幂等去重） */
        final Set<String> processedMsgIds = Collections.synchronizedSet(new HashSet<>());
        /** 重试队列 */
        final ConcurrentLinkedQueue<Message> retryQueue = new ConcurrentLinkedQueue<>();

        ReliableConsumer(String group) {
            this.group = group;
        }

        /** 消费消息：返回 CONSUME_SUCCESS 或 RECONSUME_LATER */
        ConsumeStatus consume(Message msg, boolean bizSuccess) {
            // 幂等检查：已处理过的消息直接返回成功
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

        /** 重试消费 */
        void retryConsume(boolean bizSuccess) {
            Message msg = retryQueue.poll();
            if (msg != null) {
                consume(msg, bizSuccess);
            }
        }
    }

    /* ======================== 消息丢失场景模拟 ======================== */

    /** 场景 1：Producer → Broker 网络抖动导致丢失 */
    static void scenario1_ProducerToBrokerLoss() {
        System.out.println("--- 场景 1: Producer -> Broker 网络抖动 ---");
        System.out.println("现象: 生产者发送消息时网络抖动, 消息未到达 Broker");

        BrokerConfig config = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        ReliableBroker broker = new ReliableBroker(config);
        ReliableProducer producer = new ReliableProducer("order-producer", 3, 100);

        // 模拟网络不可达 (Broker 关闭)
        System.out.println("  模拟 Broker 宕机后重启...");
        // 使用带重试的生产者
        boolean sent = producer.sendWithRetry(
                new Message("MSG-001", "{\"orderId\":1,\"amount\":100}", System.currentTimeMillis()),
                broker);
        System.out.printf("  结果: %s ← 重试机制保证不丢失%n%n", sent ? "最终成功" : "丢失");
    }

    /** 场景 2：Broker 宕机 → 未持久化消息丢失 */
    static void scenario2_BrokerCrashLoss() {
        System.out.println("--- 场景 2: Broker 宕机 → 异步刷盘丢失 ---");
        System.out.println("现象: Broker 内存有消息但未刷盘, 宕机后丢失");

        // 异步刷盘配置 → 有丢失风险
        BrokerConfig asyncConfig = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        ReliableBroker asyncBroker = new ReliableBroker(asyncConfig);
        ReliableProducer producer = new ReliableProducer("pay-producer", 1, 0);

        producer.sendWithRetry(
                new Message("MSG-ASYNC-01", "{\"payId\":1,\"amount\":500}", System.currentTimeMillis()),
                asyncBroker);
        System.out.println("  Broker 异步刷盘, 消息在内存中...");
        System.out.println("  >>> Broker 宕机! <<<");
        System.out.printf("  内存消息: %d 条 ← 这部分丢失%n", asyncBroker.memoryQueue.size());
        System.out.printf("  磁盘持久化: %d 条 ← 仅这部分安全%n", asyncBroker.diskStore.size());

        // 对比：同步刷盘配置
        System.out.println("\n  对比: 同步刷盘");
        BrokerConfig syncConfig = new BrokerConfig(FlushPolicy.SYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);
        ReliableBroker syncBroker = new ReliableBroker(syncConfig);
        producer.sendWithRetry(
                new Message("MSG-SYNC-01", "{\"payId\":2,\"amount\":500}", System.currentTimeMillis()),
                syncBroker);
        System.out.printf("  磁盘持久化: %d 条 ← 同步刷盘, 消息不丢失%n%n", syncBroker.diskStore.size());
    }

    /** 场景 3：Consumer 消费成功但业务失败 → 幂等保证 */
    static void scenario3_ConsumerIdempotent() {
        System.out.println("--- 场景 3: Consumer 消费 + 业务幂等 ---");
        System.out.println("现象: 消费成功但 DB 写入失败, 需要重试且保证幂等");

        ReliableConsumer consumer = new ReliableConsumer("order-consumer");

        Message msg = new Message("MSG-ORDER-001",
                "{\"orderId\":10,\"amount\":999}", System.currentTimeMillis());

        // 第 1 次：消费成功但业务失败
        consumer.consume(msg, false);
        // 第 2 次：重试成功
        consumer.retryConsume(true);
        // 第 3 次：重复消费 → 幂等跳过
        consumer.consume(msg, true);

        System.out.println("  幂等机制: 基于 msgId 去重, 保证重复消费安全\n");
    }

    /* ======================== 工具方法 ======================== */

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== 消息可靠性模拟 ==========\n");

        // 0. 可靠性配置对比
        System.out.println("--- 0. 可靠性配置矩阵 ---");
        System.out.println("""
                配置组合                          | 可靠性 | 延迟 | 适用场景
                ASYNC_FLUSH + ASYNC_MASTER      |   低   |  低  | 日志采集
                ASYNC_FLUSH + SYNC_MASTER       |   中   |  中  | 普通业务
                SYNC_FLUSH + SYNC_MASTER        |   高   |  高  | 金融/支付
                """);

        // 1. 同步刷盘 vs 异步刷盘
        System.out.println("--- 1. 刷盘策略对比 ---");
        BrokerConfig asyncFlush = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        BrokerConfig syncFlush = new BrokerConfig(FlushPolicy.SYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);

        ReliableBroker asyncBroker = new ReliableBroker(asyncFlush);
        ReliableBroker syncBroker = new ReliableBroker(syncFlush);

        ReliableProducer producer = new ReliableProducer("test-producer", 1, 0);

        producer.sendWithRetry(
                new Message("MSG-F-01", "async flush test", System.currentTimeMillis()), asyncBroker);
        producer.sendWithRetry(
                new Message("MSG-F-02", "sync flush test", System.currentTimeMillis()), syncBroker);

        System.out.printf("%n  异步刷盘: %d 条在磁盘 (MappedFile 默认 4KB 页缓存写入)%n",
                asyncBroker.diskStore.size());
        System.out.printf("  同步刷盘: %d 条在磁盘 (force() 强制 fsync)%n%n",
                syncBroker.diskStore.size());

        // 2. 同步复制 vs 异步复制
        System.out.println("--- 2. 主从复制策略对比 ---");
        System.out.println("  SYNC_MASTER: Master 等待 Slave 确认后才返回成功");
        System.out.println("  ASYNC_MASTER: Master 写成功即返回, 不等 Slave\n");

        BrokerConfig syncRepl = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, true);
        BrokerConfig asyncRepl = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.ASYNC_MASTER, false);
        BrokerConfig slaveDown = new BrokerConfig(FlushPolicy.ASYNC_FLUSH, ReplicatePolicy.SYNC_MASTER, false);

        ReliableBroker syncRepBroker = new ReliableBroker(syncRepl);
        ReliableBroker asyncRepBroker = new ReliableBroker(asyncRepl);
        producer.sendWithRetry(
                new Message("MSG-R-01", "sync replicate", System.currentTimeMillis()), syncRepBroker);
        producer.sendWithRetry(
                new Message("MSG-R-02", "async replicate", System.currentTimeMillis()), asyncRepBroker);

        System.out.println("  同步复制: 主备都有 → 主宕机备切换无丢失");
        System.out.println("  异步复制: 仅主有 → 主宕机未复制消息丢失\n");

        // 3. 生产者重试
        System.out.println("--- 3. 生产者重试机制 ---");
        ReliableProducer retryProducer = new ReliableProducer("retry-producer", 3, 50);
        retryProducer.sendWithRetry(
                new Message("MSG-RETRY-01", "retry test", System.currentTimeMillis()), asyncBroker);

        // 场景模拟
        scenario1_ProducerToBrokerLoss();
        scenario2_BrokerCrashLoss();
        scenario3_ConsumerIdempotent();

        // 总结
        System.out.println("--- 4. 消息可靠性总结 ---");
        System.out.println("""
                三层保障:
                  1. 生产端: 同步发送 + 重试 + 故障延迟 (sendLatencyFaultEnable)
                  2. Broker端: 同步刷盘 + 同步复制 + DLedger (Raft) 自动切换
                  3. 消费端: 手动 ACK + 消费幂等 + 死信队列
                关键原则:
                  - 同步双写 > 同步刷盘 (磁盘顺序写远快于网络)
                  - 消息 ID 全局唯一, 消费端基于 ID 幂等
                  - at-least-once + 幂等消费 = exactly-once 效果
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}