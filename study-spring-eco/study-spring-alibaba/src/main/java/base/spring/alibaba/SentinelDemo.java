package base.spring.alibaba;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sentinel 熔断降级：资源定义 + 规则配置 + 流控/熔断/热点/系统规则。
 *
 * <p>核心考点：
 * <ol>
 *   <li>滑动窗口计数器（LeapArray 简化版），QPS 限流 vs 并发线程数限流</li>
 *   <li>BlockHandler 降级逻辑（限流触发时，调用 fallback 方法）</li>
 *   <li>熔断规则三种策略：慢调用比例 / 异常比例 / 异常数</li>
 *   <li>熔断状态机：CLOSED → OPEN → HALF_OPEN → CLOSED</li>
 *   <li>@SentinelResource 注解中 blockHandler vs fallback 的区别</li>
 * </ol>
 *
 * <p>本 Demo 用纯 Java 模拟 Sentinel 核心机制。
 *
 * @author study-tuling
 */
public class SentinelDemo {

    // ======================== 1. 滑动窗口计数器（LeapArray 简化版） ========================

    /**
     * 简化版滑动窗口：2 个 Bucket，每个 Bucket 覆盖 500ms，总计 1s 窗口。
     * <p>真实 Sentinel 使用 LeapArray（环形数组），这里简化为 2 个固定 Bucket 轮换。
     */
    static class SimpleSlidingWindow {
        /** 当前活跃的 bucket 索引 */
        private int currentIndex = 0;
        /** bucket 0 的起始时间 */
        private final AtomicLong bucket0Start = new AtomicLong(System.currentTimeMillis());
        /** bucket 1 的起始时间 */
        private final AtomicLong bucket1Start = new AtomicLong(System.currentTimeMillis() + 500);
        /** 两个 bucket 的计数器 */
        private final AtomicLong[] counters = {new AtomicLong(0), new AtomicLong(0)};
        /** 每个 bucket 的时间窗口 ms */
        static final long BUCKET_WINDOW_MS = 500L;

        /**
         * 通过一次请求。
         *
         * @return 当前窗口（最近 1s）的请求总数
         */
        long pass() {
            long now = System.currentTimeMillis();
            int idx = selectBucket(now);
            counters[idx].incrementAndGet();
            System.out.printf("    [滑动窗口] Bucket#%d 计数+1, 最近1s总计=%d%n",
                    idx, totalQps(now));
            return totalQps(now);
        }

        /**
         * 计算最近 1s 的 QPS。
         */
        long totalQps(long now) {
            long total = 0;
            for (int i = 0; i < 2; i++) {
                long bucketStart = i == 0 ? bucket0Start.get() : bucket1Start.get();
                if (now - bucketStart < 1000L) {
                    total += counters[i].get();
                }
            }
            return total;
        }

        /**
         * 选择当前时间属于哪个 Bucket。
         * <p>如果当前时间超出了当前 Bucket 的范围，则轮换到另一个 Bucket 并清零。
         */
        private int selectBucket(long now) {
            if (now - bucket0Start.get() > 1000L) {
                // bucket0 过期，重置并使用它
                bucket0Start.set(now);
                counters[0].set(0);
                currentIndex = 0;
                return 0;
            }
            if (now - bucket1Start.get() > 1000L) {
                bucket1Start.set(now);
                counters[1].set(0);
                currentIndex = 1;
                return 1;
            }
            // 都在有效期内，使用当前 Bucket
            long dist0 = Math.abs(now - bucket0Start.get());
            long dist1 = Math.abs(now - bucket1Start.get());
            currentIndex = dist0 < dist1 ? 0 : 1;
            return currentIndex;
        }
    }

    // ======================== 2. 流控规则 ========================

    /**
     * 限流规则。
     */
    static class FlowRule {
        String resource;
        /** 限流类型：QPS 或 并发线程数 */
        enum Grade { QPS, THREAD }
        Grade grade;
        double threshold;
        SimpleSlidingWindow slidingWindow;

        FlowRule(String resource, Grade grade, double threshold) {
            this.resource = resource;
            this.grade = grade;
            this.threshold = threshold;
            this.slidingWindow = new SimpleSlidingWindow();
        }
    }

    // ======================== 3. 熔断规则（DegradeRule） ========================

    /**
     * 熔断状态机。
     */
    enum CircuitBreakerState {
        /** 关闭：正常通过请求 */
        CLOSED,
        /** 打开：直接拒绝请求 */
        OPEN,
        /** 半开：允许探测请求通过 */
        HALF_OPEN
    }

    /**
     * 熔断规则：慢调用比例 / 异常比例 / 异常数。
     */
    static class DegradeRule {
        String resource;
        /** 熔断策略 */
        enum Strategy { SLOW_REQUEST_RATIO, ERROR_RATIO, ERROR_COUNT }
        Strategy strategy;
        /** 阈值，例如 0.5 表示 50% */
        double threshold;
        /** 最小请求数（统计窗口内至少多少个请求才触发熔断） */
        int minRequestAmount = 5;
        /** 熔断时长 ms（OPEN 状态持续多久后才进入 HALF_OPEN） */
        long timeWindowMs = 10000L;

