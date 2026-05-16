package base.concurrent.interview;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 面试题：volatile 可见性原理解析
 *
 * 高频考点：
 * 1. volatile 两大特性：可见性 + 禁止指令重排序
 * 2. 可见性原理：JMM 内存模型，总线嗅探 + MESI 缓存一致性协议
 * 3. volatile 不保证原子性：count++ 是复合操作
 * 4. volatile 与 synchronized 对比
 * 5. DCL 单例为什么必须加 volatile
 */
public class Q03_VolatileVisibility {

    static boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        jmmModelExplain();
        visibilityPrinciple();
        mesiProtocolExplain();
        reorderingExplain();
        volatileVsSync();
    }

    static void jmmModelExplain() {
        System.out.println("=== JMM（Java Memory Model，JSR-133）===");
        System.out.println();
        System.out.println("JMM 定义了共享变量在多线程之间的访问规则：");
        System.out.println("  主内存（Main Memory）：所有线程共享，存储共享变量");
        System.out.println("  工作内存（Working Memory）：每个线程私有，存储变量的副本");
        System.out.println();
        System.out.println("  线程 A 修改共享变量：");
        System.out.println("    工作内存 A → 主内存（write）");
        System.out.println("  线程 B 读取共享变量：");
        System.out.println("    主内存 → 工作内存 B（read）");
        System.out.println();
        System.out.println("普通变量的可见性问题：");
        System.out.println("  线程 A 修改后可能暂存在 CPU 缓存/寄存器中，未及时刷回主内存。");
        System.out.println("  线程 B 可能一直读自己的 CPU 缓存，不知道主内存已变化。");
        System.out.println();
    }

    static void visibilityPrinciple() {
        System.out.println("=== volatile 可见性原理 ===");
        System.out.println();
        System.out.println("volatile 写：");
        System.out.println("  ① 将工作内存中的值刷新到主内存");
        System.out.println("  ② 通过 Lock 前缀指令 + 缓存一致性协议，使其他 CPU 缓存行失效");
        System.out.println();
        System.out.println("volatile 读：");
        System.out.println("  ① 使本地缓存失效");
        System.out.println("  ② 从主内存重新读取最新值");
        System.out.println();
        System.out.println("JVM 层面的内存屏障：");
        System.out.println("  volatile 写前插入 StoreStore 屏障");
        System.out.println("  volatile 写后插入 StoreLoad 屏障");
        System.out.println("  volatile 读后插入 LoadLoad + LoadStore 屏障");
        System.out.println();
    }

    /**
     * MESI 缓存一致性协议
     */
    static void mesiProtocolExplain() {
        System.out.println("=== MESI 缓存一致性协议 ===");
        System.out.println();
        System.out.println("CPU 多核缓存一致性通过 MESI 协议保证：");
        System.out.println("  M（Modified）  ：缓存行被修改，与主内存不一致，且仅存在于当前 CPU 缓存");
        System.out.println("  E（Exclusive） ：缓存行与主内存一致，且仅存在于当前 CPU 缓存");
        System.out.println("  S（Shared）    ：缓存行与主内存一致，可能存在于多个 CPU 缓存");
        System.out.println("  I（Invalid）   ：缓存行无效");
        System.out.println();
        System.out.println("volatile 写触发 MESI 流程：");
        System.out.println("  ① CPU0 将缓存行状态改为 M，写入新值");
        System.out.println("  ② CPU0 通过总线发送 RFO（Read For Ownership）消息");
        System.out.println("  ③ 其他 CPU 嗅探到总线消息，将自己缓存的该行标记为 I（Invalid）");
        System.out.println("  ④ 其他 CPU 再次读取时缓存不命中，从主内存重新加载");
        System.out.println();
    }

    static void reorderingExplain() {
        System.out.println("=== volatile 禁止指令重排序 ===");
        System.out.println();
        System.out.println("Happens-Before 规则中的 volatile 规则：");
        System.out.println("  对一个 volatile 变量的写，happens-before 于后续对这个 volatile 变量的读。");
        System.out.println();
        System.out.println("经典场景 — DCL 单例：");
        System.out.println("  instance = new Singleton() 分三步：");
        System.out.println("  ① memory = allocate()    分配内存");
        System.out.println("  ② ctorInstance(memory)   初始化对象");
        System.out.println("  ③ instance = memory      引用指向内存");
        System.out.println();
        System.out.println("  JIT 可能重排序为 ①③②：");
        System.out.println("  此时 instance != null 但对象未初始化 → 拿到不完整对象 → BUG！");
        System.out.println();
        System.out.println("  volatile 解决方案：");
        System.out.println("  ② 构造方法 → StoreStore 屏障 → ③ 赋值");
        System.out.println("  保证引用存在时对象一定已完整构造。");
        System.out.println();
    }

    /**
     * volatile vs synchronized 对比
     */
    static void volatileVsSync() {
        System.out.println("=== volatile vs synchronized ===");
        System.out.println();
        System.out.println("┌──────────────┬─────────────────────┬─────────────────────┐");
        System.out.println("│ 特性         │ volatile            │ synchronized        │");
        System.out.println("├──────────────┼─────────────────────┼─────────────────────┤");
        System.out.println("│ 可见性       │ 保证                │ 保证                │");
        System.out.println("│ 原子性       │ 不保证              │ 保证（代码块内）    │");
        System.out.println("│ 禁止重排序   │ 保证（内存屏障）    │ 保证（但范围更大）  │");
        System.out.println("│ 线程阻塞     │ 不阻塞              │ 阻塞                │");
        System.out.println("│ 性能开销     │ 读 = 普通变量       │ 有锁竞争开销        │");
        System.out.println("│ 适用场景     │ 状态标志、DCL       │ 复合操作、临界区    │");
        System.out.println("└──────────────┴─────────────────────┴─────────────────────┘");
        System.out.println();
        System.out.println("核心区别：volatile 是轻量级同步，只保证可见性不保证原子性。");
        System.out.println();
    }
}