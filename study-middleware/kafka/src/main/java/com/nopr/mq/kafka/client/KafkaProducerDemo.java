package com.nopr.mq.kafka.client;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * 【模块】kafka
 * 【分类】client
 * 【主题】Kafka Producer —— 异步回调·Key 分区·acks 配置
 * 【描述】使用 Kafka 官方 Producer 连接 Broker，演示：
 *         异步发送 + Callback 回调、Key 哈希分区路由、
 *         acks 配置（all=最强可靠性）。
 *         先运行此 Demo 发送消息，再运行 KafkaConsumerDemo 消费。
 * 【运行要求】Kafka Broker 已启动（默认 192.168.4.52:9092）
 * 【关键概念】KafkaProducer、ProducerRecord、Callback、acks=all、
 *             key 分区、RecordMetadata、异步发送
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class KafkaProducerDemo {

    private static final String BOOTSTRAP_SERVERS = "192.168.4.52:9092";
    private static final String TOPIC = "study-kafka-topic";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            System.out.println("Kafka Producer 启动成功, Broker: " + BOOTSTRAP_SERVERS);

            asyncSendWithCallback(producer);
            syncSend(producer);

            producer.flush();
        }
        System.out.println("Kafka Producer 已关闭");
    }

    static void asyncSendWithCallback(KafkaProducer<String, String> producer) {
        System.out.println("\n--- 异步发送 + Callback ---");
        for (int i = 1; i <= 5; i++) {
            String key = "order-" + (i % 3);
            String value = String.format("{\"orderId\":%d,\"amount\":%.2f}", i, Math.random() * 100);
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, value);

            producer.send(record, new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    if (exception == null) {
                        System.out.printf("  [成功] topic=%s partition=%d offset=%d key=%s%n",
                                metadata.topic(), metadata.partition(),
                                metadata.offset(), key);
                    } else {
                        System.err.printf("  [失败] key=%s error=%s%n", key, exception.getMessage());
                    }
                }
            });
        }
    }

    static void syncSend(KafkaProducer<String, String> producer)
            throws ExecutionException, InterruptedException {
        System.out.println("\n--- 同步发送（等待结果）---");
        for (int i = 6; i <= 8; i++) {
            String key = "sync-" + i;
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, key, "同步消息-" + i);

            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get();
            System.out.printf("  [同步] topic=%s partition=%d offset=%d key=%s%n",
                    metadata.topic(), metadata.partition(), metadata.offset(), key);
        }
    }
}
