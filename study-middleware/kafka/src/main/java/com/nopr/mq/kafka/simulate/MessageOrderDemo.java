package com.nopr.mq.kafka.simulate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】消息顺序性 —— 分区有序·全局有序·Rebalance 风险
 * 【描述】模拟 Kafka 消息顺序性：Partitioner 按 Key 哈希选固定分区实现分区有序；
 *         单 Partition 实现全局有序；演示 Rebalance 导致顺序中断的风险。
 * 【关键概念】顺序消息、Partitioner、分区有序、全局有序、Rebalance、顺序中断、
 *             粘性分区
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.MessageOrderDemo
 *
 * @author study-tuling
 * @since 2026-05-16
 */
public class MessageOrderDemo {

    record Order(long orderId, long userId, String operation, int sequence) {
        @Override
        public String toString() {
            return String.format("Order{id=%d, userId=%d, op='%s', seq=%d}", orderId, userId, operation, sequence);
        }
    }

    static class Partition {
        final String topic;
        final int partitionId;
        final Queue<Order> messages = new LinkedList<>();
        final ReentrantLock lock = new ReentrantLock();

        Partition(String topic, int partitionId) {
            this.topic = topic;
            this.partitionId = partitionId;
        }

        void put(Order order) {
            lock.lock();
            try {
                messages.offer(order);
            } finally {
                lock.unlock();
            }
        }

        Order poll() {
            lock.lock();
            try {
                return messages.poll();
            } finally {
                lock.unlock();
            }
        }

        int size() {
            return messages.size();
        }
    }

    static class OrderBroker {
        final String name;
        final Map<String, List<Partition>> topicPartitions = new LinkedHashMap<>();

        OrderBroker(String name) {
            this.name = name;
        }

        void createTopic(String topic, int partitionNum) {
            List<Partition> partitions = new ArrayList<>();
            for (int i = 0; i < partitionNum; i++) {
                partitions.add(new Partition(topic, i));
            }
            topicPartitions.put(topic, partitions);
            System.out.printf("  [Broker] 创建 Topic: %s, 分区数: %d%n", topic, partitionNum);
        }

        List<Partition> getPartitions(String topic) {
            return topicPartitions.getOrDefault(topic, Collections.emptyList());
        }
    }

    static class OrderProducer {
        final String group;

        OrderProducer(String group) {
            this.group = group;
        }

        /** 分区有序发送：相同 Key → 同一 Partition */
        void sendPartitionOrder(OrderBroker broker, String topic, Order order) {
            List<Partition> partitions = broker.getPartitions(topic);
            int partitionId = (int) (Math.abs(order.userId) % partitions.size());
            Partition partition = partitions.get(partitionId);
            partition.put(order);
            System.out.printf("  [Producer] 分区有序: %s -> Partition-%d (userId=%d)%n",
                    order, partitionId, order.userId);
        }

        /** 全局有序发送：全部发到 Partition-0 */
        void sendGlobalOrder(OrderBroker broker, String topic, Order order) {
            List<Partition> partitions = broker.getPartitions(topic);
            Partition partition = partitions.get(0);
            partition.put(order);
            System.out.printf("  [Producer] 全局有序: %s -> Partition-0%n", order);
        }
    }

    static class OrderConsumer {
        final String name;
        final Map<Long, Integer> lastSeqMap = new ConcurrentHashMap<>();
        boolean hasDisorder = false;

        OrderConsumer(String name) {
            this.name = name;
        }

        void consumeOrderly(Partition partition) {
            partition.lock.lock();
            try {
                while (!partition.messages.isEmpty()) {
                    Order order = partition.poll();
                    System.out.printf("  [Consumer-%s] 消费: %s%n", name, order);
                    Integer lastSeq = lastSeqMap.get(order.userId);
                    if (lastSeq != null && order.sequence <= lastSeq) {
                        System.out.printf("  >>> 乱序检测! userId=%d, lastSeq=%d, current=%d <<<%n",
                                order.userId, lastSeq, order.sequence);
                        hasDisorder = true;
                    }
                    lastSeqMap.put(order.userId, order.sequence);
                }
            } finally {
                partition.lock.unlock();
            }
        }
    }

