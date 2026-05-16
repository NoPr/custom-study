# 02 - volatile 与 CAS

## 1. volatile 详解

### 1.1 JMM 内存模型

```mermaid
flowchart LR
    subgraph "线程 A"
        WA["工作内存 A<br/>（CPU 缓存/寄存器）"]
    end
    subgraph "线程 B"
        WB["工作内存 B<br/>（CPU 缓存/寄存器）"]
    end
    subgraph "主内存"
        MM["共享变量<br/>（主内存）"]
    end
    WA <-->|"read / write"| MM
    WB <-->|"read / write"| MM
```

- **主内存**：所有线程共享，存储所有共享变量
- **工作内存**：每个线程私有，变量副本 + CPU 缓存 + 寄存器
- 线程间通信必须通过主内存

### 1.2 volatile 两大特性

```mermaid
flowchart TD
    V["volatile"] --> A["① 可见性<br/>写立即刷主内存<br/>读从主内存读"]
    V --> B["② 禁止重排序<br/>内存屏障<br/>StoreStore / StoreLoad / LoadLoad / LoadStore"]
```

#### 1.2.1 可见性原理

**volatile 写：**
1. 将工作内存值刷新到主内存
2. Lock 前缀指令 → 总线锁定 → 其他 CPU 缓存行失效（MESI 协议）

**volatile 读：**
1. 本地缓存失效
2. 从主内存重新加载

#### 1.2.2 MESI 缓存一致性协议

```mermaid
stateDiagram-v2
    M: Modified（已修改）
    E: Exclusive（独占）
    S: Shared（共享）
    I: Invalid（失效）

    [*] --> I: 初始
    I --> E: 本地读取（其他 CPU 无副本）
    I --> S: 本地读取（其他 CPU 有副本）
    E --> M: 本地写入
    M --> S: 其他 CPU 读取
    S --> I: 其他 CPU 写入（总线嗅探）
    E --> I: 其他 CPU 写入
    M --> I: 其他 CPU 读取/写入
```

volatile 写触发：CPU0 发送 RFO（Read For Ownership）→ 其他 CPU 标记 I。

#### 1.2.3 禁止指令重排序 — 内存屏障

| 屏障类型 | 作用 |
|----------|------|
| **StoreStore** | volatile 写前的普通写完成后，才执行 volatile 写 |
| **StoreLoad** | volatile 写完成后，才执行后续的读操作 |
| **LoadLoad** | volatile 读完成后，才执行后续的读操作 |
| **LoadStore** | volatile 读完成后，才执行后续的写操作 |

**volatile 写插入屏障：**
```
普通写 → StoreStore → volatile 写 → StoreLoad
```

**volatile 读插入屏障：**
```
volatile 读 → LoadLoad → LoadStore
```

### 1.3 volatile 不保证原子性

```java
volatile int count = 0;
count++;  // 非原子！读 → 加1 → 写，三步操作
```

**为什么 count++ 非原子？**

```
线程 A: 读 count(0) → +1 → 写 count(1)
线程 B: 读 count(0) → +1 → 写 count(1)
// 期望 count=2，实际 count=1，丢失一次更新
```

### 1.4 DCL 单例中的 volatile

```java
public class Singleton {
    private static volatile Singleton instance;  // ← 必须 volatile！

    public static Singleton getInstance() {
        if (instance == null) {           // 第一次检查
            synchronized (Singleton.class) {
                if (instance == null) {   // 第二次检查
                    instance = new Singleton();  // 三步非原子操作
                }
            }
        }
        return instance;
    }
}
```

**new Singleton() 三步：**

```mermaid
flowchart LR
    A["① allocate<br/>分配内存"] --> B["② ctorInstance<br/>初始化对象"]
    B --> C["③ instance=memory<br/>引用赋值"]

    A --> C2["③ instance=memory<br/>引用赋值（重排序后）"]
    C2 -.-> B2["② ctorInstance<br/>初始化（太晚了！）"]

    style C2 fill:#ffcdd2
    style B2 fill:#ffcdd2
```

volatile 通过 StoreStore 屏障保证 ② 必须在 ③ 之前完成。

---

## 2. CAS 详解

### 2.1 CAS 原理

```mermaid
flowchart TD
    START["CAS(V, A, B)"] --> READ["读取内存值 V"]
    READ --> CMP{"V == A ?"}
    CMP -->|"是"| SET["更新 V = B<br/>返回 true"]
    CMP -->|"否"| FAIL["不更新<br/>返回 false"]

    SET --> DONE["完成"]
    FAIL --> SPIN["自旋：重新读取 V<br/>设为新的 A 再尝试"]
    SPIN --> READ
```

**CAS 自旋模板：**
```java
do {
    oldValue = atomicVar.get();
    newValue = computeNewValue(oldValue);
} while (!atomicVar.compareAndSet(oldValue, newValue));
```

### 2.2 ABA 问题

```mermaid
sequenceDiagram
    participant T1 as 线程 T1
    participant V as 共享变量
    participant T2 as 线程 T2

    T1->>V: 读到值 A
    T2->>V: CAS: A→B
    Note over V: 值 = B
    T2->>V: CAS: B→A
    Note over V: 值 = A
    T1->>V: CAS: A→C（成功！）
    Note over T1: 以为没有变化<br/>实际经历了 A→B→A
```

**经典场景** — 链表栈出栈：
```
head → A → B → C
T1 准备 pop（A→B）
T2 pop A、pop B、push A
T1 CAS：head 仍是 A，CAS 成功 → B 已被释放 → 悬垂指针
```

### 2.3 解决方案：版本号

```java
// AtomicStampedReference 使用 stamp 跟踪版本
AtomicStampedReference<Integer> ref = new AtomicStampedReference<>(100, 0);

// CAS 同时比较引用和版本号
ref.compareAndSet(expectedRef, newRef, expectedStamp, newStamp);
// 版本号不同 → CAS 失败 → 检测到 ABA
```

```mermaid
sequenceDiagram
    participant T1 as 线程 T1
    participant V as (ref, stamp)
    participant T2 as 线程 T2

    T1->>V: 读到 (A, 0)
    T2->>V: CAS: (A,0)→(B,1)
    T2->>V: CAS: (B,1)→(A,2)
    T1->>V: CAS: (A,0)→(C,1)
    Note over V: 期望 stamp=0 ≠ 当前 stamp=2<br/>CAS 失败！
```

### 2.4 CAS 优缺点

| 优点 | 缺点 |
|------|------|
| 无锁，无阻塞，无上下文切换 | 自旋 CPU 开销（高竞争时浪费） |
| 适合短临界区 | ABA 问题 |
| 是 JUC 基石 | 只能保证一个变量的原子操作 |

---

## 3. 面试要点

- volatile 两大特性及实现原理（MESI + 内存屏障）
- volatile 为什么不能保证原子性（复合操作）
- DCL 必须加 volatile 的原因（禁止重排序）
- CAS 的 ABA 问题及解决方案（AtomicStampedReference）
- CAS 自旋的开销和适用场景