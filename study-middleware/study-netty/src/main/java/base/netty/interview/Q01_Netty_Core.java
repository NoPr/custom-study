package base.netty.interview;

/**
 * 面试：Netty 核心组件 + 零拷贝 + Reactor 模式。
 *
 * <p>本类为面试自测工具，覆盖 Netty 高频面试题：
 * <ul>
 *   <li><b>Reactor 主从模型</b>：MainReactor(boss) → SubReactor(worker)</li>
 *   <li><b>Netty 内存池</b>：PooledByteBufAllocator 原理</li>
 *   <li><b>Netty 高性能原因</b>：NIO + Reactor + 零拷贝 + 池化 + 无锁串行化</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，逐题输出解答。
 *
 * @author study-tuling
 */
public class Q01_Netty_Core {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("        Netty 面试高频问题自测");
        System.out.println("=".repeat(60));

        question1_reactorModel();
        question2_zeroCopy();
        question3_byteBufAndPool();
        question4_pipelineAndHandler();
        question5_highPerformanceReasons();
    }

    /* ==================== 第 1 题：Reactor 主从模型 ==================== */

    /**
     * Reactor 模式演进：
     * <ol>
     *   <li><b>单 Reactor 单线程</b>：Redis 6.0 之前，accept + read + write + process 全在一个线程</li>
     *   <li><b>单 Reactor 多线程</b>：accept/read/write 在 Reactor 线程，process 在线程池</li>
     *   <li><b>主从 Reactor 多线程</b>（Netty 采用）：
     *     MainReactor(boss) 专门 accept，SubReactor(worker) 负责 read/write/process</li>
     * </ol>
     *
     * <pre>
     *      客户端连接               MainReactor(Boss EventLoopGroup)
     *      Client1 ──┐                  │ accept
     *      Client2 ──┤──────────>  ServerSocketChannel
     *      Client3 ──┘                  │ 分发
     *                      ┌───────────┼───────────┐
     *                      ▼           ▼           ▼
     *               SubReactor1  SubReactor2  SubReactorN
     *                (Worker)     (Worker)     (Worker)
     *                 read/write   read/write   read/write
     *                      │           │           │
     *                      ▼           ▼           ▼
     *               Pipeline链   Pipeline链   Pipeline链
     * </pre>
     */
    static void question1_reactorModel() {
        System.out.println("\n【Q1】讲一下 Netty 的 Reactor 主从模型");

        System.out.println("""
                Netty 采用**主从 Reactor 多线程模型**：

                1. Boss Group（MainReactor）
                   - 通常只有 1 个 NioEventLoop 线程
                   - 专门处理 ServerSocketChannel 的 OP_ACCEPT 事件
                   - 接收新连接后，轮询注册到 Worker Group

                2. Worker Group（SubReactor）
                   - 通常 CPU 核数 × 2 个 NioEventLoop 线程
                   - 处理已连接 SocketChannel 的 OP_READ / OP_WRITE 事件
                   - 每个 Channel 绑定一个固定的 Worker 线程，避免上下文切换

                3. 线程绑定策略
                   - 一个 Channel 从注册到销毁，始终由同一个 EventLoop 处理
                   - 实现**无锁串行化**：同一 Channel 的所有操作都在同一线程
                   - 局部无锁化设计是 Netty 高性能的核心原因之一

                与 Redis 对比：
                - Redis 6.0 之前：单 Reactor 单线程（accept/read/write/process 同一线程）
                - Netty：主从 Reactor，accept 和 IO 分离，充分利用多核 CPU""");
    }

    /* ==================== 第 2 题：零拷贝 ==================== */

    static void question2_zeroCopy() {
        System.out.println("\n【Q2】Netty 的零拷贝体现在哪些方面？");

        System.out.println("""
                Netty 的"零拷贝"有 4 个层面：

                1. **操作系统级零拷贝（sendfile）**
                   - FileRegion 封装 FileChannel.transferTo()
                   - 数据从磁盘 → 内核缓冲区 → 网卡，不经过用户态
                   - 传统 IO 4 次拷贝 → sendfile 2 次拷贝（0 次 CPU 拷贝）

                2. **CompositeByteBuf 零拷贝合并**
                   - 多个 ByteBuf 逻辑合并，不产生物理拷贝
                   - 例如 HTTP 响应 Header + Body 合并
                   - 底层维护一个 Component 数组，记录每个 ByteBuf 的引用

                3. **Slice 零拷贝切片**
                   - ByteBuf.slice() 共享同一块内存，只是调整 readerIndex/writerIndex
                   - 适合协议解析时从完整帧中切出 header/body 分别处理

                4. **DirectBuffer 堆外内存**
                   - 数据直接分配在堆外，IO 时避免堆内 → 堆外的一次拷贝
                   - Netty 默认使用 PooledDirectByteBuf

                与 Kafka 零拷贝对比：
                - Kafka 使用 mmap 写磁盘（PageCache）+ sendfile 发数据给消费者
                - Netty 侧重网络 IO 的零拷贝（sendfile + CompositeByteBuf）""");
    }

    /* ==================== 第 3 题：ByteBuf 与内存池 ==================== */

    static void question3_byteBufAndPool() {
        System.out.println("\n【Q3】Netty 的 ByteBuf 和 PooledByteBufAllocator 原理");

        System.out.println("""
                1. **ByteBuf 设计优势（对比 JDK ByteBuffer）**
                   - 读写双指针：readerIndex / writerIndex，无需 flip()
                   - 自动扩容：capacity 不足时自动扩展（最大 Integer.MAX_VALUE）
                   - 引用计数：ReferenceCounted 接口，手动或自动释放
                   - 池化支持：PooledByteBufAllocator 复用 ByteBuf，减少 GC 压力

                2. **PooledByteBufAllocator 内存池原理**
                   - 类似 jemalloc 的 Arena 内存分配算法
                   - 分层结构：PoolArena → PoolChunkList → PoolChunk → PoolPage → PoolSubpage
                   - PoolChunk：默认 16MB，以完全二叉树管理内存分配
                   - PoolSubpage：小于 8KB 的请求从 Subpage 分配（位图管理）
                   - ThreadLocal Cache：每个线程有私有的 PoolThreadCache，减少锁竞争

                3. **内存规格分类**
                   - Tiny：0 ~ 512B
                   - Small：512B ~ 8KB
                   - Normal：8KB ~ 16MB
                   - Huge：> 16MB（不走池化，直接分配释放）""");
    }

    /* ==================== 第 4 题：Pipeline 与 Handler ==================== */

    static void question4_pipelineAndHandler() {
        System.out.println("\n【Q4】Pipeline 链式处理机制是怎样运作的？");

        System.out.println("""
                1. **Pipeline 结构**
                   - 双向链表：Head ↔ Handler1 ↔ Handler2 ↔ ... ↔ Tail
                   - Head/Tail 是内置哨兵节点，用户 Handler 插在中间
                   - 每个 Handler 被包装成 ChannelHandlerContext

                2. **Inbound 事件传播（Head → Tail）**
                   - 触发：fireChannelActive / fireChannelRead / fireChannelReadComplete
                   - 方向：从 Head.next 开始，依次调用每个 Handler 的对应方法
                   - Handler 处理后调用 ctx.fireXxx() 继续向下传播
                   - 如果不调用 ctx.fireXxx()，事件传播中断

                3. **Outbound 事件传播（Tail → Head）**
                   - 触发：write / flush / writeAndFlush
                   - 方向：从 Tail.prev 开始，向 Head 方向传播
                   - 与 Inbound 方向相反

                4. **异常传播**
                   - 调用 ctx.fireExceptionCaught(cause)
                   - 从当前节点向后（Tail 方向）查找第一个能处理的 ExceptionHandler
                   - 如果 Tail 收到异常仍未处理，打印警告日志并释放资源""");
    }

    /* ==================== 第 5 题：高性能原因总结 ==================== */

    static void question5_highPerformanceReasons() {
        System.out.println("\n【Q5】Netty 高性能的原因有哪些？");

        System.out.println("""
                Netty 高性能的 6 大原因：

                1. **NIO 多路复用（I/O 模型）**
                   - 基于 JDK NIO Selector 实现 Reactor 模式
                   - 一个线程管理多个 Channel，避免 BIO 的一连接一线程模型
                   - epoll 边缘触发（Linux），减少无效的系统调用

                2. **Reactor 主从线程模型**
                   - Boss 线程专门 accept，Worker 线程专门 IO
                   - 线程分工明确，充分利用多核 CPU

                3. **零拷贝**
                   - sendfile：操作系统级，数据传输不经过用户态
                   - CompositeByteBuf/Slice：应用层零拷贝合并与切片
                   - DirectBuffer：堆外内存，IO 时零拷贝

                4. **无锁串行化设计**
                   - 每个 Channel 绑定固定 EventLoop 线程
                   - Channel 的所有操作在同一线程执行，无需加锁
                   - 局部无锁 → 减少锁竞争 → 高吞吐

                5. **内存池化（PooledByteBufAllocator）**
                   - ByteBuf 复用，减少频繁分配/释放开销
                   - 降低 GC 频率和停顿时间
                   - jemalloc 风格的 Arena 分配算法

                6. **高效的并发编程**
                   - MPSC（Multi-Producer Single-Consumer）队列
                   - FastThreadLocal（比 JDK ThreadLocal 快 3 倍）
                   - 时间轮（HashedWheelTimer）管理超时任务，O(1) 复杂度

                综合效果：
                - 单机支持百万级长连接
                - 稳定吞吐量可达数十万 QPS
                - 远优于传统的 BIO 线程模型（Tomcat 200 连接即频繁上下文切换）""");
    }
}