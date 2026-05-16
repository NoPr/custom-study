package base.netty;

/**
 * Pipeline 责任链模式：ChannelHandler + ChannelHandlerContext + Inbound/Outbound。
 *
 * <p>核心设计：
 * <ul>
 *   <li><b>Pipeline</b>：双向链表结构，head ↔ handler1 ↔ handler2 ↔ tail</li>
 *   <li><b>Inbound 处理器</b>：数据从 head → tail 方向传播（fireChannelRead 向下调用）</li>
 *   <li><b>Outbound 处理器</b>：数据从 tail → head 方向传播（write 向上调用）</li>
 *   <li><b>ChannelHandlerContext</b>：每个 Handler 被包装成 Context，串联成链</li>
 *   <li><b>异常传播</b>：exceptionCaught 从当前节点向后查找能处理的 Handler</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()，观察 Pipeline 中 Inbound/Outbound 事件的传播顺序。
 *
 * @author study-tuling
 */
public class PipelineDemo {

    public static void main(String[] args) {
        System.out.println("=== Netty Pipeline 责任链模式演示 ===\n");

        /*
         * ==================== 1. 构建 Pipeline ====================
         * 添加 Head → LogHandler(Inbound) → UppercaseHandler(Inbound+Outbound) → ExceptionHandler → Tail
         */
        DefaultChannelPipeline pipeline = new DefaultChannelPipeline();
        pipeline.addLast("logHandler", new LoggingHandler());
        pipeline.addLast("uppercaseHandler", new UppercaseHandler());
        pipeline.addLast("exceptionHandler", new ExceptionHandler());
        System.out.println("[Pipeline] 构建完成: Head → LoggingHandler → UppercaseHandler → ExceptionHandler → Tail\n");

        /*
         * ==================== 2. Inbound 事件传播 ====================
         * 模拟 channelActive → channelRead → channelReadComplete 事件流
         */
        System.out.println("--- Inbound 事件传播测试 ---");
        System.out.println(">>> 触发 channelActive（连接建立）");
        pipeline.fireChannelActive(); // 从 Head 开始向 Tail 传播
        System.out.println();

        System.out.println(">>> 触发 channelRead（读取数据 'hello netty'）");
        pipeline.fireChannelRead("hello netty"); // Head → LogHandler → UppercaseHandler → Tail
        System.out.println();

        System.out.println(">>> 触发 channelReadComplete（读取完成）");
        pipeline.fireChannelReadComplete();
        System.out.println();

        /*
         * ==================== 3. Outbound 事件传播 ====================
         * write 从 Tail 向 Head 方向传播
         */
        System.out.println("--- Outbound 事件传播测试 ---");
        System.out.println(">>> 触发 write（写出数据 'response'）");
        pipeline.write("response"); // Tail → ExceptionHandler → UppercaseHandler → LogHandler → Head
        System.out.println();

        System.out.println(">>> 触发 flush");
        pipeline.flush();
        System.out.println();

        /*
         * ==================== 4. 异常传播 ====================
         * exceptionCaught 从当前节点向后查找能处理的 ExceptionHandler
         */
        System.out.println("--- 异常传播测试 ---");
        System.out.println(">>> 触发 exceptionCaught（模拟 RuntimeException）");
        pipeline.fireExceptionCaught(new RuntimeException("模拟网络异常"));
        System.out.println();

        System.out.println("=== 演示完成 ===");
    }

    /* ========================== Pipeline 核心实现 ========================== */

    /**
     * 模拟 Netty DefaultChannelPipeline：双向链表 + Head/Tail 哨兵节点。
     *
     * <p>每个 Handler 被包装成 {@link DefaultChannelHandlerContext}，通过 prev/next 指针串联。
     */
    static class DefaultChannelPipeline {
        final DefaultChannelHandlerContext head;
        final DefaultChannelHandlerContext tail;

        DefaultChannelPipeline() {
            head = new DefaultChannelHandlerContext("head", new HeadHandler());
            tail = new DefaultChannelHandlerContext("tail", new TailHandler());
            head.next = tail;
            tail.prev = head;
        }

        /** 在尾部前添加 Handler */
        void addLast(String name, ChannelHandler handler) {
            DefaultChannelHandlerContext newCtx = new DefaultChannelHandlerContext(name, handler);
            DefaultChannelHandlerContext prevCtx = tail.prev;
            prevCtx.next = newCtx;
            newCtx.prev = prevCtx;
            newCtx.next = tail;
            tail.prev = newCtx;
        }

        /* === Inbound 事件：从 Head.next 开始向 Tail 方向传播 === */

        void fireChannelActive() {
            head.fireChannelActive();
        }

        void fireChannelRead(Object msg) {
            head.fireChannelRead(msg);
        }

        void fireChannelReadComplete() {
            head.fireChannelReadComplete();
        }

        void fireExceptionCaught(Throwable cause) {
            head.fireExceptionCaught(cause);
        }

        /* === Outbound 事件：从 Tail.prev 开始向 Head 方向传播 === */

        void write(Object msg) {
            tail.write(msg);
        }

        void flush() {
            tail.flush();
        }
    }

    /**
     * 模拟 Netty ChannelHandlerContext：包装 Handler，持有 prev/next 指针。
     *
     * <p>关键方法：
     * <ul>
     *   <li>fireXxx() —— 调用 next Context 的同名方法，实现 Inbound 传播</li>
     *   <li>write/flush —— 调用 prev Context 的同名方法，实现 Outbound 传播</li>
     * </ul>
     */
    static class DefaultChannelHandlerContext {
        final String name;
        final ChannelHandler handler;
        DefaultChannelHandlerContext prev;
        DefaultChannelHandlerContext next;