        /** 统计窗口：通过总数 */
        AtomicLong totalCount = new AtomicLong(0);
        /** 统计窗口：慢调用 / 异常数 */
        AtomicLong badCount = new AtomicLong(0);

        /** 当前熔断状态 */
        AtomicReference<CircuitBreakerState> state =
                new AtomicReference<>(CircuitBreakerState.CLOSED);
        /** OPEN 状态开始时间 */
        AtomicLong openTime = new AtomicLong(0);

        DegradeRule(String resource, Strategy strategy, double threshold) {
            this.resource = resource;
            this.strategy = strategy;
            this.threshold = threshold;
        }

        /**
         * 记录一次成功或失败的调用。
         *
         * @param isSlowOrError true=慢调用或异常，false=正常
         */
        void record(boolean isSlowOrError) {
            totalCount.incrementAndGet();
            if (isSlowOrError) {
                badCount.incrementAndGet();
            }
            checkAndTransition();
        }

        /**
         * 检查并执行状态转换。
         */
        void checkAndTransition() {
            CircuitBreakerState currentState = state.get();
            long total = totalCount.get();
            long bad = badCount.get();

            switch (currentState) {
                case CLOSED -> {
                    if (total < minRequestAmount) return;
                    double ratio = (double) bad / total;
                    if (ratio >= threshold) {
                        if (state.compareAndSet(CircuitBreakerState.CLOSED,
                                CircuitBreakerState.OPEN)) {
                            openTime.set(System.currentTimeMillis());
                            System.out.printf("    [熔断] %s CLOSED → OPEN (ratio=%.2f >= %.2f)%n",
                                    resource, ratio, threshold);
                        }
                    } else {
                        // 正常，重置计数器
                        totalCount.set(0);
                        badCount.set(0);
                    }
                }
                case OPEN -> {
                    long elapsed = System.currentTimeMillis() - openTime.get();
                    if (elapsed >= timeWindowMs) {
                        if (state.compareAndSet(CircuitBreakerState.OPEN,
                                CircuitBreakerState.HALF_OPEN)) {
                            totalCount.set(0);
                            badCount.set(0);
                            System.out.printf("    [熔断] %s OPEN → HALF_OPEN (经过 %dms)%n",
                                    resource, elapsed);
                        }
                    }
                }
                case HALF_OPEN -> {
                    if (total >= minRequestAmount && bad == 0) {
                        if (state.compareAndSet(CircuitBreakerState.HALF_OPEN,
                                CircuitBreakerState.CLOSED)) {
                            System.out.printf("    [熔断] %s HALF_OPEN → CLOSED (探测全部正常)%n",
                                    resource);
                        }
                    } else if (total >= minRequestAmount && bad > 0) {
                        if (state.compareAndSet(CircuitBreakerState.HALF_OPEN,
                                CircuitBreakerState.OPEN)) {
                            openTime.set(System.currentTimeMillis());
                            System.out.printf("    [熔断] %s HALF_OPEN → OPEN (探测存在异常)%n",
                                    resource);
                        }
                    }
                }
            }
        }

        /**
         * 尝试通过：CLOSED 或 HALF_OPEN 时允许通过，OPEN 时拒绝。
         *
         * @return true=允许通过，false=被熔断
         */
        boolean tryPass() {
            CircuitBreakerState currentState = state.get();
            return currentState != CircuitBreakerState.OPEN;
        }
    }

    // ======================== 4. 资源入口（模拟 @SentinelResource） ========================

    /**
     * 模拟 @SentinelResource(value="xxx", blockHandler="xxx", fallback="xxx")。
     */
    static class SentinelResourceProxy {
        private final Map<String, FlowRule> flowRules = new ConcurrentHashMap<>();
        private final Map<String, DegradeRule> degradeRules = new ConcurrentHashMap<>();

        void addFlowRule(FlowRule rule) {
            flowRules.put(rule.resource, rule);
        }

        void addDegradeRule(DegradeRule rule) {
            degradeRules.put(rule.resource, rule);
        }

