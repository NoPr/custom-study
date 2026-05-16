package base.flink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Flink 窗口机制：TimeWindow(Tumbling/Sliding/Session) + CountWindow + 手写 WindowAssigner
 * + Trigger 触发条件(watermark到达+count) + 窗口延迟数据处理(SideOutput)。
 *
 * <p>核心考点：
 * <ul>
 *   <li><b>TumblingWindow</b>：固定大小、无重叠，窗口间隔=窗口大小</li>
 *   <li><b>SlidingWindow</b>：固定大小、有重叠，滑动步长小于窗口大小时数据重复计算</li>
 *   <li><b>SessionWindow</b>：动态大小，基于会话超时间隔(Gap)，Gap内无数据则窗口闭合</li>
 *   <li><b>CountWindow</b>：无时间概念，按元素数量触发计算</li>
 *   <li><b>WindowAssigner</b>：决定每条数据分配到哪个/哪些窗口</li>
 *   <li><b>Trigger</b>：决定窗口何时触发计算(watermark到达/元素数量/定时器)</li>
 *   <li><b>SideOutput</b>：旁路输出，延迟数据不丢弃而是分流到侧输出流</li>
 * </ul>
 *
 * @author study-tuling
 */
public class WindowDemo {

    /* ======================== 数据模型 ======================== */

    /** 模拟 Flink 事件 */
    record FlinkEvent(String key, long timestamp, int value) {
    }

    /** 窗口元数据 */
    record Window(long start, long end) implements Comparable<Window> {
        @Override
        public int compareTo(Window other) {
            return Long.compare(this.start, other.start);
        }
        @Override
        public String toString() {
            return String.format("[%d, %d)", start, end);
        }
    }

    /** 窗口聚合结果 */
    record WindowResult(Window window, String key, int sum, int count) {
    }

    /* ======================== WindowAssigner 分配器 ======================== */

    /**
     * WindowAssigner 接口：将事件分配到窗口集合。
     */
    @FunctionalInterface
    interface WindowAssigner {
        Collection<Window> assignWindows(FlinkEvent event);
    }

    /** 滚动窗口分配器 */
    static class TumblingWindowAssigner implements WindowAssigner {
        private final long windowSizeMs;

        TumblingWindowAssigner(long windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
        }

        @Override
        public Collection<Window> assignWindows(FlinkEvent event) {
            long start = (event.timestamp / windowSizeMs) * windowSizeMs;
            return List.of(new Window(start, start + windowSizeMs));
        }
    }

    /** 滑动窗口分配器 */
    static class SlidingWindowAssigner implements WindowAssigner {
        private final long windowSizeMs;
        private final long slideMs;

        SlidingWindowAssigner(long windowSizeMs, long slideMs) {
            this.windowSizeMs = windowSizeMs;
            this.slideMs = slideMs;
        }

        @Override
        public Collection<Window> assignWindows(FlinkEvent event) {
            long lastStart = (event.timestamp - windowSizeMs + slideMs) / slideMs * slideMs;
            if (lastStart < 0) lastStart = 0;
            long firstStart = (event.timestamp / slideMs) * slideMs;

            List<Window> windows = new ArrayList<>();
            for (long start = firstStart; start >= lastStart; start -= slideMs) {
                windows.add(new Window(start, start + windowSizeMs));
            }
            return windows;
        }
    }

    /** 会话窗口分配器：基于时间间隔(Gap)动态合并窗口 */
    record SessionWindowAssigner(long sessionGapMs) implements WindowAssigner {
        @Override
        public Collection<Window> assignWindows(FlinkEvent event) {
            // 会话窗口返回单元素窗口，后续由 Trigger 的合并逻辑处理
            return List.of(new Window(event.timestamp, event.timestamp + sessionGapMs));
        }
    }

    /** 计数窗口分配器：按元素数量触发，不产生 Window 对象 */
    record CountWindowAssigner(long maxCount) implements WindowAssigner {
        @Override
        public Collection<Window> assignWindows(FlinkEvent event) {
            return List.of(new Window(0, maxCount));
        }
    }

    /* ======================== Trigger 触发器 ======================== */

