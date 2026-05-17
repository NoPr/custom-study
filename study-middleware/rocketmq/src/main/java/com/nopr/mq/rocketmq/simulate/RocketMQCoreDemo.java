package com.nopr.mq.rocketmq.simulate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】RocketMQ 核心架构 —— NameServer·Broker·CommitLog·ConsumeQueue
 * 【描述】手写简化 NameServer（路由注册表）、Broker（CommitLog + ConsumeQueue）、
 *         Producer（路由查询→选队列→发送）、Consumer（Pull 拉取）。完整演示
 *         RocketMQ 的消息存储与消费全链路。
 * 【关键概念】NameServer、Broker、CommitLog、ConsumeQueue、MessageQueue、
 *             路由发现、Pull 消费、QueueData、BrokerData
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class RocketMQCoreDemo {

    /* ======================== 数据模型 ======================== */

    /** 队列路由数据，描述一个 Broker 上某个 Topic 的队列分布 */
    record QueueData(String brokerName, int readQueueNums, int writeQueueNums, int perm) {
        static QueueData of(String brokerName) {
            return new QueueData(brokerName, 4, 4, 6); // 默认 4 队列，可读可写
        }
    }

    /** Broker 注册信息 */
    record BrokerData(String clusterName, String brokerName,
                      Map<Long/* brokerId 0=Master */, String/* address */> brokerAddrs) {
        static BrokerData of(String clusterName, String brokerName, String masterAddr, String slaveAddr) {
            Map<Long, String> addrs = new LinkedHashMap<>();
            addrs.put(0L, masterAddr);
            addrs.put(1L, slaveAddr);
            return new BrokerData(clusterName, brokerName, addrs);
        }
    }

    /** 消息实体 */
    record Message(String topic, String tags, byte[] body, String keys, int delayTimeLevel) {
        static Message of(String topic, String tags, String content) {
            return new Message(topic, tags, content.getBytes(StandardCharsets.UTF_8), null, 0);
        }
    }

    /** CommitLog 条目：物理存储单元 */
    record CommitLogEntry(long offset, int size, String topic, int queueId, int queueOffset,
                          String tags, byte[] body) {
    }

    /** ConsumeQueue 条目：逻辑索引，每条 20 字节 */
    record ConsumeQueueEntry(long commitLogOffset, int size, long tagCode) {
        static ConsumeQueueEntry of(long offset, int size, long tagCode) {
            return new ConsumeQueueEntry(offset, size, tagCode);
        }
    }

    /** 消息拉取结果 */
    record PullResult(long nextOffset, long minOffset, long maxOffset, List<MessageExt> messages) {
    }

    /** 消费消息扩展 */
    record MessageExt(long queueOffset, long commitLogOffset, String topic, String tags, byte[] body) {
    }

    /* ======================== NameServer（简化版） ======================== */

    /** 模拟 NameServer：路由注册表 */
    static class SimpleNameServer {
        /** Topic → 队列数据列表 */
        final ConcurrentHashMap<String, List<QueueData>> topicQueueTable = new ConcurrentHashMap<>();
        /** BrokerName → Broker 数据 */
        final ConcurrentHashMap<String, BrokerData> brokerAddrTable = new ConcurrentHashMap<>();

        /** Broker 向 NameServer 注册 */
        void registerBroker(String clusterName, String brokerName, String masterAddr, String slaveAddr) {
            BrokerData brokerData = BrokerData.of(clusterName, brokerName, masterAddr, slaveAddr);
            brokerAddrTable.put(brokerName, brokerData);
            System.out.printf("  [NameServer] Broker 注册: %s -> master=%s, slave=%s%n",
                    brokerName, masterAddr, slaveAddr);
        }

        /** Broker 注册 Topic 路由信息 */
        void registerTopic(String topic, QueueData queueData) {
            topicQueueTable.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(queueData);
            System.out.printf("  [NameServer] Topic 路由注册: %s -> broker=%s, rQN=%d, wQN=%d%n",
                    topic, queueData.brokerName, queueData.readQueueNums, queueData.writeQueueNums);
        }

        /** Producer/Consumer 拉取 Topic 路由信息 */
        List<QueueData> getRouteInfo(String topic) {
            List<QueueData> routes = topicQueueTable.get(topic);
            if (routes == null) {
                System.out.printf("  [NameServer] WARN: Topic %s 无路由信息%n", topic);
                return Collections.emptyList();
            }
            return routes;
        }

        /** 根据 BrokerName 获取 Master 地址 */
        String getMasterAddr(String brokerName) {
            BrokerData data = brokerAddrTable.get(brokerName);
            return data != null ? data.brokerAddrs().get(0L) : null;
        }
    }

    /* ======================== Broker 存储模型 ======================== */

    /** 简化版 Broker，包含 CommitLog 和 ConsumeQueue */
    static class SimpleBroker {
        final String brokerName;
        final String clusterName;
        /** CommitLog：全局消息顺序追加 */
        final List<CommitLogEntry> commitLog = new ArrayList<>();
        /** Topic → QueueId → ConsumeQueueEntry 列表 */
        final Map<String, Map<Integer, List<ConsumeQueueEntry>>> consumeQueues = new LinkedHashMap<>();
        /** 每个 Topic-Queue 的当前偏移量 */
        final Map<String, Map<Integer, Long>> queueOffsets = new LinkedHashMap<>();
        /** CommitLog 全局偏移量 */
        long commitLogOffset = 0;

        SimpleBroker(String brokerName, String clusterName) {
            this.brokerName = brokerName;
            this.clusterName = clusterName;
        }

        /** 写入消息到 CommitLog 并构建 ConsumeQueue 索引 */
        long putMessage(Message msg) {
            int queueId = Math.abs(msg.keys != null ? msg.keys.hashCode() : msg.body.hashCode()) % 4;
            Map<Integer, Long> topicOffsets = queueOffsets.computeIfAbsent(msg.topic, k -> new LinkedHashMap<>());
            long qOffset = topicOffsets.getOrDefault(queueId, 0L);

            int bodyLen = msg.body.length;
            CommitLogEntry entry = new CommitLogEntry(
                    commitLogOffset, bodyLen, msg.topic, queueId, (int) qOffset, msg.tags, msg.body);
            commitLog.add(entry);

            // 构建 ConsumeQueue 索引：hash(tags) 作为 tagCode
            long tagCode = msg.tags != null ? msg.tags.hashCode() : 0;
            consumeQueues
                    .computeIfAbsent(msg.topic, k -> new LinkedHashMap<>())
                    .computeIfAbsent(queueId, k -> new ArrayList<>())
                    .add(ConsumeQueueEntry.of(commitLogOffset, bodyLen, tagCode));

            topicOffsets.put(queueId, qOffset + 1);
            commitLogOffset += bodyLen;
            return commitLogOffset;
        }

        /** 从 ConsumeQueue 定位 CommitLog 读取消息 */
        List<MessageExt> getMessage(String topic, int queueId, long fromOffset, int maxNum) {
            Map<Integer, List<ConsumeQueueEntry>> topicCQ = consumeQueues.get(topic);
            if (topicCQ == null) return Collections.emptyList();

            List<ConsumeQueueEntry> cqList = topicCQ.get(queueId);
            if (cqList == null || fromOffset >= cqList.size()) return Collections.emptyList();

            List<MessageExt> result = new ArrayList<>();
            int end = (int) Math.min(fromOffset + maxNum, cqList.size());
            for (int i = (int) fromOffset; i < end; i++) {
                ConsumeQueueEntry cqEntry = cqList.get(i);
                // 通过 CommitLog 偏移量定位物理消息
                for (CommitLogEntry clog : commitLog) {
                    if (clog.offset == cqEntry.commitLogOffset) {
                        result.add(new MessageExt(i, cqEntry.commitLogOffset,
                                clog.topic, clog.tags, clog.body));
                        break;
                    }
                }
            }
            return result;
        }
    }

    /* ======================== Producer 模拟 ======================== */

    @SuppressWarnings("unused")
    record Producer(String group, SimpleNameServer namesrv) {
        /** 发送消息：先查 NameServer 路由，再选队列发送 */
        void send(Message msg, SimpleBroker broker) {
            List<QueueData> routes = namesrv.getRouteInfo(msg.topic);
            if (routes.isEmpty()) {
                System.out.printf("  [Producer] 发送失败: Topic %s 无路由%n", msg.topic);
                return;
            }
            long offset = broker.putMessage(msg);
            System.out.printf("  [Producer] 发送消息: topic=%s, tags=%s, CommitLog offset=%d%n",
                    msg.topic, msg.tags, offset);
        }
    }

    /* ======================== Consumer 模拟 ======================== */

    /** 消费者：Pull 模式拉取消息 */
    record Consumer(String group, SimpleNameServer namesrv) {
        List<MessageExt> pull(String topic, SimpleBroker broker, int queueId, long offset, int maxNum) {
            List<MessageExt> msgs = broker.getMessage(topic, queueId, offset, maxNum);
            System.out.printf("  [Consumer] 拉取: topic=%s, queueId=%d, offset=%d, count=%d%n",
                    topic, queueId, offset, msgs.size());
            return msgs;
        }
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== RocketMQ 核心架构模拟 ==========\n");

        // 1. 启动 NameServer
        System.out.println("--- 1. NameServer 启动 & Broker 注册 ---");
        SimpleNameServer namesrv = new SimpleNameServer();
        namesrv.registerBroker("DefaultCluster", "broker-a", "192.168.1.10:10911", "192.168.1.11:10911");
        namesrv.registerTopic("OrderTopic", QueueData.of("broker-a"));
        namesrv.registerTopic("PayTopic", QueueData.of("broker-a"));

        // 2. 启动 Broker
        System.out.println("\n--- 2. Broker 启动 ---");
        SimpleBroker broker = new SimpleBroker("broker-a", "DefaultCluster");
        System.out.printf("  CommitLog 全局偏移量起始: %d%n", broker.commitLogOffset);

        // 3. Producer 发送消息
        System.out.println("\n--- 3. Producer 发送消息 ---");
        Producer producer = new Producer("order-producer-group", namesrv);
        for (int i = 1; i <= 5; i++) {
            producer.send(Message.of("OrderTopic", "TagA",
                    "{\"orderId\":" + i + ",\"amount\":" + (i * 100) + "}"), broker);
        }

        // 4. 查看 CommitLog 存储
        System.out.println("\n--- 4. CommitLog 物理存储 ---");
        for (CommitLogEntry entry : broker.commitLog) {
            int byteLen = new String(entry.body, StandardCharsets.UTF_8).length();
            System.out.printf("  offset=%-4d size=%-4d topic=%s queueId=%d queueOffset=%d body=%s%n",
                    entry.offset, byteLen, entry.topic, entry.queueId, entry.queueOffset,
                    new String(entry.body, StandardCharsets.UTF_8));
        }

        // 5. 查看 ConsumeQueue 逻辑索引
        System.out.println("\n--- 5. ConsumeQueue 逻辑索引（每条 20 字节） ---");
        broker.consumeQueues.forEach((topic, queueMap) -> {
            System.out.printf("  Topic: %s%n", topic);
            queueMap.forEach((queueId, cqList) -> {
                System.out.printf("    Queue %d (共 %d 条):%n", queueId, cqList.size());
                for (ConsumeQueueEntry cqe : cqList) {
                    System.out.printf("      -> CommitLogOffset=%-5d size=%d tagCode=0x%08X%n",
                            cqe.commitLogOffset, cqe.size, cqe.tagCode);
                }
            });
        });

        // 6. Consumer 拉取消费
        System.out.println("\n--- 6. Consumer 拉取消费 ---");
        Consumer consumer = new Consumer("order-consumer-group", namesrv);
        for (int queueId = 0; queueId < 4; queueId++) {
            List<MessageExt> msgs = consumer.pull("OrderTopic", broker, queueId, 0, 5);
            for (MessageExt msg : msgs) {
                System.out.printf("   消费: queueOffset=%d, commitLogOffset=%d, body=%s%n",
                        msg.queueOffset, msg.commitLogOffset,
                        new String(msg.body, StandardCharsets.UTF_8));
            }
        }

        // 7. 核心概念总结
        System.out.println("\n--- 7. 核心概念总结 ---");
        System.out.println("""
                NameServer: 无状态路由中心, 类似 DNS, Broker 主动上报 Topic 路由
                  - Topic → Broker 映射, 生产者消费者启动时拉取
                  - 心跳检测: Broker 每 30s 上报, NameServer 120s 未收到则剔除
                CommitLog: 所有消息物理文件, 顺序追加写 (1GB/文件)
                  - 类似 WAL(Write-Ahead Log), 保证磁盘顺序写性能
                  - 每个文件 = 起始偏移量 (00000000000000000000)
                ConsumeQueue: 逻辑队列索引, 每条 20 字节
                  - commitLogOffset(8) + size(4) + tagCode(8)
                  - 消费者先查 CQ 索引 → 再定位 CommitLog 读消息体
                Master-Slave:
                  - SYNC_MASTER 同步复制: Slave 确认后返回成功
                  - ASYNC_MASTER 异步复制: Master 写成功即返回
                  - SYNC_FLUSH 同步刷盘 vs ASYNC_FLUSH 异步刷盘
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}