        /**
         * 执行被保护的资源。
         *
         * @param resource 资源名
         * @param blockHandler 被限流/熔断时的降级逻辑
         * @param fallback 业务异常时的兜底逻辑
         * @param callable 正常业务逻辑
         * @return 执行结果
         */
        String execute(String resource, Runnable blockHandler,
                       Runnable fallback, java.util.function.Supplier<String> callable) {
            /* ── 步骤 1：检查熔断规则 ── */
            DegradeRule degradeRule = degradeRules.get(resource);
            if (degradeRule != null && !degradeRule.tryPass()) {
                System.out.printf("    [BlockHandler] %s 被熔断（状态=%s）%n",
                        resource, degradeRule.state.get());
                blockHandler.run();
                return "degraded";
            }

            /* ── 步骤 2：检查流控规则 ── */
            FlowRule flowRule = flowRules.get(resource);
            if (flowRule != null) {
                long currentQps = flowRule.slidingWindow.pass();
                if (currentQps > flowRule.threshold) {
                    System.out.printf("    [BlockHandler] %s 被限流 (QPS=%d > threshold=%.0f)%n",
                            resource, currentQps, flowRule.threshold);
                    blockHandler.run();
                    return "blocked";
                }
            }

            /* ── 步骤 3：执行业务逻辑 ── */
            try {
                String result = callable.get();
                if (degradeRule != null) {
                    degradeRule.record(false); // 正常调用
                }
                return result;
            } catch (Exception e) {
                if (degradeRule != null) {
                    degradeRule.record(true); // 异常调用
                }
                System.out.printf("    [Fallback] %s 业务异常: %s%n", resource, e.getMessage());
                fallback.run();
                return "fallback";
            }
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        SentinelResourceProxy proxy = new SentinelResourceProxy();

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   Sentinel 熔断降级 - 纯 Java 模拟演示       ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        /* ── 1. 流控规则演示（QPS 限流） ── */
        System.out.println("\n=== 1. QPS 限流（threshold=5） ===");
        proxy.addFlowRule(new FlowRule("getOrder", FlowRule.Grade.QPS, 5));

        for (int i = 0; i < 8; i++) {
            final int requestNo = i + 1;
            proxy.execute("getOrder",
                    () -> System.out.printf("    [BlockHandler] 请求#%d → 限流降级：返回默认订单%n", requestNo),
                    () -> System.out.printf("    [Fallback] 请求#%d → 兜底数据%n", requestNo),
                    () -> {
                        System.out.printf("    [业务] 请求#%d → 查询订单成功%n", requestNo);
                        return "order#" + requestNo;
                    });
        }

        /* ── 2. 熔断规则演示（异常比例） ── */
        System.out.println("\n=== 2. 熔断规则：异常比例（threshold=0.5, minRequest=5） ===");
        proxy.addDegradeRule(new DegradeRule("payOrder",
                DegradeRule.Strategy.ERROR_RATIO, 0.5));

        // 前 5 次中制造 3 次异常 → 比例 60% > 50% → 触发熔断
        System.out.println("  --- 制造异常触发熔断 ---");
        for (int i = 0; i < 5; i++) {
            final int no = i + 1;
            proxy.execute("payOrder",
                    () -> System.out.printf("    请求#%d 被熔断%n", no),
                    () -> System.out.printf("    请求#%d 异常兜底%n", no),
                    () -> {
                        if (no <= 3) throw new RuntimeException("支付超时");
                        System.out.printf("    请求#%d 支付成功%n", no);
                        return "paid#" + no;
                    });
        }
        // 熔断后请求被拒绝
        System.out.println("  --- OPEN 状态下请求被拒绝 ---");
        proxy.execute("payOrder",
                () -> System.out.println("    [BlockHandler] 熔断中，返回默认处理"),
                () -> {},
                () -> { System.out.println("    不该执行到这里"); return "ok"; });

        /* ── 3. 熔断状态机：CLOSED → OPEN → HALF_OPEN → CLOSED ── */
        System.out.println("\n=== 3. 熔断状态机详解 ===");
        // 手动构造一个规则演示状态流转
        DegradeRule demoRule = new DegradeRule("demoResource",
                DegradeRule.Strategy.ERROR_COUNT, 2);
        demoRule.minRequestAmount = 3;
        demoRule.timeWindowMs = 2000L; // 2s 熔断窗口，方便演示

        System.out.printf("  初始状态: %s%n", demoRule.state.get());
        // 模拟 3 次异常 → OPEN
        for (int i = 0; i < 3; i++) {
            demoRule.record(true);
        }
        System.out.printf("  3 次异常后: %s%n", demoRule.state.get());

        // 等待 2s → HALF_OPEN
        try { Thread.sleep(2100); } catch (InterruptedException ignored) {}
        demoRule.checkAndTransition();
        System.out.printf("  2s 后: %s%n", demoRule.state.get());

        // HALF_OPEN 下 3 次正常 → CLOSED
        for (int i = 0; i < 3; i++) {
            demoRule.record(false);
        }
        demoRule.checkAndTransition();
        System.out.printf("  探测全部正常后: %s%n", demoRule.state.get());

        /* ── 4. blockHandler vs fallback 区别 ── */
        System.out.println("\n=== 4. blockHandler vs fallback 区别 ===");
        System.out.println("  blockHandler: 触发条件 = 限流/熔断拒绝（BlockException）");
        System.out.println("  fallback:     触发条件 = 业务方法抛出异常");
        System.out.println("  优先级：blockHandler 先于 fallback");
        System.out.println("  同时配置时：限流 → blockHandler; 业务异常 → fallback");

        System.out.println("\n=== 演示结束 ===");
    }
}