    /** 触发器结果：通知 Purge(清空) / Fire(触发计算) / Continue(继续等待) */
    enum TriggerResult {
        /** 触发计算并清空窗口 */
        FIRE_AND_PURGE,
        /** 仅触发计算，保留窗口状态 */
        FIRE,
        /** 继续等待 */
        CONTINUE
    }

    /**
     * Trigger 接口：决定窗口何时触发计算。
     * <p>生产环境中通常组合多种条件：Watermark到达窗口末尾 + 自定义Count触发。
     */
    interface Trigger {
        /**
         * 每个元素到达时调用
         * @return 触发结果
         */
        TriggerResult onElement(FlinkEvent event, Window window);

        /** Watermark 到达窗口 end 时调用 */
        TriggerResult onEventTime(long watermark, Window window);
    }

    /** 组合触发器：Watermark 到达 + 元素 Count 达到阈值 */
    record CompositeTrigger(long maxCount) implements Trigger {
        // 每个窗口的元素计数
        private static final Map<Window, Long> countMap = new ConcurrentHashMap<>();

        @Override
        public TriggerResult onElement(FlinkEvent event, Window window) {
            long current = countMap.merge(window, 1L, Long::sum);
            if (current >= maxCount) {
                countMap.remove(window);
                return TriggerResult.FIRE_AND_PURGE;
            }
            return TriggerResult.CONTINUE;
        }

        @Override
        public TriggerResult onEventTime(long watermark, Window window) {
            countMap.remove(window);
            return watermark >= window.end ? TriggerResult.FIRE_AND_PURGE : TriggerResult.CONTINUE;
        }
    }

    /* ======================== 侧输出 SideOutput ======================== */

    /**
     * 侧输出收集器：延迟数据不丢弃，分流到此。
     */
    static class SideOutputCollector {
        final List<WindowResult> lateData = Collections.synchronizedList(new ArrayList<>());

        void collect(WindowResult result) {
            lateData.add(result);
            System.out.printf("  [SideOutput] 延迟数据 -> %s key=%s sum=%d count=%d%n",
                    result.window(), result.key(), result.sum(), result.count());
        }
    }

    /* ======================== 简化版 WindowOperator ======================== */

    /**
     * 窗口算子：组合 Assigner + Trigger + AggregateFunction。
     * <p>每个 Key 独立维护窗口状态 Map。
     */
    static class WindowOperator {
        private final WindowAssigner assigner;
        private final Trigger trigger;
        // key -> window -> 元素列表
        private final Map<String, Map<Window, List<Integer>>> state = new ConcurrentHashMap<>();

        WindowOperator(WindowAssigner assigner, Trigger trigger) {
            this.assigner = assigner;
            this.trigger = trigger;
        }

        /** 处理事件，返回触发的窗口结果列表 */
        List<WindowResult> processElement(FlinkEvent event, long currentWatermark) {
            List<WindowResult> results = new ArrayList<>();
            Map<Window, List<Integer>> keyState = state.computeIfAbsent(
                    event.key, k -> new ConcurrentHashMap<>());

            Collection<Window> windows = assigner.assignWindows(event);

            for (Window window : windows) {
                List<Integer> values = keyState.computeIfAbsent(window, w -> new ArrayList<>());
                values.add(event.value);

                // 检查 Trigger 条件
                TriggerResult elementTrigger = trigger.onElement(event, window);
                if (elementTrigger == TriggerResult.FIRE_AND_PURGE
                        || elementTrigger == TriggerResult.FIRE) {
                    results.add(fireAndRemoveWindow(keyState, event.key, window));
                }
            }

            // 检查滚动窗口是否因 Watermark 触发
            List<Window> toFire = new ArrayList<>();
            for (Map.Entry<Window, List<Integer>> entry : keyState.entrySet()) {
                Window w = entry.getKey();
                TriggerResult rt = trigger.onEventTime(currentWatermark, w);
                if (rt == TriggerResult.FIRE_AND_PURGE || rt == TriggerResult.FIRE) {
                    toFire.add(w);
                }
            }
            for (Window w : toFire) {
                results.add(fireAndRemoveWindow(keyState, event.key, w));
            }

            return results;
        }

