# EventLoop 与 Pipeline

> 基于 JDK NIO 模拟 Netty 核心机制，理解 EventLoop 事件循环和 Pipeline 责任链。

## 1. EventLoop Selector 事件循环

Netty EventLoop 本质是**单线程 + Selector + 死循环**的三位一体结构。每个 EventLoop 绑定一个 Selector，通过 `while(true)` 轮询 IO 事件，所有 Channel 操作都在同一线程内完成。

```mermaid
flowchart TD
    subgraph EventLoop["NioEventLoop 线程"]
        START([线程启动]) --> TASK["执行任务队列<br/>（异步注册/定时任务）"]
        TASK --> SELECT["selector.select(timeout)<br/>阻塞等待 IO 事件"]
        SELECT --> |"就绪 Channel > 0"| PROCESS["processSelectedKeys()<br/>遍历 SelectionKey"]
        SELECT --> |"超时无事件"| TASK
        PROCESS --> |"OP_ACCEPT"| ACCEPT["Boss: accept 新连接<br/>注册到 Worker Selector"]
        PROCESS --> |"OP_READ"| READ["Worker: 读取数据<br/>传入 Pipeline 处理"]
        PROCESS --> |"OP_WRITE"| WRITE["Worker: 刷新写缓冲区<br/>恢复 OP_READ"]
        ACCEPT --> TASK
        READ --> TASK
        WRITE --> TASK
    end

    CLIENT([客户端连接]) -.-> |"TCP SYN"| ACCEPT
    READ -.-> |"数据"| PIPELINE["Pipeline 责任链处理"]
```

### 核心要点

| 组件 | 职责 | 线程数 |
|------|------|--------|
| Boss EventLoopGroup | 监听 ACCEPT 事件，接收连接 | 通常 1 个 |
| Worker EventLoopGroup | 处理 READ/WRITE 事件 | CPU 核数 x 2 |
| Channel-EventLoop 绑定 | 一个 Channel 从生到死绑定同一个 EventLoop | 无锁串行化 |

## 2. Pipeline 双向链表结构

ChannelPipeline 是一个**双向链表**，Head 和 Tail 是哨兵节点，用户自定义 Handler 插入在中间。

```mermaid
graph LR
    subgraph Pipeline["ChannelPipeline 双向链表"]
        HEAD["Head<br/>(哨兵: Inbound起点<br/>Outbound终点)"]
        H1["Handler1<br/>Inbound"]
        H2["Handler2<br/>Outbound"]
        H3["Handler3<br/>Inbound+Outbound"]
        TAIL["Tail<br/>(哨兵: Inbound终点<br/>Outbound起点)"]
    end

    HEAD <--> |"prev/next"| H1 <--> |"prev/next"| H2 <--> |"prev/next"| H3 <--> |"prev/next"| TAIL

    subgraph Context["ChannelHandlerContext"]
        NAME["name"]
        HANDLER["handler"]
        PREV["prev 指针"]
        NEXT["next 指针"]
    end
```

### Handler 类型

| 类型 | 传播方向 | 典型场景 |
|------|----------|----------|
| ChannelInboundHandler | Head -> Tail | 解码、业务处理 |
| ChannelOutboundHandler | Tail -> Head | 编码、写出 |
| ChannelDuplexHandler | 双向 | 日志、监控 |

## 3. Inbound / Outbound 事件传播

```mermaid
sequenceDiagram
    participant HEAD as Head
    participant H1 as LogHandler<br/>(Inbound)
    participant H2 as UppercaseHandler<br/>(In+Out)
    participant H3 as ExceptionHandler
    participant TAIL as Tail

    Note over HEAD,TAIL: ===== Inbound 事件: Head → Tail =====

    HEAD->>H1: ① channelRead("hello")
    H1->>H2: ② ctx.fireChannelRead("hello")
    H2->>H2: ③ 转大写 "HELLO"
    H2->>H3: ④ ctx.fireChannelRead("HELLO")
    H3->>TAIL: ⑤ ctx.fireChannelRead("HELLO")
    TAIL->>TAIL: ⑥ 释放资源

    Note over HEAD,TAIL: ===== Outbound 事件: Tail → Head =====

    TAIL->>H3: ⑦ write("response")
    H3->>H2: ⑧ ctx.write("response")
    H2->>H2: ⑨ Outbound 转大写 "RESPONSE"
    H2->>H1: ⑩ ctx.write("RESPONSE")
    H1->>HEAD: ctx.write("RESPONSE")
    HEAD->>HEAD: 实际 Socket 写出
```

### 异常传播规则

- `exceptionCaught` 从当前节点向 Tail 方向查找
- 找到第一个重写了 `exceptionCaught` 且**不调用** `ctx.fireExceptionCaught()` 的 Handler
- 如果异常传到 Tail 仍未处理，打印 WARN 日志并释放资源

---

> **最佳实践**：Pipeline 尾部添加 ExceptionHandler 兜底，避免异常到达 Tail 导致连接关闭。