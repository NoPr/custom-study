package base.dolphinscheduler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务调度引擎：Master 分配 Worker + Cron 表达式解析器 + 任务分片 + 优先级队列 +
 * 失败重试 + 任务超时 Kill。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>Master-Worker 架构</b>：Master 负责任务分发和调度决策，
 *       Worker 负责任务执行并上报心跳/状态</li>
 *   <li><b>Cron 表达式解析器</b>：手写 5/6/7 字段解析，
 *       支持 * , - / 等通配符和步长</li>
 *   <li><b>任务分片</b>：将大规模任务拆分为 N 个分片并行实例化，每个分片处理数据子集</li>
 *   <li><b>PriorityBlockingQueue</b>：优先级队列按权重排序，高优先级任务优先分配 Worker</li>
 *   <li><b>失败重试</b>：支持重试间隔、最大重试次数、手动恢复标记</li>
 *   <li><b>超时 Kill</b>：任务执行超时后自动中断线程，释放 Worker 资源</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。
 *
 * @author study-tuling
 */
public class TaskSchedulerDemo {

    /* ==================== 1. 手写 Cron 表达式解析器 ==================== */

    /**
     * Cron 表达式解析器 — 支持 5/6 字段（分 时 日 月 周 [年]）。
     *
     * <p>字段取值范围：
     * <ul>
     *   <li>分钟：0-59</li>
     *   <li>小时：0-23</li>
     *   <li>日：1-31</li>
     *   <li>月：1-12 或 JAN-DEC</li>
     *   <li>星期：0-7（0 和 7 都表示周日）或 SUN-SAT</li>
     *   <li>年（可选）：1970-2099</li>
     * </ul>
     *
     * <p>支持通配符：*（任意）、,（枚举）、-（范围）、/（步长）。
     */
    static class CronExpression {
        private final String expression;
        private final Set<Integer> minutes;
        private final Set<Integer> hours;
        private final Set<Integer> daysOfMonth;
        private final Set<Integer> months;
        private final Set<Integer> daysOfWeek;

