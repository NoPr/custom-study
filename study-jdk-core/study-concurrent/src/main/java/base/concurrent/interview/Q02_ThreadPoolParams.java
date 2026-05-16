package base.concurrent.interview;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 面试题：线程池 7 大参数 + 工作流程 + 常见问题
 *
 * 高频考点：
 * 1. 7 个参数含义
 * 2. 任务提交流程（core → queue → max → reject）
 * 3. 队列选型：无界队列风险（OOM）、有界队列大小怎么设
 * 4. 拒绝策略选择
 * 5. Executors 工厂方法为什么不推荐（阿里规范）
 */
public class Q02_ThreadPoolParams {

    public static void main(String[] args) throws Exception {
        sevenParamsExplain();
        taskSubmitFlowExplain();
        queueSelectionGuide();
        whyNotExecutors();
        threadPoolSizingGuide();
    }

    static void sevenParamsExplain() {
        System.out.println("=== 线程池 7 大参数 ===");
        System.out.println("new ThreadPoolExecutor(");
        System.out.println("  int corePoolSize,        // ① 核心线程数（一直存活）");
        System.out.println("  int maximumPoolSize,     // ② 最大线程数（核心 + 临时）");
        System.out.println("  long keepAliveTime,      // ③ 空闲临时线程存活时间");
        System.out.println("  TimeUnit unit,           // ④ 时间单位");
        System.out.println("  BlockingQueue workQueue, // ⑤ 工作队列");
        System.out.println("  ThreadFactory factory,   // ⑥ 线程工厂");
        System.out.println("  RejectedExecutionHandler // ⑦ 拒绝策略");
        System.out.println(")");
        System.out.println();
    }

    static void taskSubmitFlowExplain() {
        System.out.println("=== 任务提交流程 ===");
        System.out.println("execute(runnable) 或 submit(callable) 后：");
        System.out.println();
        System.out.println("  ① 当前线程数 < corePoolSize？");
        System.out.println("     └─ 是 → 创建核心线程，直接执行任务");
        System.out.println("     └─ 否 → 进入 ②");
        System.out.println();
        System.out.println("  ② workQueue.offer(task) 成功？");
        System.out.println("     └─ 是 → 任务入队，等待空闲线程");
        System.out.println("     └─ 否 → 进入 ③");
        System.out.println();
        System.out.println("  ③ 当前线程数 < maximumPoolSize？");
        System.out.println("     └─ 是 → 创建临时线程，执行任务");
        System.out.println("     └─ 否 → 进入 ④");
        System.out.println();
        System.out.println("  ④ 触发拒绝策略 handler.rejectedExecution()");
        System.out.println();
        System.out.println("记忆口诀：core → queue → max → reject");
        System.out.println();
    }

    static void queueSelectionGuide() {
        System.out.println("=== 队列选型指南 ===");
        System.out.println("┌──────────────────────────┬──────────────────┬──────────────────┐");
        System.out.println("│ 队列类型                 │ 特点             │ 风险             │");
        System.out.println("├──────────────────────────┼──────────────────┼──────────────────┤");
        System.out.println("│ SynchronousQueue         │ 不存储，直接交付 │ 无等待队列       │");
        System.out.println("│ ArrayBlockingQueue(n)    │ 有界，必须设大小 │ 队列满了才创建   │");
        System.out.println("│                          │                  │ 临时线程         │");
        System.out.println("│ LinkedBlockingQueue()    │ 无界（默认∞）    │ OOM 风险！       │");
        System.out.println("│ LinkedBlockingQueue(n)   │ 有界，必须设大小 │ 队列满才拒绝     │");
        System.out.println("└──────────────────────────┴──────────────────┴──────────────────┘");
        System.out.println();
        System.out.println("阿里规范：必须使用有界队列，明确设置队列大小。");
        System.out.println();
    }

    /**
     * 为什么不推荐 Executors 工厂方法
     */
    static void whyNotExecutors() {
        System.out.println("=== 为什么阿里禁止用 Executors 创建线程池？===");

        System.out.println("1. newFixedThreadPool / newSingleThreadExecutor：");
        System.out.println("   使用 LinkedBlockingQueue（默认 Integer.MAX_VALUE）");
        System.out.println("   → 队列无限堆积 → OOM");
        System.out.println();

        System.out.println("2. newCachedThreadPool：");
        System.out.println("   使用 SynchronousQueue + maximumPoolSize = Integer.MAX_VALUE");
        System.out.println("   → 无限创建线程 → OOM（线程栈内存耗尽）");
        System.out.println();

        System.out.println("3. newScheduledThreadPool：");
        System.out.println("   maximumPoolSize = Integer.MAX_VALUE");
        System.out.println("   → 同上，无限创建线程 → OOM");
        System.out.println();

        System.out.println("正确姿势：new ThreadPoolExecutor(...) 手动指定所有参数。");
        System.out.println();
    }

    /**
     * 线程池大小估算指南
     */
    static void threadPoolSizingGuide() {
        System.out.println("=== 线程池大小估算 ===");

        System.out.println("CPU 密集型（计算为主，几乎无 I/O 等待）：");
        System.out.println("  线程数 = CPU 核数 + 1");
        System.out.println("  原因：CPU 核数个线程最大化 CPU 利用率，+1 防线程缺页中断");
        System.out.println();

        System.out.println("I/O 密集型（大量网络/磁盘 I/O，等待时间占比高）：");
        System.out.println("  线程数 = CPU 核数 * (1 + 平均等待时间 / 平均计算时间)");
        System.out.println("  简化公式：CPU 核数 * 2");
        System.out.println("  极端 I/O：CPU 核数 / (1 - 阻塞系数)，阻塞系数 ≈ 0.8~0.9");
        System.out.println();

        System.out.println("通用经验公式（《Java Concurrency in Practice》）：");
        System.out.println("  N_threads = N_cpu * U_cpu * (1 + W/C)");
        System.out.println("  U_cpu: 目标 CPU 利用率（0~1）");
        System.out.println("  W/C:   等待时间与计算时间的比率");
        System.out.println();

        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("当前机器 CPU 核数: " + cpuCores);
        System.out.println();
    }
}