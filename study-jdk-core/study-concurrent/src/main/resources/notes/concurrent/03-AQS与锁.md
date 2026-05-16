# 03 - AQS 与锁

## 1. AQS 核心架构

### 1.1 三要素

```mermaid
flowchart TD
    subgraph "AQS（AbstractQueuedSynchronizer）"
        STATE["① volatile int state<br/>同步状态（0=未锁, 1=已锁, >1=重入）"]
        QUEUE["② CLH 变体队列<br/>双向链表，存储阻塞线程"]
        CAS["③ CAS<br/>修改 state / 入队出队"]
    end

    STATE --- CAS
    QUEUE --- CAS
```

### 1.2 CLH 队列结构

```mermaid
flowchart LR
    subgraph "CLH 队列"
        HEAD["head<br/>（哨兵节点）"]
        N1["Node 1<br/>thread: T1<br/>waitStatus: -1(SIGNAL)<br/>prev → head"]
        N2["Node 2<br/>thread: T2<br/>waitStatus: 0<br/>prev → Node1"]
        TAIL["tail"]
    end
    HEAD -->|"next"| N1
    N1 -->|"next"| N2
    TAIL --> N2
```

**Node 字段：**
- `prev` / `next`：双向链表指针
- `thread`：当前节点对应的线程
- `waitStatus`：等待状态
  - `0`：初始状态
  - `CANCELLED(1)`：节点取消
  - `SIGNAL(-1)`：后继节点需要被唤醒
  - `CONDITION(-2)`：节点在 Condition 队列中
  - `PROPAGATE(-3)`：共享模式下传播

### 1.3 模板方法模式

```mermaid
flowchart TD
    A["AQS.acquire(arg)"] --> B["tryAcquire(arg)"]
    A --> C["addWaiter(EXCLUSIVE)"]
    A --> D["acquireQueued()"]

    E["AQS.release(arg)"] --> F["tryRelease(arg)"]
    E --> G["unparkSuccessor(head)"]

    B -.->|"子类实现"| H["ReentrantLock.Sync"]
    F -.->|"子类实现"| H
```

AQS 定义骨架（acquire/release），子类实现具体逻辑（tryAcquire/tryRelease）。

---

## 2. ReentrantLock 原理

### 2.1 独占模式 acquire 流程

```mermaid
flowchart TD
    LOCK["lock()"] --> TRY["tryAcquire(1)"]
    TRY -->|"CAS state 0→1 成功"| SET["setExclusiveOwnerThread(current)"]
    SET --> RETURN["返回（获取锁成功）"]
    TRY -->|"失败"| ADD["addWaiter(EXCLUSIVE)<br/>创建 Node 入 CLH 队尾"]
    ADD --> ACQ["acquireQueued()<br/>自旋等待"]
    ACQ --> PRED{"前驱是 head？"}
    PRED -->|"是"| TRY2["tryAcquire(1)"]
    PRED -->|"否"| PARK["park() 阻塞"]
    PARK --> UNPARK["被前驱 unpark 唤醒"]
    UNPARK --> PRED
    TRY2 -->|"成功"| SETHEAD["设为 head<br/>返回"]
    TRY2 -->|"失败"| PARK
```

### 2.2 公平锁 vs 非公平锁

```mermaid
flowchart TD
    subgraph "非公平锁 lock()"
        NF1["直接 CAS state 0→1"] -->|"成功"| NF_DONE["获取锁"]
        NF1 -->|"失败"| NF2["acquire(1) → tryAcquire"]
        NF2 --> NF3["nonfairTryAcquire<br/>再次 CAS（不管队列）"]
    end

    subgraph "公平锁 lock()"
        F1["acquire(1) → tryAcquire"] --> F2{"hasQueuedPredecessors()？"}
        F2 -->|"队列无等待线程"| F3["CAS state 0→1"]
        F2 -->|"队列有等待线程"| F4["入队排队"]
    end
```

| 特性 | 非公平锁（默认） | 公平锁 |
|------|-----------------|--------|
| 获取顺序 | 可能插队 | 严格 FIFO |
| 吞吐量 | **高** | 低（上下文切换多） |
| 饥饿 | 可能有 | 无 |
| 实现 | lock() 直接 CAS | hasQueuedPredecessors() 检查 |

### 2.3 可重入实现

```java
// state 记录重入次数
tryAcquire(int acquires) {
    if (state == 0) {
        // CAS 抢锁
    } else if (currentThread == getExclusiveOwnerThread()) {
        int nextc = state + acquires;  // state + 1
        setState(nextc);
        return true;
    }
    return false;
}

tryRelease(int releases) {
    int c = getState() - releases;  // state - 1
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();
    boolean free = (c == 0);  // state == 0 才真正释放
    if (free) setExclusiveOwnerThread(null);
    setState(c);
    return free;
}
```

---

## 3. Condition 条件队列

```mermaid
flowchart TD
    subgraph "AQS 同步队列（CLH）"
        HEAD["head"] --> N1["Node(T1)"]
        N1 --> N2["Node(T2)"]
    end

    subgraph "Condition 条件队列"
        C_HEAD["firstWaiter"] --> C1["Node(T3, CONDITION)"]
        C1 --> C2["Node(T4, CONDITION)"]
    end

    C_HEAD -.->|"signal() 转移到同步队列"| N2
```

- **await()**：当前线程加入 Condition 队列，释放锁
- **signal()**：将 Condition 队列头节点转移到 CLH 同步队列，重新竞争锁
- **signalAll()**：将 Condition 队列所有节点转移到 CLH 同步队列

---

## 4. Lock 接口方法一览

| 方法 | 说明 |
|------|------|
| `lock()` | 获取锁，阻塞直到成功 |
| `lockInterruptibly()` | 可中断获取锁 |
| `tryLock()` | 非阻塞尝试获取锁 |
| `tryLock(time, unit)` | 带超时的尝试获取锁 |
| `unlock()` | 释放锁 |
| `newCondition()` | 创建 Condition 对象 |

---

## 5. 面试要点

- AQS 三要素（state + CLH + CAS）和模板方法模式
- ReentrantLock 的 lock/unlock 完整流程
- 公平锁 vs 非公平锁的区别和实现
- 可重入的实现原理（state 计数）
- Condition 和 Object.wait/notify 的对应关系