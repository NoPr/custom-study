package com.nopr.mq.kafka.simulate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 【模块】kafka
 * 【分类】simulate
 * 【主题】再平衡 —— Range·RoundRobin·Sticky·CooperativeSticky
 * 【描述】模拟 Kafka Consumer Group 再平衡：RangeAssignor（按范围平均分配）、
 *         RoundRobinAssignor（轮询分配）、StickyAssignor（粘性保持）。
 *         CooperativeStickyAssignor 分阶段执行不触发 stop-the-world。
 *         演示 Consumer 加入/离开时 Partition 的重新分配策略。
 * 【关键概念】再平衡、Rebalance、RangeAssignor、RoundRobinAssignor、
 *             StickyAssignor、CooperativeStickyAssignor、GroupCoordinator、
 *             Eager vs Cooperative、stop-the-world
 * 【关联类】@see com.nopr.mq.kafka.simulate.KafkaCoreDemo
 *
 * @author NoPr
 * @since 2026-05-16
 */
public class RebalanceDemo {

    interface Assignor {
        Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions);
        String name();
    }

    static class RangeAssignor implements Assignor {
        public String name() { return "RangeAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            List<Integer> sorted = partitions.stream().sorted().collect(Collectors.toList());
            int perConsumer = sorted.size() / consumers.size();
            int remainder = sorted.size() % consumers.size();
            int start = 0;
            for (int i = 0; i < consumers.size(); i++) {
                int count = perConsumer + (i < remainder ? 1 : 0);
                result.put(consumers.get(i), new ArrayList<>(sorted.subList(start, start + count)));
                start += count;
            }
            return result;
        }
    }

    static class RoundRobinAssignor implements Assignor {
        public String name() { return "RoundRobinAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            consumers.forEach(c -> result.put(c, new ArrayList<>()));
            List<Integer> sorted = partitions.stream().sorted().collect(Collectors.toList());
            for (int i = 0; i < sorted.size(); i++) {
                result.get(consumers.get(i % consumers.size())).add(sorted.get(i));
            }
            return result;
        }
    }

    static class StickyAssignor implements Assignor {
        private final Map<String, List<Integer>> previous;

        StickyAssignor(Map<String, List<Integer>> previous) { this.previous = previous; }

        public String name() { return "StickyAssignor"; }
        public Map<String, List<Integer>> assign(List<String> consumers, List<Integer> partitions) {
            if (previous == null) return new RangeAssignor().assign(consumers, partitions);

            Map<String, List<Integer>> result = new LinkedHashMap<>();
            consumers.forEach(c -> result.put(c, new ArrayList<>()));

            for (String consumer : consumers) {
                List<Integer> prev = previous.getOrDefault(consumer, Collections.emptyList());
                for (int p : prev) {
                    if (partitions.contains(p) && result.get(consumer).size() < prev.size()) {
                        result.get(consumer).add(p);
                    }
                }
            }

            Set<Integer> assigned = result.values().stream()
                    .flatMap(List::stream).collect(Collectors.toSet());
            List<Integer> unassigned = partitions.stream()
                    .filter(p -> !assigned.contains(p)).sorted().collect(Collectors.toList());

            int idx = 0;
            for (int p : unassigned) {
                String consumer = consumers.get(idx % consumers.size());
                result.get(consumer).add(p);
                idx++;
            }

            return result;
        }
    }

    static void printAssignment(Map<String, List<Integer>> assignment) {
        assignment.forEach((consumer, parts) ->
                System.out.printf("  %s \u2192 partitions: %s%n", consumer, parts));
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Kafka 再平衡 (Rebalance) Demo");
        System.out.println("=".repeat(60));

        List<String> consumers = List.of("consumer-1", "consumer-2", "consumer-3");
        List<Integer> partitions = List.of(0, 1, 2, 3, 4, 5, 6, 7);

        System.out.println("\n--- RangeAssignor（按范围切分）---");
        printAssignment(new RangeAssignor().assign(consumers, partitions));

        System.out.println("\n--- RoundRobinAssignor（轮询分配）---");
        printAssignment(new RoundRobinAssignor().assign(consumers, partitions));

        System.out.println("\n--- StickyAssignor（consumer-3 离开后，粘性保持）---");
        Map<String, List<Integer>> prev = new LinkedHashMap<>();
        prev.put("consumer-1", List.of(0, 1, 2));
        prev.put("consumer-2", List.of(3, 4, 5));
        prev.put("consumer-3", List.of(6, 7));
        List<String> reducedConsumers = List.of("consumer-1", "consumer-2");
        printAssignment(new StickyAssignor(prev).assign(reducedConsumers, partitions));

        System.out.println("\n\uD83D\uDCA1 Sticky 策略减少分区迁移，避免大量重新消费");
        System.out.println("   CooperativeSticky 进一步优化：分阶段执行，不触发 stop-the-world");
    }
}