        private WindowResult fireAndRemoveWindow(Map<Window, List<Integer>> ks,
                                                  String key, Window window) {
            List<Integer> values = ks.remove(window);
            if (values == null || values.isEmpty()) {
                return null;
            }
            int sum = values.stream().mapToInt(Integer::intValue).sum();
            return new WindowResult(window, key, sum, values.size());
        }
    }

    /* ======================== RDD Lineage vs Checkpoint ======================== */

    /**
     * Lineage（血统）重算 vs Checkpoint 截断对比演示。
     */
    static void rdLineageVsCheckpointDemo() {
        System.out.println("--- Lineage 血统重算 vs Checkpoint 截断血统 ---");

        System.out.println("""
                Lineage（血统）：
                  RDD0 [1,2,3,4,5]
                    → map(x*2) → RDD1 [2,4,6,8,10]
                      → filter(x>5) → RDD2 [6,8,10]
                        → reduce(+) → 24
                
                故障恢复：若 RDD2 分区丢失，回溯 Lineage 从 RDD0 重算整个链路。
                        无需额外存储，但长链重算开销大。
                
                Checkpoint：
                  RDD0 → ... → RDD10 [checkpoint到HDFS] → ... → RDD20
                
                故障恢复：若 RDD20 分区丢失，从最近 Checkpoint(RDD10) 恢复，无需回溯到 RDD0。
                         截断血缘，避免长链重算。
                """);

        // 模拟 Linage 重算链
        System.out.println("模拟 Lineage 重算链：");
        List<Integer> rd0 = List.of(1, 2, 3, 4, 5);
        System.out.println("  RDD_0 (Source): " + rd0);

        List<Integer> rd1 = rd0.stream().map(x -> x * 2).collect(Collectors.toList());
        System.out.println("  RDD_1 (map x2): " + rd1);

        List<Integer> rd2 = rd1.stream().filter(x -> x > 3).collect(Collectors.toList());
        System.out.println("  RDD_2 (filter>3): " + rd2);

        int result = rd2.stream().reduce(0, Integer::sum);
        System.out.println("  Action(reduce 求和): " + result);

        System.out.println("\n  血缘链: Source → map → filter → action");
        System.out.println("  若 RDD_2 分区丢失 → 从 Source 沿血缘链重算整个 DAG");
        System.out.println("  若 RDD_1 Checkpoint → 从 RDD_1 恢复, 只重算 filter+action\n");
    }

    /* ======================== 日志工具 ======================== */