        private static final Map<String, Integer> MONTH_MAP = Map.ofEntries(
                Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3),
                Map.entry("APR", 4), Map.entry("MAY", 5), Map.entry("JUN", 6),
                Map.entry("JUL", 7), Map.entry("AUG", 8), Map.entry("SEP", 9),
                Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12)
        );

        private static final Map<String, Integer> WEEK_MAP = Map.ofEntries(
                Map.entry("SUN", 0), Map.entry("MON", 1), Map.entry("TUE", 2),
                Map.entry("WED", 3), Map.entry("THU", 4), Map.entry("FRI", 5), Map.entry("SAT", 6)
        );

        CronExpression(String expression) {
            this.expression = expression;
            String[] fields = expression.trim().split("\\s+");
            if (fields.length < 5 || fields.length > 7) {
                throw new IllegalArgumentException("Cron 表达式字段数需为 5-7，实际: " + fields.length);
            }
            this.minutes      = parseField(fields[0], 0, 59, null);
            this.hours        = parseField(fields[1], 0, 23, null);
            this.daysOfMonth  = parseField(fields[2], 1, 31, null);
            this.months       = parseField(fields[3], 1, 12, MONTH_MAP);
            this.daysOfWeek   = parseField(fields[4], 0, 7, WEEK_MAP);
        }

        /**
         * 解析单个 Cron 字段。
         *
         * <p>支持格式：
         * <ul>
         *   <li>*：匹配所有值</li>
         *   <li>5：精确值</li>
         *   <li>1,3,5：枚举</li>
         *   <li>1-5：范围</li>
         *   <li><code>*&sol;5</code> 或 <code>0-30/5</code>：步长</li>
         * </ul>
         */
        static Set<Integer> parseField(String field, int min, int max,
                                        Map<String, Integer> nameMap) {
            Set<Integer> result = new TreeSet<>();

            // 处理逗号分隔的多个子表达式
            for (String part : field.split(",")) {
                int step = 1;
                int start = min;
                int end = max;

                // 提取步长
                int slashIndex = part.indexOf('/');
                String rangePart;
                if (slashIndex != -1) {
                    rangePart = part.substring(0, slashIndex);
                    step = Integer.parseInt(part.substring(slashIndex + 1));
                } else {
                    rangePart = part;
                }

                // 解析范围
                if (rangePart.equals("*")) {
                    // start/end 保持默认值
                } else if (rangePart.contains("-")) {
                    String[] rangeParts = rangePart.split("-");
                    start = resolveValue(rangeParts[0], nameMap);
                    end = resolveValue(rangeParts[1], nameMap);
                } else {
                    start = resolveValue(rangePart, nameMap);
                    end = start;
                }

                // 展开值
                for (int value = start; value <= end; value += step) {
                    if (value >= min && value <= max) {
                        result.add(value);
                    }
                }
            }
            return Collections.unmodifiableSet(result);
        }

        /** 解析单个值，支持名称映射（如 JAN->1） */
        static int resolveValue(String value, Map<String, Integer> nameMap) {
            if (nameMap != null) {
                Integer mapped = nameMap.get(value.toUpperCase());
                if (mapped != null) {
                    return mapped;
                }
            }
            return Integer.parseInt(value);
        }

        boolean matches(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
            return minutes.contains(minute)
                    && hours.contains(hour)
                    && daysOfMonth.contains(dayOfMonth)
                    && months.contains(month)
                    && daysOfWeek.contains(dayOfWeek);
        }

        /** 返回人类可读的描述 */
        String describe() {
            return String.format("Cron[%s] -> 分:%s 时:%s 日:%s 月:%s 周:%s",
                    expression, minutes, hours, daysOfMonth, months, daysOfWeek);
        }
    }

    /* ==================== 2. 任务模型 ==================== */

    /** 优先级：HIGH > MEDIUM > LOW */
    enum TaskPriority { HIGH(3), MEDIUM(2), LOW(1);
        final int weight;
        TaskPriority(int weight) { this.weight = weight; }
    }

    /** 任务状态 */
    enum TaskState { PENDING, RUNNING, SUCCESS, FAILED, RETRYING, TIMEOUT, KILLED }

    /** 调度任务 */
    static class ScheduledTask implements Comparable<ScheduledTask> {
        final String taskId;
        final String name;
        final TaskPriority priority;
        final int maxRetries;
        final long timeoutMs;
        final CronExpression cronExpr;      // null 表示手动触发
        final int shardIndex;               // 分片编号 (-1 表示不分片)
        final int totalShards;              // 总分片数

        TaskState state = TaskState.PENDING;
        int retryCount = 0;
        long submitTime;
        long startTime;

        ScheduledTask(String taskId, String name, TaskPriority priority,
                      int maxRetries, long timeoutMs, CronExpression cronExpr,
                      int shardIndex, int totalShards) {
            this.taskId = taskId;
            this.name = name;
            this.priority = priority;
            this.maxRetries = maxRetries;
            this.timeoutMs = timeoutMs;
            this.cronExpr = cronExpr;
            this.shardIndex = shardIndex;
            this.totalShards = totalShards;
        }

        /** 优先级队列排序：权重高优先，同权重先提交优先 */
        @Override
        public int compareTo(ScheduledTask other) {
            int cmp = Integer.compare(other.priority.weight, this.priority.weight);
            if (cmp != 0) return cmp;
            return Long.compare(this.submitTime, other.submitTime);
        }

        @Override
        public String toString() {
            String shardInfo = shardIndex >= 0 ? String.format(" [分片%d/%d]", shardIndex, totalShards) : "";
            return String.format("Task{%s '%s' priority=%s retry=%d/%d state=%s%s}",
                    taskId, name, priority, retryCount, maxRetries, state, shardInfo);
        }
    }

    /* ==================== 3. Master 调度器 ==================== */

    static class MasterScheduler {
        /** 优先级阻塞队列：高优先级任务优先被 Worker 拉取 */
        final PriorityBlockingQueue<ScheduledTask> taskQueue = new PriorityBlockingQueue<>();

        /** Worker 线程池 */
        final ExecutorService workerPool = Executors.newFixedThreadPool(4);

        /** 正在执行的任务映射 */
        final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

        /** 任务重试记录 */
        final Map<String, Integer> retryMap = new ConcurrentHashMap<>();

        /** 手动恢复标记 */
        final Set<String> manualRecoveryTasks = ConcurrentHashMap.newKeySet();

        final AtomicInteger taskCounter = new AtomicInteger(0);

        /** 提交任务到优先级队列 */
        void submit(ScheduledTask task) {
            task.submitTime = System.currentTimeMillis();
            taskQueue.offer(task);
            System.out.printf("[Master] 提交任务: %s%n", task);
        }

        /** 任务分片：将一个逻辑任务拆分为 N 个物理分片并行实例化 */
        List<ScheduledTask> shard(ScheduledTask template, int shardCount) {
            List<ScheduledTask> shards = new ArrayList<>();
            for (int i = 0; i < shardCount; i++) {
                ScheduledTask shard = new ScheduledTask(
                        template.taskId + "-shard-" + i,
                        template.name,
                        template.priority,
                        template.maxRetries,
                        template.timeoutMs,
                        template.cronExpr,
                        i,
                        shardCount
                );
                shards.add(shard);
                submit(shard);
            }
            System.out.printf("[Master] 任务 [%s] 拆分为 %d 个分片并行实例化%n",
                    template.name, shardCount);
            return shards;
        }

        /** 调度循环：从优先级队列取出任务分配给 Worker */
        void startScheduling() {
            System.out.println("[Master] 调度循环启动, Worker 池大小=" + 4);
            Thread schedulerThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        ScheduledTask task = taskQueue.poll(2, TimeUnit.SECONDS);
                        if (task == null) continue;

                        // 手动恢复检查：如果是 FAILED 且标记了手动恢复，重新调度
                        if (task.state == TaskState.FAILED && manualRecoveryTasks.contains(task.taskId)) {
                            System.out.printf("[Master] 手动恢复任务: %s%n", task.taskId);
                            manualRecoveryTasks.remove(task.taskId);
                            task.state = TaskState.PENDING;
                            task.retryCount = 0;
                        }

                        if (task.state != TaskState.PENDING && task.state != TaskState.RETRYING) {
                            continue;
                        }

                        assignToWorker(task);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "master-scheduler");
            schedulerThread.setDaemon(true);
            schedulerThread.start();
        }

        /** 将任务分配给 Worker 执行 */
        void assignToWorker(ScheduledTask task) {
            task.state = TaskState.RUNNING;
            task.startTime = System.currentTimeMillis();

            Future<?> future = workerPool.submit(() -> executeOnWorker(task));
            runningTasks.put(task.taskId, future);

            // 超时监控：如果任务执行超过 timeoutMs，自动 kill
            if (task.timeoutMs > 0) {
                Thread timeoutGuard = new Thread(() -> {
                    try {
                        Thread.sleep(task.timeoutMs);
                        if (runningTasks.containsKey(task.taskId)) {
                            Future<?> taskFuture = runningTasks.get(task.taskId);
                            if (taskFuture != null && !taskFuture.isDone()) {
                                taskFuture.cancel(true);
                                task.state = TaskState.TIMEOUT;
                                runningTasks.remove(task.taskId);
                                System.out.printf("[超时-KILL] %s 执行超时(%dms), 已终止%n",
                                        task.taskId, task.timeoutMs);
                                handleRetry(task);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "timeout-guard-" + task.taskId);
                timeoutGuard.setDaemon(true);
                timeoutGuard.start();
            }
        }

        /** Worker 节点执行逻辑 */
        void executeOnWorker(ScheduledTask task) {
            String workerName = Thread.currentThread().getName();
            System.out.printf("[Worker-%s] 开始执行: %s%n", workerName, task);

            try {
                // 模拟任务执行
                long executionTime = 300 + ThreadLocalRandom.current().nextLong(500);
                Thread.sleep(executionTime);

                // 随机模拟失败 (30% 概率)
                if (ThreadLocalRandom.current().nextDouble() < 0.3) {
                    throw new RuntimeException("模拟任务执行异常");
                }

                task.state = TaskState.SUCCESS;
                runningTasks.remove(task.taskId);
                System.out.printf("[Worker-%s] 执行成功: %s (耗时 %dms)%n",
                        workerName, task.taskId, executionTime);

            } catch (InterruptedException e) {
                task.state = TaskState.KILLED;
                runningTasks.remove(task.taskId);
                System.out.printf("[Worker-%s] 任务被中断: %s%n", workerName, task.taskId);
                Thread.currentThread().interrupt();

            } catch (Exception e) {
                task.state = TaskState.FAILED;
                runningTasks.remove(task.taskId);
                System.out.printf("[Worker-%s] 执行失败: %s, 原因: %s%n",
                        workerName, task.taskId, e.getMessage());
                handleRetry(task);
            }
        }

        /** 失败重试逻辑 */
        void handleRetry(ScheduledTask task) {
            int currentRetries = retryMap.merge(task.taskId, 1, Integer::sum);
            if (currentRetries <= task.maxRetries) {
                task.state = TaskState.RETRYING;
                task.retryCount = currentRetries;
                long delayMs = (long) Math.pow(2, currentRetries) * 100; // 指数退避: 200ms, 400ms, 800ms...
                System.out.printf("[Retry] %s 第 %d/%d 次重试, 延迟 %dms%n",
                        task.taskId, currentRetries, task.maxRetries, delayMs);

                // 延迟后重新入队
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                taskQueue.offer(task);
            } else {
                task.state = TaskState.FAILED;
                System.out.printf("[Retry] %s 已达最大重试次数 %d, 标记为 FAILED, 等待手动恢复%n",
                        task.taskId, task.maxRetries);
                // 模拟手动恢复：延迟一段时间后标记
                manualRecoveryTasks.add(task.taskId);
            }
        }

        void shutdown() {
            workerPool.shutdown();
            try {
                workerPool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* ==================== 4. Demo 场景 ==================== */

    static void demoCronParser() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 1：手写 Cron 表达式解析器验证");
        System.out.println("=".repeat(60));

        String[][] testCases = {
                {"0 0 2 * * ?",       "每天凌晨 2:00"},
                {"0 */5 * * * ?",     "每 5 分钟"},
                {"0 0 9-18/2 * * ?",  "工作时段每 2 小时 (9,11,13,15,17)"},
                {"0 30 1 1,15 * ?",   "每月 1 号和 15 号 1:30"},
                {"0 0 8 ? * MON-FRI", "周一到周五 8:00"},
                {"0 0 0 1 JAN ?",    "每年 1 月 1 日 0:00"},
        };

        for (String[] testCase : testCases) {
            CronExpression cron = new CronExpression(testCase[0]);
            System.out.printf("表达式: %-25s  含义: %s%n", testCase[0], testCase[1]);
            System.out.printf("  解析: %s%n", cron.describe());

            // 验证特定时间点
            boolean match = cron.matches(0, 2, 1, 1, 1);
            System.out.printf("  匹配 (分=0,时=2,日=1,月=1,周=1): %s%n%n", match);
        }
    }

    static void demoTaskSharding() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 2：任务分片 -- 大数据量并行处理");
        System.out.println("=".repeat(60));

        MasterScheduler master = new MasterScheduler();
        master.startScheduling();

        ScheduledTask bigDataTask = new ScheduledTask(
                "ETL-001", "用户行为数据ETL", TaskPriority.HIGH,
                2, 3000, null, -1, 0
        );

        System.out.println("原始任务: " + bigDataTask.name + " (数据量 1000 万条)");
        System.out.println("处理策略: 按 user_id % 分片数 拆分，每个分片处理一个数据分区");
        master.shard(bigDataTask, 4);

        sleepMs(5000);
        master.shutdown();
    }

    static void demoPriorityQueue() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 3：PriorityBlockingQueue 优先级调度");
        System.out.println("=".repeat(60));

        MasterScheduler master = new MasterScheduler();
        master.startScheduling();

        System.out.println("提交顺序: LOW → MEDIUM → HIGH → LOW → HIGH");
        master.submit(new ScheduledTask("P-001", "低优-日志归档",  TaskPriority.LOW,    1, 2000, null, -1, 0));
        master.submit(new ScheduledTask("P-002", "中优-数据同步",  TaskPriority.MEDIUM, 1, 2000, null, -1, 0));
        master.submit(new ScheduledTask("P-003", "高优-实时计算",  TaskPriority.HIGH,   1, 2000, null, -1, 0));
        master.submit(new ScheduledTask("P-004", "低优-报表导出",  TaskPriority.LOW,    1, 2000, null, -1, 0));
        master.submit(new ScheduledTask("P-005", "高优-风控检测",  TaskPriority.HIGH,   1, 2000, null, -1, 0));

        System.out.println("优先级队列按 weight(高3 > 中2 > 低1) 排序，同权重 FIFO\n");

        sleepMs(6000);
        master.shutdown();
    }

    static void demoRetryAndTimeout() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 4：失败重试 + 超时 Kill + 手动恢复");
        System.out.println("=".repeat(60));

        MasterScheduler master = new MasterScheduler();
        master.startScheduling();

        // 这是一类会随机失败的短耗时任务（30% 概率失败，最多重试 3 次）
        System.out.println(">>> 提交失败重试任务（预计 30% 概率失败，最多重试 3 次，指数退避）");
        master.submit(new ScheduledTask("RETRY-001", "失败-重试任务",  TaskPriority.MEDIUM, 3, 2000, null, -1, 0));
        master.submit(new ScheduledTask("RETRY-002", "失败-重试任务2", TaskPriority.MEDIUM, 3, 2000, null, -1, 0));

        // 超时任务
        System.out.println(">>> 提交超时 Kill 任务（执行时长 > 500ms 则超时 Kill）");
        master.submit(new ScheduledTask("TIMEOUT-001", "超时-Kill任务", TaskPriority.LOW, 0, 500, null, -1, 0));

        sleepMs(10000);

        // 模拟手动恢复
        System.out.println("\n>>> 模拟手动恢复 FAILED 任务:");
        if (!master.manualRecoveryTasks.isEmpty()) {
            for (String taskId : master.manualRecoveryTasks) {
                System.out.printf("  手动恢复任务: %s%n", taskId);
            }
            System.out.println("  在实际 DolphinScheduler 中，管理员可通过 UI 点击「恢复」按钮重新触发");
        }

        master.shutdown();
    }

    /* ==================== 5. 主入口 ==================== */

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("    DolphinScheduler 任务调度引擎核心原理演示");
        System.out.println("    覆盖: Cron解析器 | 任务分片 | 优先级队列 | 失败重试 | 超时Kill");
        System.out.println("=".repeat(60));

        demoCronParser();
        demoTaskSharding();
        demoPriorityQueue();
        demoRetryAndTimeout();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("    TaskSchedulerDemo 全部演示完毕");
        System.out.println("=".repeat(60));
    }
}