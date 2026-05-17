package com.nopr.mq.rocketmq.simulate;

import java.util.*;

/**
 * 【模块】rocketmq
 * 【分类】simulate
 * 【主题】消费重试 —— 阶梯式等待·retryTimes→delayLevel·RECONSUME_LATER
 * 【描述】模拟 RocketMQ 消费重试机制：Consumer 返回 RECONSUME_LATER 后，
 *         Broker 按 retryTimes 映射到 delayLevel（阶梯式递增等待），
 *         消息延迟后重新投递。演示指数退避重试策略。
 * 【关键概念】消费重试、RECONSUME_LATER、阶梯式等待、delayLevel 映射、
 *             指数退避、retryTimes、重试队列（%RETRY%）
 * 【关联类】@see com.nopr.mq.rocketmq.simulate.DeadLetterQueueDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class ConsumeRetryDemo {

    /**
     * retryTimes → delayLevel 映射（RocketMQ 默认策略）
     * retryTimes=0 → level=0（立即重试）
     * retryTimes=1 → level=3（10s）
     * retryTimes=2 → level=4（30s）
     * retryTimes=3 → level=5（1min）
     * ...
     * retryTimes>16 → DLQ
     */
    static final int[] RETRY_DELAY_LEVELS = {
            0,  // retryTimes=0: 立即
            3,  // retryTimes=1: 10s
            4,  // retryTimes=2: 30s
            5,  // retryTimes=3: 1min
            6,  // retryTimes=4: 2min
            7,  // retryTimes=5: 3min
            8,  // retryTimes=6: 4min
            9,  // retryTimes=7: 5min
            10, // retryTimes=8: 6min
            11, // retryTimes=9: 7min
            12, // retryTimes=10: 8min
            13, // retryTimes=11: 9min
            14, // retryTimes=12: 10min
            15, // retryTimes=13: 20min
            16, // retryTimes=14: 30min
            17, // retryTimes=15: 1h
            18, // retryTimes=16: 2h
    };

    static final int[] DELAY_LEVEL_MS = DelayMessageDemo.DELAY_LEVELS;

    record Message(String msgId, String body, int retryTimes) {}

    static class RetryBroker {
        private final Map<Integer, List<Message>> retryQueues = new LinkedHashMap<>();
        private final List<String> eventLog = new ArrayList<>();

        void putForRetry(Message msg) {
            int nextRetry = msg.retryTimes() + 1;
            int delayLevel = nextRetry < RETRY_DELAY_LEVELS.length
                    ? RETRY_DELAY_LEVELS[nextRetry] : 18;

            int delayMs = DELAY_LEVEL_MS[delayLevel];
            Message retryMsg = new Message(msg.msgId(), msg.body(), nextRetry);

            retryQueues.computeIfAbsent(delayLevel, k -> new ArrayList<>()).add(retryMsg);
            eventLog.add(String.format("[重试] msgId=%s retryTimes=%d delayLevel=%d 等待 %s",
                    msg.msgId(), nextRetry, delayLevel, formatDelay(delayMs)));
        }

        Optional<Message> tryDeliver(int currentDelayLevel) {
            List<Message> msgs = retryQueues.get(currentDelayLevel);
            if (msgs != null && !msgs.isEmpty()) {
                Message m = msgs.remove(0);
                eventLog.add(String.format("[重新投递] msgId=%s body=%s retryTimes=%d",
                        m.msgId(), m.body(), m.retryTimes()));
                return Optional.of(m);
            }
            return Optional.empty();
        }

        void printEventLog() {
            eventLog.forEach(System.out::println);
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RocketMQ 消费重试 (阶梯式等待) Demo");
        System.out.println("=".repeat(60));

        System.out.println("\n--- retryTimes → delayLevel 映射 ---");
        System.out.printf("%-12s %-14s %-12s%n", "retryTimes", "delayLevel", "等待时间");
        System.out.println("-".repeat(40));
        for (int i = 0; i < RETRY_DELAY_LEVELS.length && RETRY_DELAY_LEVELS[i] > 0; i++) {
            int level = RETRY_DELAY_LEVELS[i];
            System.out.printf("%-12d %-14d %-12s%n", i, level,
                    formatDelay(DELAY_LEVEL_MS[level]));
        }
        System.out.printf("%-12s %-14s %-12s%n", ">16", "-", "→ DLQ");

        RetryBroker broker = new RetryBroker();

        System.out.println("\n--- 模拟一条消息的完整重试链 ---");
        Message msg = new Message("MSG-R1", "支付回调", 0);

        for (int attempt = 0; attempt < 4; attempt++) {
            System.out.printf("%n第 %d 次消费失败 →%n", attempt + 1);
            broker.putForRetry(msg);
            msg = new Message(msg.msgId(), msg.body(), attempt + 1);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  事件日志");
        System.out.println("=".repeat(60));
        broker.printEventLog();

        System.out.println("\n\uD83D\uDCA1 RocketMQ 重试递增加速隔离故障 Consumer，避免雪崩");
        System.out.println("   Kafka 默认无重试，可配置 RetryableTopic 或手动实现");
    }

    static String formatDelay(long ms) {
        if (ms < 60_000) return ms / 1000 + "s";
        if (ms < 3_600_000) return ms / 60_000 + "min";
        return ms / 3_600_000 + "h";
    }
}