    static void printSeparator(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60));
    }

    /* ======================== main ======================== */

    public static void main(String[] args) {
        System.out.println("========== Flink 窗口机制核心原理演示 ==========\n");

        rdTumblingWindowDemo();
        rdSlidingWindowDemo();
        rdSessionWindowDemo();
        rdCountWindowDemo();
        rdDualTriggerDemo();
        rdSideOutputLateDataDemo();
        rdLineageVsCheckpointDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    // ==================== 滚动窗口 ====================

    static void rdTumblingWindowDemo() {
        printSeparator("1. 滚动窗口 Tumbling Window (size=5s)");

        List<FlinkEvent> events = List.of(
                new FlinkEvent("A", 1000L, 1),
                new FlinkEvent("A", 2000L, 2),
                new FlinkEvent("A", 3000L, 3),
                new FlinkEvent("A", 6000L, 4),  // 跨窗口
                new FlinkEvent("A", 7000L, 5)
        );

        System.out.println("时间轴(ms): t=1000 t=2000 t=3000 t=6000 t=7000");

        WindowOperator op = new WindowOperator(
                new TumblingWindowAssigner(5000L),
                new CompositeTrigger(Long.MAX_VALUE)  // 纯 watermark 触发
        );

        List<WindowResult> results = new ArrayList<>();
        // watermark 在最后一条事件后推进到 7500ms
        long watermark = 7500L;
        for (FlinkEvent e : events) {
            List<WindowResult> batch = op.processElement(e, watermark);
            if (batch != null) results.addAll(batch);
        }

        for (WindowResult r : results) {
            System.out.printf("  窗口=%s key=%s sum=%d count=%d%n",
                    r.window(), r.key(), r.sum(), r.count());
        }

        System.out.println("  解释: window[0,5000) 含 t=1000/2000/3000, window[5000,10000) 含 t=6000/7000");
        System.out.println("  触发条件: watermark(7500) >= window.end(5000/10000)\n");
    }

    // ==================== 滑动窗口 ====================

    static void rdSlidingWindowDemo() {
        printSeparator("2. 滑动窗口 Sliding Window (size=10s, slide=5s)");

        List<FlinkEvent> events = List.of(
                new FlinkEvent("B", 1000L, 10),
                new FlinkEvent("B", 6000L, 20),
                new FlinkEvent("B", 11000L, 30)
        );

        System.out.println("时间轴(ms): t=1000 t=6000 t=11000");

        WindowAssigner assigner = new SlidingWindowAssigner(10000L, 5000L);

        System.out.println("数据分配结果：");
        for (FlinkEvent e : events) {
            Collection<Window> windows = assigner.assignWindows(e);
            System.out.printf("  Event(t=%d, v=%d) -> 窗口: %s%n",
                    e.timestamp(), e.value(), windows);
        }

        long totalEventsInWindows = 0;
        for (FlinkEvent e : events) {
            totalEventsInWindows += assigner.assignWindows(e).size();
        }
        System.out.printf("\n  3条数据分配到了 %d 个窗口(存在重复计算), 实际数据量=3%n",
                totalEventsInWindows);
        System.out.println("  关键: 滑动窗口数据可能属于多个窗口, 必须重复计算\n");
    }

    // ==================== 会话窗口 ====================

    static void rdSessionWindowDemo() {
        printSeparator("3. 会话窗口 Session Window (gap=3s)");

        List<FlinkEvent> events = List.of(
                new FlinkEvent("C", 1000L, 1),   // Session1 开始
                new FlinkEvent("C", 2000L, 2),   // Session1 内
                new FlinkEvent("C", 6000L, 3),   // gap>3s, 新Session2
                new FlinkEvent("C", 7000L, 4),   // Session2 内
                new FlinkEvent("C", 12000L, 5)   // gap>3s, 新Session3 - 独立
        );

        long sessionGap = 3000L;
        System.out.printf("会话 Gap=%dms%n", sessionGap);
        System.out.println("时间线:");
        System.out.println("  Session1: t=1000 t=2000 ─gap:4000ms─> Session2: t=6000 t=7000 ─gap:5000ms─> Session3: t=12000");

        // 合并逻辑: 按时间排序，相邻间隔 > gap 则创建新会话
        List<FlinkEvent> sorted = events.stream()
                .sorted(Comparator.comparingLong(FlinkEvent::timestamp))
                .collect(Collectors.toList());

        List<List<FlinkEvent>> sessions = new ArrayList<>();
        List<FlinkEvent> currentSession = new ArrayList<>();
        long lastTimestamp = -1;

        for (FlinkEvent e : sorted) {
            if (!currentSession.isEmpty() && (e.timestamp() - lastTimestamp) > sessionGap) {
                sessions.add(new ArrayList<>(currentSession));
                currentSession.clear();
            }
            currentSession.add(e);
            lastTimestamp = e.timestamp();
        }
        if (!currentSession.isEmpty()) {
            sessions.add(new ArrayList<>(currentSession));
        }

        for (int i = 0; i < sessions.size(); i++) {
            List<FlinkEvent> session = sessions.get(i);
            int sum = session.stream().mapToInt(FlinkEvent::value).sum();
            long start = session.get(0).timestamp();
            long end = session.get(session.size() - 1).timestamp();
            System.out.printf("  Session%d [%dms~%dms]: 数据=%s sum=%d%n",
                    i + 1, start, end,
                    session.stream().map(FlinkEvent::value).collect(Collectors.toList()), sum);
        }
        System.out.println("  关键: 会话窗口大小动态变化, 依靠 watermark 推进触发闭合\n");
    }

    // ==================== 计数窗口 ====================

    static void rdCountWindowDemo() {
        printSeparator("4. 计数窗口 Count Window (count=3)");

        System.out.println("滚动计数窗口: 每3条触发一次计算");
        int maxCount = 3;
        // 模拟滚动计数
        List<Integer> data = List.of(1, 2, 3, 4, 5, 6, 7);
        System.out.println("输入数据: " + data);

        List<Integer> batch = new ArrayList<>();
        List<Integer> batchSums = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            batch.add(data.get(i));
            if (batch.size() == maxCount || i == data.size() - 1) {
                int sum = batch.stream().mapToInt(Integer::intValue).sum();
                batchSums.add(sum);
                System.out.printf("  批次%d: %s -> sum=%d (count=%d)%n",
                        batchSums.size(), batch, sum, batch.size());
                batch.clear();
            }
        }
        System.out.println("  关键: 计数窗口与事件时间无关, 适合固定批量聚合场景\n");
    }

    // ==================== 组合触发器 ====================

    static void rdDualTriggerDemo() {
        printSeparator("5. 组合触发器: Watermark到达 + Count触发");

        System.out.println("场景: 窗口5s, 同时配置 count触发=2 (哪个先到触发哪个)");
        System.out.println("""
                数据:
                  t=1000 val=1 → window[0,5000) count=1, watermark=500
                  t=2000 val=2 → window[0,5000) count=2 → Fire! (count触发)
                  t=3000 val=3 → window[0,5000) count=1, watermark=500
                  watermark推进到 5500 → 触发 window[0,5000) → Fire! (watermark触发)
                """);

        Map<String, Integer> triggerLog = new LinkedHashMap<>();
        triggerLog.put("window[0,5000) count=3 + watermark", 2);  // 被 count 触发1次 + watermark 触发1次
        System.out.println("  CountTrigger 触发: t=1000/2000 凑满2条 -> FIRE");
        System.out.println("  WatermarkTrigger 触发: t=3000 凑不满, watermark=5500 > end → FIRE");
        System.out.println("  设计要点: Trigger可以组合, Flink默认内置 EventTimeTrigger/CountTrigger\n");
    }

    // ==================== SideOutput 延迟数据处理 ====================

    static void rdSideOutputLateDataDemo() {
        printSeparator("6. 侧输出 SideOutput 处理延迟数据");

        // 设定 allowedLateness=2s, watermark 超过 window.end+2s 后仍到的数据视为延迟
        long windowSize = 5000L;
        long allowedLateness = 2000L;
        // window[0,5000) 的最终截止 watermark = 5000 + 2000 = 7000

        List<FlinkEvent> lateScenario = List.of(
                new FlinkEvent("D", 1000L, 10),   // window[0,5000) 正常
                new FlinkEvent("D", 3000L, 20),   // window[0,5000) 正常
                new FlinkEvent("D", 8000L, 99)    // window[0,5000) 延迟, t>7000
        );

        System.out.println("配置: window=5s, allowedLateness=2s, SideOutput=收集延迟数据");
        System.out.println("Late Cutoff = window.end + allowedLateness = 5000+2000 = 7000ms");

        SideOutputCollector sideCollector = new SideOutputCollector();

        long currentWatermark = 0;
        for (FlinkEvent e : lateScenario) {
            currentWatermark = Math.max(currentWatermark, e.timestamp());
            long windowStart = (e.timestamp() / windowSize) * windowSize;
            long windowEnd = windowStart + windowSize;
            long lateCutoff = windowEnd + allowedLateness;

            if (currentWatermark > lateCutoff && e.timestamp() < windowEnd) {
                // 数据已超过允许延迟时间, 进入侧输出
                WindowResult lateResult = new WindowResult(
                        new Window(windowStart, windowEnd), e.key(), e.value(), 1);
                sideCollector.collect(lateResult);
            } else {
                System.out.printf("  [正常] t=%dms val=%d window=%s (wm=%d, cutoff=%d)%n",
                        e.timestamp(), e.value(), new Window(windowStart, windowEnd),
                        currentWatermark, lateCutoff);
            }
        }
        System.out.printf("\n  侧输出共收集 %d 条延迟数据%n", sideCollector.lateData.size());
        System.out.println("  设计要点: SideOutput 避免数据丢弃, 延迟数据单独处理\n");
    }
}