package base.mq;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 消息顺序性：全局有序 vs 分区有序 + 重平衡问题
 *
 * <p>分区有序：同一业务 ID → 同一 MessageQueue。
 * 全局有序：单 Queue + 禁用自动负载均衡。
 * 重平衡时顺序中断场景演示。
 *
 * <p>顺序消息核心原理：
 * <pre>
 * 分区有序 (PartitionOrder):
 *   - 生产者: MessageQueueSelector → 相同业务 Key 发到同一 Queue
 *   - Broker: 同一 Queue 内消息 FIFO
 *   - 消费者: 同一 Queue 单线程顺序消费
 *
 * 全局有序 (GlobalOrder):
 *   - 生产者: 所有消息发到同一 Queue (Topic 仅 1 个队列)
 *   - 消费者: 单线程消费, 禁用自动负载均衡
 * </pre>
 *
 * <p>顺序中断场景：
 * <ol>
 *   <li>重平衡: Consumer 宕机 → Queue 重新分配 → 新 Consumer 从何处开始消费?</li>
 *   <li>发送端异常: 同步发送失败 → 重试 → 后续消息先到达</li>
 *   <li>SUSPEND_CURRENT_QUEUE_A_MOMENT: 消费失败挂起 → 兄弟 Queue 继续消费 → 整体乱序</li>
 * </ol>
 *
 * @author study-tuling
 */
public class MessageOrderDemo {

    /* ======================== 数据模型 ======================== */

    /** 订单（业务实体） */
    record Order(long orderId, long userId, String operation, int sequence) {
        @Override
        public String toString() {
            return String.format("Order{id=%d, userId=%d, op='%s', seq=%d}", orderId, userId, operation, sequence);
        }
    }

    /** 消息队列 */
    static class MessageQueue {
        final String topic;
        final int queueId;
        final Queue<Order> messages = new LinkedList<>();
        final ReentrantLock lock = new ReentrantLock();

