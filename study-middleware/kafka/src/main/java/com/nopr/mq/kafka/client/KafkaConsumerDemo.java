package com.nopr.mq.kafka.client;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * 【模块】kafka
 * 【分类】client
 * 【主题】Kafka Consumer —— Consumer Group·Poll·Offset 管理
 * 【描述】使用 Kafka 官方 Consumer 订阅消费，演示：
 *         Consumer Group 订阅、Poll 循环拉取、auto.offset.reset、
 *         enable.auto.commit、max.poll.records 配置。
 *         先运行 KafkaProducerDemo 发送消息。
 * 【运行要求】Kafka Broker 已启动（默认 192.168.4.52:9092）
 * 【关键概念】KafkaConsumer、ConsumerGroup、poll、offset、
 *             auto.offset.reset(earliest/latest)、commit
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class KafkaConsumerDemo {

    private static final String BOOTSTRAP_SERVERS = "192.168.4.52:9092";
    private static final String TOPIC = "study-kafka-topic";
    private static final String GROUP_ID = "study-kafka-consumer-group";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));
            System.out.println("Kafka Consumer 启动成功, Broker: " + BOOTSTRAP_SERVERS);
            System.out.println("订阅: " + TOPIC + " (group: " + GROUP_ID + ")");
            System.out.println("等待消息... (Ctrl+C 退出)");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                consumer.wakeup();
                System.out.println("Kafka Consumer 已关闭");
            }));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("  [消费] topic=%s partition=%d offset=%d key=%s value=%s%n",
                            record.topic(), record.partition(), record.offset(),
                            record.key(), record.value());
                }
            }
        } catch (Exception e) {
            if (!(e.getCause() instanceof InterruptedException)) {
                System.err.println("Consumer 异常: " + e.getMessage());
            }
        }
    }
}