    static void rebalanceDisorderDemo(OrderBroker broker, String topic, int partitionCount) {
        System.out.println("\n--- Rebalance 场景: Consumer 宕机 → Partition 重新分配 ---");

        OrderConsumer consumer1 = new OrderConsumer("C1");
        OrderConsumer consumer2 = new OrderConsumer("C2");

        System.out.println("  初始分配: C1→P0,P1 | C2→P2,P3");

        Partition p0 = broker.getPartitions(topic).get(0);
        Order c1Msg = p0.poll();
        System.out.printf("  C1 消费 %s 后宕机...%n", c1Msg);
        System.out.println("  >>> C1 宕机! 触发 Rebalance <<<");

        System.out.println("  重平衡后: C2→P0,P1,P2,P3");
        Order c2Msg = p0.poll();
        System.out.printf("  C2 接管 P0, 消费: %s%n", c2Msg);
        System.out.println("  风险: C2 不知道 C1 的消费 offset, 需要从 commit offset 恢复\n");
    }

    public static void main(String[] args) {
        System.out.println("========== 消息顺序性模拟 (Kafka) ==========\n");

        // 1. 分区有序
        System.out.println("--- 1. 分区有序 (Partitioner Key Hash) ---");
        System.out.println("原理: 相同 Key → 同一 Partition → Partition 内 FIFO\n");
        OrderBroker broker = new OrderBroker("order-broker");
        broker.createTopic("OrderTopic", 4);
        OrderProducer producer = new OrderProducer("order-group");

        long userId1 = 1001L;
        long userId2 = 2002L;
        producer.sendPartitionOrder(broker, "OrderTopic", new Order(1, userId1, "CREATE", 1));
        producer.sendPartitionOrder(broker, "OrderTopic", new Order(1, userId2, "CREATE", 1));
        producer.sendPartitionOrder(broker, "OrderTopic", new Order(1, userId1, "PAY", 2));
        producer.sendPartitionOrder(broker, "OrderTopic", new Order(1, userId2, "PAY", 2));
        producer.sendPartitionOrder(broker, "OrderTopic", new Order(1, userId1, "SHIP", 3));

        System.out.println("\n  消费结果 (分区有序): ");
        List<Partition> partitions = broker.getPartitions("OrderTopic");
        for (Partition p : partitions) {
            if (p.size() > 0) {
                System.out.printf("  Partition-%d (共 %d 条):%n", p.partitionId, p.size());
                p.lock.lock();
                try {
                    while (!p.messages.isEmpty()) {
                        Order order = p.poll();
                        System.out.printf("    -> %s%n", order);
                    }
                } finally {
                    p.lock.unlock();
                }
            }
        }

        // 2. 全局有序
        System.out.println("\n--- 2. 全局有序 (单 Partition) ---");
        System.out.println("原理: 单 Partition + 单线程消费\n");
        OrderBroker globalBroker = new OrderBroker("global-broker");
        globalBroker.createTopic("GlobalOrderTopic", 1);
        OrderProducer globalProducer = new OrderProducer("global-order-group");

        for (int i = 1; i <= 5; i++) {
            globalProducer.sendGlobalOrder(globalBroker, "GlobalOrderTopic",
                    new Order(i, 1, "STEP-" + i, i));
        }

        System.out.println("\n  消费结果 (全局有序): ");
        Partition globalP = globalBroker.getPartitions("GlobalOrderTopic").get(0);
        globalP.lock.lock();
        try {
            while (!globalP.messages.isEmpty()) {
                System.out.printf("    -> %s%n", globalP.poll());
            }
        } finally {
            globalP.lock.unlock();
        }

        // 3. Rebalance 场景
        OrderBroker rebalBroker = new OrderBroker("rebal-broker");
        rebalBroker.createTopic("RebalTopic", 4);
        OrderProducer rebalProducer = new OrderProducer("rebal-group");
        for (int i = 1; i <= 8; i++) {
            rebalProducer.sendPartitionOrder(rebalBroker, "RebalTopic",
                    new Order(i, 1000 + (i % 3), "OP-" + i, i));
        }
        rebalanceDisorderDemo(rebalBroker, "RebalTopic", 4);

        // 总结
        System.out.println("--- 3. 顺序消息总结 ---");
        System.out.println("""
                分区有序 (最常用):
                  - DefaultPartitioner: hash(key) % numPartitions
                  - StickyPartitioner: 粘性分区减少延迟
                  - 同一 Key 路由到同一 Partition, 保证局部有序
                全局有序 (特殊场景):
                  - Topic 仅 1 个 Partition
                  - 性能瓶颈: 单点写入/消费
                顺序中断风险:
                  - Rebalance: Consumer 下线 → Partition 重新分配 → 进度不连续
                  - 发送端: 重试 + max.in.flight.requests > 1 → 乱序
                  - enable.idempotence=true 时会限制 max.in.flight.requests=5
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}