        MessageQueue(String topic, int queueId) {
            this.topic = topic;
            this.queueId = queueId;
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

    /** Broker 模拟 */
    static class OrderBroker {
        final String name;
        final Map<String, List<MessageQueue>> topicQueues = new LinkedHashMap<>();

        OrderBroker(String name) {
            this.name = name;
        }

        /** 创建 Topic 的队列 */
        void createTopic(String topic, int queueNum) {
            List<MessageQueue> queues = new ArrayList<>();
            for (int i = 0; i < queueNum; i++) {
                queues.add(new MessageQueue(topic, i));
            }
            topicQueues.put(topic, queues);
            System.out.printf("  [Broker] 创建 Topic: %s, 队列数: %d%n", topic, queueNum);
        }

        /** 获取所有队列 */
        List<MessageQueue> getQueues(String topic) {
            return topicQueues.getOrDefault(topic, Collections.emptyList());
        }
    }

    /* ======================== Producer 模拟 ======================== */

    static class OrderProducer {
        final String group;

        OrderProducer(String group) {
            this.group = group;
        }

        /** 分区有序发送：相同 userId → 同一 Queue */
        void sendPartitionOrder(OrderBroker broker, String topic, Order order) {
            List<MessageQueue> queues = broker.getQueues(topic);
            // 关键: 根据 userId 选择固定队列 (MessageQueueSelector)
            int queueId = (int) (Math.abs(order.userId) % queues.size());
            MessageQueue queue = queues.get(queueId);
            queue.put(order);
            System.out.printf("  [Producer] 分区有序: %s -> Queue-%d (userId=%d)%n", order, queueId, order.userId);
        }

        /** 全局有序发送：全部发到 Queue-0 */
        void sendGlobalOrder(OrderBroker broker, String topic, Order order) {
            List<MessageQueue> queues = broker.getQueues(topic);
            MessageQueue queue = queues.get(0); // 固定 Queue-0
            queue.put(order);
            System.out.printf("  [Producer] 全局有序: %s -> Queue-0%n", order);
        }
    }

    /* ======================== Consumer 模拟 ======================== */

    static class OrderConsumer {
        final String name;
        /** 记录消费序列号，检测乱序 */
        final Map<Long/* userId */, Integer> lastSeqMap = new ConcurrentHashMap<>();
        boolean hasDisorder = false;

        OrderConsumer(String name) {
            this.name = name;
        }

        /** 顺序消费 Queue */
        void consumeOrderly(MessageQueue queue) {
            queue.lock.lock();
            try {
                while (!queue.messages.isEmpty()) {
                    Order order = queue.poll();
                    System.out.printf("  [Consumer-%s] 消费: %s%n", name, order);
                    // 检测乱序：同一 userId 的 seq 必须递增
                    Integer lastSeq = lastSeqMap.get(order.userId);
                    if (lastSeq != null && order.sequence <= lastSeq) {
                        System.out.printf("  >>> 乱序检测! userId=%d, lastSeq=%d, current=%d <<<%n",
                                order.userId, lastSeq, order.sequence);
                        hasDisorder = true;
                    }
                    lastSeqMap.put(order.userId, order.sequence);
                }
            } finally {
                queue.lock.unlock();
            }
        }
    }

    /* ======================== 重平衡模拟 ======================== */

    /** 模拟 Consumer 宕机 → Queue 重新分配 → 顺序中断 */
    static void rebalanceDisorderDemo(OrderBroker broker, String topic, int queueCount) {
        System.out.println("\n--- 重平衡场景: Consumer 宕机 → Queue 重新分配 ---");

        // 两个 Consumer
        OrderConsumer consumer1 = new OrderConsumer("C1");
        OrderConsumer consumer2 = new OrderConsumer("C2");

        // 分配: C1 负责 Queue-0, Queue-1; C2 负责 Queue-2, Queue-3
        System.out.println("  初始分配: C1→Q0,Q1 | C2→Q2,Q3");

        // C1 消费 Q0 中部分消息后宕机
        MessageQueue q0 = broker.getQueues(topic).get(0);
        Order c1Msg = q0.poll();
        System.out.printf("  C1 消费 %s 后宕机...%n", c1Msg);
        System.out.println("  >>> C1 宕机! 触发重平衡 <<<");

        // 重平衡后: C2 接管 Q0, Q1, Q2, Q3
        System.out.println("  重平衡后: C2→Q0,Q1,Q2,Q3");
        // C2 从 Q0 继续消费 → 但不知道之前 C1 的 offset → 可能重复或丢失
        Order c2Msg = q0.poll();
        System.out.printf("  C2 接管 Q0, 消费: %s%n", c2Msg);
        System.out.println("  风险: C2 不知道 C1 的消费进度, 需要从存储的 offset 恢复\n");
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== 消息顺序性模拟 ==========\n");

        // 1. 分区有序
        System.out.println("--- 1. 分区有序 (Partition Ordered) ---");
        System.out.println("原理: 相同业务 Key → 同一 Queue → Queue 内 FIFO\n");
        OrderBroker broker = new OrderBroker("order-broker");
        broker.createTopic("OrderTopic", 4);
        OrderProducer producer = new OrderProducer("order-group");

        // 模拟用户下单流程: userId=1001 创建→支付→发货, 必须有序
        long userId1 = 1001L;
        long userId2 = 2002L;
        producer.sendPartitionOrder(broker, "OrderTopic",
                new Order(1, userId1, "CREATE", 1));
        producer.sendPartitionOrder(broker, "OrderTopic",
                new Order(1, userId2, "CREATE", 1));
        producer.sendPartitionOrder(broker, "OrderTopic",
                new Order(1, userId1, "PAY", 2));
        producer.sendPartitionOrder(broker, "OrderTopic",
                new Order(1, userId2, "PAY", 2));
        producer.sendPartitionOrder(broker, "OrderTopic",
                new Order(1, userId1, "SHIP", 3));

        // 消费分区有序消息
        System.out.println("\n  消费结果 (分区有序): ");
        List<MessageQueue> queues = broker.getQueues("OrderTopic");
        for (MessageQueue q : queues) {
            if (q.size() > 0) {
                System.out.printf("  Queue-%d (共 %d 条):%n", q.queueId, q.size());
                q.lock.lock();
                try {
                    while (!q.messages.isEmpty()) {
                        Order order = q.poll();
                        System.out.printf("    -> %s%n", order);
                    }
                } finally {
                    q.lock.unlock();
                }
            }
        }

        // 2. 全局有序
        System.out.println("\n--- 2. 全局有序 (Global Ordered) ---");
        System.out.println("原理: 单 Queue + 禁用自动负载均衡 + 单线程消费\n");
        OrderBroker globalBroker = new OrderBroker("global-broker");
        globalBroker.createTopic("GlobalOrderTopic", 1); // 仅 1 个队列!
        OrderProducer globalProducer = new OrderProducer("global-order-group");

        for (int i = 1; i <= 5; i++) {
            globalProducer.sendGlobalOrder(globalBroker, "GlobalOrderTopic",
                    new Order(i, 1, "STEP-" + i, i));
        }

        // 消费全局有序
        System.out.println("\n  消费结果 (全局有序): ");
        MessageQueue globalQ = globalBroker.getQueues("GlobalOrderTopic").get(0);
        OrderConsumer globalConsumer = new OrderConsumer("GlobalC");
        globalQ.lock.lock();
        try {
            while (!globalQ.messages.isEmpty()) {
                System.out.printf("    -> %s%n", globalQ.poll());
            }
        } finally {
            globalQ.lock.unlock();
        }

        // 3. 重平衡场景
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
                分区有序 (99% 场景):
                  - MessageQueueSelector: (queues, msg, arg) -> Math.abs(arg.hashCode()) % queues.size()
                  - 同一业务 ID 路由到同一 Queue, 保证局部有序
                  - Consumer 端: ConsumeOrderly → 获取 Queue 锁, 单线程消费
                全局有序 (特殊场景):
                  - Topic 仅 1 个 WriteQueue, 或每次只选 Queue-0
                  - 性能瓶颈: 单点写入/消费
                顺序中断风险:
                  - 重平衡: Consumer 下线 → Queue 重新分配 → 新 Consumer 进度不连续
                  - 发送端: 同步发送失败 + 重试 → 后续消息先到达
                  - 消费端: 部分失败 → SUSPEND_CURRENT_QUEUE_A_MOMENT
                解决方案:
                  - 锁定队列: 重平衡期间暂停消费 (不足: 有延迟)
                  - 业务补偿: 允许短暂乱序, 消费端按 seq 排序或丢弃过期消息
                """);

        System.out.println("========== 演示完毕 ==========");
    }
}