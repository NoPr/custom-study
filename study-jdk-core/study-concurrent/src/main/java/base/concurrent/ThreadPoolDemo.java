package base.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池 7 参数 + 4 种拒绝策略演示
 *
 * 核心知识点：
 * ThreadPoolExecutor 7 个参数：
 * 1. corePoolSize    — 核心线程数（一直存在，除非 allowCoreThreadTimeOut）
 * 2. maximumPoolSize — 最大线程数
 * 3. keepAliveTime   — 空闲线程存活时间
 * 4. unit            — keepAliveTime 时间单位
 * 5. workQueue       — 工作队列（阻塞队列）
 * 6. threadFactory   — 线程工厂（命名线程用）
 * 7. handler         — 拒绝策略
 *
 * 线程池执行流程：
 * ① 线程数 < corePoolSize → 直接创建新线程执行
 * ② 线程数 ≥ corePoolSize → 任务入队
 * ③ 队列满且线程数 < maxPoolSize → 创建新线程执行
 * ④ 队列满且线程数 = maxPoolSize → 触发拒绝策略
 *
 * 常用线程池（Executors）：
 * - newFixedThreadPool(n)     — core=max=n, LinkedBlockingQueue(∞)
 * - newCachedThreadPool()     — core=0, max=∞, SynchronousQueue
 * - newSingleThreadExecutor() — core=max=1, LinkedBlockingQueue(∞)
 * - newScheduledThreadPool(n) — 定时/周期任务
 */
public class ThreadPoolDemo {

    public static void main(String[] args) throws Exception {
        sevenParametersExplain();
        threadPoolFlowExplain();
        abortPolicyDemo();
        callerRunsPolicyDemo();
        discardPolicyDemo();
        discardOldestPolicyDemo();
        customThreadFactoryDemo();
        futureTaskDemo();
    }

    static void sevenParametersExplain() {
        System.out.println("=== 线程池 7 个参数 ===");
        System.out.println("1. corePoolSize    核心线程（一直存活）");
        System.out.println("2. maximumPoolSize  最大线程（核心 + 临时）");
        System.out.println("3. keepAliveTime    空闲临时线程存活时间");
        System.out.println("4. unit             keepAliveTime 单位");
        System.out.println("5. workQueue        阻塞队列（ArrayBlockingQueue/LinkedBlockingQueue/SynchronousQueue）");
        System.out.println("6. threadFactory    线程创建工厂（命名规则、优先级、守护线程）");
        System.out.println("7. handler          拒绝策略（4 种）");
        System.out.println();
    }

    static void threadPoolFlowExplain() {
        System.out.println("=== 线程池执行流程 ===");
        System.out.println("提交任务 →");
        System.out.println("  ① 正在运行的线程 < corePoolSize？ → 创建核心线程执行");
        System.out.println("  ② 工作队列未满？ → 任务入队等待");
        System.out.println("  ③ 正在运行的线程 < maximumPoolSize？ → 创建临时线程执行");
        System.out.println("  ④ 触发拒绝策略 handler.rejectedExecution()");
        System.out.println();
    }

    /**
     * AbortPolicy（默认）：拒绝并抛出 RejectedExecutionException
     */
    static void abortPolicyDemo() {
        System.out.println("=== 拒绝策略 1: AbortPolicy（默认，抛异常）===");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );

        try {
            for (int i = 1; i <= 3; i++) {
                final int taskId = i;
                executor.execute(() -> {
                    System.out.println("  任务 " + taskId + " 执行中...");
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } catch (Exception e) {
            System.out.println("  捕获异常: " + e.getClass().getSimpleName());
        }

        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println();
    }

    /**
     * CallerRunsPolicy：由调用者线程（提交任务的线程）直接执行任务
     * 适用场景：不让任务丢失，且可以降低提交速率（调用者也在干活）
     */
    static void callerRunsPolicyDemo() {
        System.out.println("=== 拒绝策略 2: CallerRunsPolicy（调用者执行）===");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 1; i <= 3; i++) {
            final int taskId = i;
            executor.execute(() -> {
                String threadName = Thread.currentThread().getName();
                System.out.println("  任务 " + taskId + " 由线程 [" + threadName + "] 执行");
                latch.countDown();
            });
        }

        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("  注意第 3 个任务由 main 线程执行（caller）");
        executor.shutdown();
        System.out.println();
    }

    /**
     * DiscardPolicy：直接丢弃新任务，不抛异常
     * 适用场景：允许丢失任务的场景（如日志采样）
     */
    static void discardPolicyDemo() {
        System.out.println("=== 拒绝策略 3: DiscardPolicy（静默丢弃）===");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.DiscardPolicy()
        );

        for (int i = 1; i <= 3; i++) {
            final int taskId = i;
            executor.execute(() -> {
                System.out.println("  任务 " + taskId + " 执行");
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("  任务 3 被静默丢弃，无异常！");
        System.out.println();
    }

    /**
     * DiscardOldestPolicy：丢弃队列中最老的任务（队头），将新任务入队
     */
    static void discardOldestPolicyDemo() {
        System.out.println("=== 拒绝策略 4: DiscardOldestPolicy（丢弃最旧任务）===");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        AtomicInteger executedCount = new AtomicInteger(0);

        for (int i = 1; i <= 4; i++) {
            final int taskId = i;
            executor.execute(() -> {
                executedCount.incrementAndGet();
                System.out.println("  任务 " + taskId + " 执行");
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("  最终执行的任务数: " + executedCount.get() + "（最旧的任务被丢弃了）");
        System.out.println();
    }

    /**
     * 自定义线程工厂 — 命名线程 + 设置为守护线程
     */
    static void customThreadFactoryDemo() throws InterruptedException {
        System.out.println("=== 自定义 ThreadFactory ===");

        ThreadFactory namedFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r,
                        "custom-pool-" + threadNumber.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                namedFactory
        );

        executor.execute(() -> {
            System.out.println("  当前线程: " + Thread.currentThread().getName());
        });
        executor.execute(() -> {
            System.out.println("  当前线程: " + Thread.currentThread().getName());
        });

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
        System.out.println();
    }

    /**
     * Future + Callable 获取异步任务返回值
     */
    static void futureTaskDemo() throws Exception {
        System.out.println("=== Future + Callable 异步任务返回值 ===");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        Callable<Integer> task = () -> {
            TimeUnit.MILLISECONDS.sleep(200);
            return 42;
        };

        Future<Integer> future = executor.submit(task);
        System.out.println("  任务已提交，主线程可以继续做其他事情...");

        Integer result = future.get(1, TimeUnit.SECONDS);
        System.out.println("  获取到异步任务结果: " + result);

        executor.shutdown();
        System.out.println();
    }
}