package com.nopr.mq.rocketmq.simulate;

import java.util.*;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】延迟消息 —— 18 个延迟级别·定时投递·ScheduleMessageService
 * 【描述】模拟 RocketMQ 延迟消息机制：Producer 设置 delayLevel（1~18）
 *         对应 1s~2h 延迟时间。Broker 端 ScheduleMessageService 定时扫描
 *         SCHEDULE_TOPIC，到期后将消息投递到原始 Topic。
 * 【关键概念】延迟消息、delayLevel、ScheduleMessageService、SCHEDULE_TOPIC_XXXX、
 *             messageDelayLevel、定时调度、延迟队列
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class DelayMessageDemo {

    static final int[] DELAY_LEVELS = {
            0,  // level 0 = 不延迟
            1_000,        // level 1: 1s
            5_000,        // level 2: 5s
            10_000,       // level 3: 10s
            30_000,       // level 4: 30s
            60_000,       // level 5: 1m
            120_000,      // level 6: 2m
            180_000,      // level 7: 3m
            240_000,      // level 8: 4m
            300_000,      // level 9: 5m
            360_000,      // level 10: 6m
            420_000,      // level 11: 7m
            480_000,      // level 12: 8m
            540_000,      // level 13: 9m
            600_000,      // level 14: 10m
            1_200_000,    // level 15: 20m
            1_800_000,    // level 16: 30m
            3_600_000,    // level 17: 1h
            7_200_000,    // level 18: 2h
    };

    record Message(String msgId, String topic, String body, int delayLevel, long createTime) {}

    static class DelayBroker {
        private final Map<String, Queue<Message>> normalQueues = new LinkedHashMap<>();
        private final TreeMap<Long, List<Message>> scheduleMap = new TreeMap<>();
        private final List<String> eventLog = new ArrayList<>();

        void putMessage(Message msg) {
            if (msg.delayLevel() > 0) {
                long deliveryTime = System.currentTimeMillis() + DELAY_LEVELS[msg.delayLevel()];
                scheduleMap.computeIfAbsent(deliveryTime, k -> new ArrayList<>()).add(msg);
                eventLog.add(String.format("[延迟消息] topic=%s msgId=%s level=%d delay=%ds 预计投递=%s",
                        msg.topic(), msg.msgId(), msg.delayLevel(),
                        DELAY_LEVELS[msg.delayLevel()] / 1000,
                        new Date(deliveryTime)));
            } else {
                normalQueues.computeIfAbsent(msg.topic(), k -> new LinkedList<>()).offer(msg);
                eventLog.add(String.format("[即时消息] topic=%s msgId=%s", msg.topic(), msg.msgId()));
            }
        }

        void tick() {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Long, List<Message>>> it = scheduleMap.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<Long, List<Message>> entry = it.next();
                if (entry.getKey() > now) break;

                for (Message msg : entry.getValue()) {
                    normalQueues.computeIfAbsent(msg.topic(), k -> new LinkedList<>()).offer(msg);
                    eventLog.add(String.format("[到期投递] topic=%s msgId=%s body=%s 在 %s",
                            msg.topic(), msg.msgId(), msg.body(), new Date()));
                }
                it.remove();
            }
        }

        Message pullMessage(String topic) {
            Queue<Message> q = normalQueues.get(topic);
            if (q == null || q.isEmpty()) return null;
            return q.poll();
        }

        void printEventLog() {
            eventLog.forEach(System.out::println);
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RocketMQ 延迟消息 Demo");
        System.out.println("=".repeat(60));

        System.out.println("\n--- RocketMQ 18 个延迟级别 ---");
        System.out.printf("%-8s %-12s%n", "Level", "延迟时间");
        System.out.println("-".repeat(22));
        for (int i = 1; i < DELAY_LEVELS.length; i++) {
            System.out.printf("%-8d %-12s%n", i, formatDelay(DELAY_LEVELS[i]));
        }

        DelayBroker broker = new DelayBroker();

        System.out.println("\n--- 发送延迟消息 ---");
        broker.putMessage(new Message("MSG-D1", "order-topic", "30分钟未支付提醒", 14, System.currentTimeMillis()));
        broker.putMessage(new Message("MSG-D2", "sms-topic", "1小时后关怀短信", 17, System.currentTimeMillis()));
        broker.putMessage(new Message("MSG-N1", "order-topic", "下单成功通知", 0, System.currentTimeMillis()));

        System.out.println("\n[T=0s] 即时消息可直接消费，延迟消息等待中...");
        Message ready = broker.pullMessage("order-topic");
        if (ready != null) System.out.println("  消费到: " + ready.body());

        System.out.println("\n[T=600s] 模拟时间推进 10 分钟...");
        Message fakeDelivered = new Message("MSG-D1", "order-topic", "30分钟未支付提醒", 14,
                System.currentTimeMillis() - 600_000);
        broker.putMessage(fakeDelivered);
        broker.tick();
        ready = broker.pullMessage("order-topic");
        if (ready != null) System.out.println("  投递成功: " + ready.body());

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  事件日志");
        System.out.println("=".repeat(60));
        broker.printEventLog();

        System.out.println("\n\uD83D\uDCA1 RocketMQ 延迟消息不支持任意时间精度，仅支持 18 个预设级别");
        System.out.println("   Kafka 无原生延迟消息，需借助外部存储 + 定时扫描实现");
    }

    static String formatDelay(long ms) {
        if (ms < 60_000) return ms / 1000 + "s";
        if (ms < 3_600_000) return ms / 60_000 + "min";
        return ms / 3_600_000 + "h";
    }
}