        DefaultChannelHandlerContext(String name, ChannelHandler handler) {
            this.name = name;
            this.handler = handler;
        }

        /* === Inbound 传播：next 方向 === */

        void fireChannelActive() {
            System.out.printf("  [%s] channelActive → %s%n", name, next.name);
            handler.channelActive(this);
        }

        void fireChannelRead(Object msg) {
            System.out.printf("  [%s] channelRead('%s') → %s%n", name, msg, next.name);
            handler.channelRead(this, msg);
        }

        void fireChannelReadComplete() {
            System.out.printf("  [%s] channelReadComplete → %s%n", name, next.name);
            handler.channelReadComplete(this);
        }

        void fireExceptionCaught(Throwable cause) {
            System.out.printf("  [%s] exceptionCaught(%s) → %s%n", name,
                    cause.getClass().getSimpleName(), next.name);
            handler.exceptionCaught(this, cause);
        }

        /* === Outbound 传播：prev 方向 === */

        void write(Object msg) {
            System.out.printf("  [%s] write('%s') → %s%n", name, msg, prev.name);
            handler.write(this, msg);
        }

        void flush() {
            System.out.printf("  [%s] flush → %s%n", name, prev.name);
            handler.flush(this);
        }
    }

    /* ========================== Handler 接口 ========================== */

    /** 模拟 Netty ChannelHandler 接口 */
    interface ChannelHandler {
        default void channelActive(DefaultChannelHandlerContext ctx) {
            ctx.fireChannelActive(); // 默认向下传播
        }

        default void channelRead(DefaultChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }

        default void channelReadComplete(DefaultChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();
        }

        default void write(DefaultChannelHandlerContext ctx, Object msg) {
            ctx.write(msg); // 默认向上传播（注意：此处 ctx.write 是 Outbound，向 prev 方向）
        }

        default void flush(DefaultChannelHandlerContext ctx) {
            ctx.flush();
        }

        default void exceptionCaught(DefaultChannelHandlerContext ctx, Throwable cause) {
            ctx.fireExceptionCaught(cause);
        }
    }

    /* ========================== 内置 Handler 实现 ========================== */

    /** Head 哨兵处理器：Inbound 起点、Outbound 终点 */
    static class HeadHandler implements ChannelHandler {
        @Override
        public void channelActive(DefaultChannelHandlerContext ctx) {
            ctx.fireChannelActive(); // 启动 Inbound 链
        }

        @Override
        public void channelRead(DefaultChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void channelReadComplete(DefaultChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();
        }

        @Override
        public void write(DefaultChannelHandlerContext ctx, Object msg) {
            /* Outbound 终点：实际写出数据（模拟） */
            System.out.println("    [Head-写出] 最终输出: " + msg);
        }
    }

    /** Tail 哨兵处理器：Inbound 终点、Outbound 起点 */
    static class TailHandler implements ChannelHandler {
        @Override
        public void channelRead(DefaultChannelHandlerContext ctx, Object msg) {
            /* Inbound 终点：释放 ByteBuf、记录未处理消息（模拟） */
            System.out.println("    [Tail-未处理] 消息到达链尾: " + msg);
        }

        @Override
        public void write(DefaultChannelHandlerContext ctx, Object msg) {
            ctx.write(msg); // 启动 Outbound 链（向 prev 方向）
        }

        @Override
        public void exceptionCaught(DefaultChannelHandlerContext ctx, Throwable cause) {
            System.err.println("    [Tail-异常] 未处理的异常到达链尾: " + cause.getMessage());
        }
    }

    /** 日志处理器：Inbound 只读，记录每个事件 */
    static class LoggingHandler implements ChannelHandler {
        @Override
        public void channelActive(DefaultChannelHandlerContext ctx) {
            System.out.println("    [LogHandler] 连接已建立");
            ctx.fireChannelActive(); // 继续向下传播
        }

        @Override
        public void channelRead(DefaultChannelHandlerContext ctx, Object msg) {
            System.out.println("    [LogHandler] 记录读取: " + msg);
            ctx.fireChannelRead(msg); // 继续向下传播
        }
    }

    /**
     * 大写转换处理器：Inbound 转大写 + Outbound 转大写。
     *
     * <p>同时实现 Inbound 和 Outbound 处理，演示双向传播机制。
     */
    static class UppercaseHandler implements ChannelHandler {
        @Override
        public void channelRead(DefaultChannelHandlerContext ctx, Object msg) {
            String upper = ((String) msg).toUpperCase();
            System.out.println("    [UppercaseHandler] 转大写: " + msg + " → " + upper);
            ctx.fireChannelRead(upper); // 转换后的数据继续向下传播
        }

        @Override
        public void write(DefaultChannelHandlerContext ctx, Object msg) {
            String upper = ((String) msg).toUpperCase();
            System.out.println("    [UppercaseHandler] Outbound 转大写: " + msg + " → " + upper);
            ctx.write(upper); // 继续向上传播
        }
    }

    /**
     * 异常处理器：捕获并处理异常，不继续向下传播。
     *
     * <p>Netty 中通常在 Pipeline 尾部添加 ExceptionHandler 兜底。
     */
    static class ExceptionHandler implements ChannelHandler {
        @Override
        public void exceptionCaught(DefaultChannelHandlerContext ctx, Throwable cause) {
            System.out.println("    [ExceptionHandler] 捕获异常: " + cause.getMessage());
            /* 处理完毕，不继续传播——阻止异常到达 Tail */
        }
    }
}