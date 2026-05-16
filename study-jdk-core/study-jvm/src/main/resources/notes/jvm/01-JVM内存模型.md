# 01 - JVM 内存模型

## 1. JVM 运行时数据区

```mermaid
graph TB
    subgraph "JVM 运行时数据区"
        subgraph "线程私有"
            PC["程序计数器 PC<br/>当前线程字节码行号<br/>唯一不 OOM 的区域"]
            VM_Stack["虚拟机栈<br/>每个方法 → 一个栈帧<br/>StackOverflowError / OOM"]
            Native_Stack["本地方法栈<br/>Native 方法服务<br/>HotSpot 合入虚拟机栈"]
        end
        subgraph "线程共享"
            Heap["堆 Heap<br/>对象实例 + 数组<br/>-Xms / -Xmx 控制大小<br/>OOM 最主要区域"]
            Method_Area["方法区 / 元空间<br/>类信息 / 常量 / 静态变量<br/>JDK 8+ Metaspace<br/>-XX:MaxMetaspaceSize"]
        end
    end
```

---

## 2. 堆内存分代结构

```mermaid
graph LR
    subgraph "堆内存 Heap"
        subgraph "新生代 Young Generation (1/3)"
            Eden["Eden 区<br/>80% 新生代<br/>新对象分配"]
            S0["S0 Survivor<br/>10% 新生代<br/>From / To 切换"]
            S1["S1 Survivor<br/>10% 新生代<br/>From / To 切换"]
        end
        subgraph "老年代 Old Generation (2/3)"
            Old["Tenured / Old<br/>长期存活对象<br/>大对象"]
        end
    end

    Eden -->|"Minor GC 存活"| S0
    S0 -->|"年龄 +1，复制"| S1
    S1 -->|"年龄超阈值(15)"| Old
    Eden -->|"大对象 / 空间担保失败"| Old
```

### 2.1 对象流转

```mermaid
flowchart TD
    A["new 对象"] --> B{"大小 > PretenureSizeThreshold?"}
    B -->|是| C["直接进入老年代"]
    B -->|否| D["分配在 Eden 区"]
    D --> E{"Eden 满了？"}
    E -->|是| F["Minor GC"]
    F --> G{"对象存活？"}
    G -->|否| H["回收"]
    G -->|是| I{"年龄 ≥ MaxTenuringThreshold?"}
    I -->|是| C
    I -->|否| J["复制到 Survivor<br/>年龄 +1"]
    J --> K{"Survivor 区放得下？"}
    K -->|否| C
    K -->|是| L["等待下次 Minor GC"]
    L --> F
```

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-Xms` | 初始堆大小 | 物理内存 1/64 |
| `-Xmx` | 最大堆大小 | 物理内存 1/4 |
| `-Xmn` | 新生代大小 | 堆的 1/3 |
| `-XX:NewRatio` | 老年代:新生代 比例 | 2（老年代 2/3） |
| `-XX:SurvivorRatio` | Eden:S0 比例 | 8（Eden 80%，S0/S1 各 10%） |
| `-XX:MaxTenuringThreshold` | 晋升老年代年龄阈值 | 15（CMS 默认 6，G1 默认 15） |
| `-XX:PretenureSizeThreshold` | 大对象直接进入老年代阈值 | 0（不生效，需配合 UseSerialGC/ParNew） |

---

## 3. 虚拟机栈 & 栈帧

```mermaid
graph TB
    subgraph "Thread N 的虚拟机栈"
        direction TB
        Frame3["栈帧 3（栈顶）"]
        Frame2["栈帧 2"]
        Frame1["栈帧 1（栈底）"]
        FrameN["..."]
    end

    subgraph "栈帧内部结构"
        direction LR
        LV["局部变量表<br/>Local Variables<br/>参数 + 局部变量"]
        OS["操作数栈<br/>Operand Stack<br/>字节码指令执行"]
        DL["动态链接<br/>Dynamic Linking<br/>指向运行时常量池"]
        RA["返回地址<br/>Return Address<br/>方法返回后继续执行位置"]
    end

    Frame3 -.-> LV
    Frame3 -.-> OS
```

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-Xss` | 线程栈大小 | 1M（Linux x64） |

栈帧大小在编译期确定，不受运行时数据影响。

---

## 4. 方法区 → 元空间演变

```mermaid
flowchart LR
    subgraph "JDK 7 及以前"
        PermGen["永久代 PermGen<br/>属于堆内存<br/>-XX:MaxPermSize=256m<br/>存储：类信息、常量池、静态变量"]
    end
    subgraph "JDK 8+"
        Metaspace["元空间 Metaspace<br/>本地内存 Native Memory<br/>-XX:MaxMetaspaceSize<br/>存储：类元数据、方法信息"]
        Heap2["堆 Heap<br/>字符串常量池 + 静态变量<br/>（从 PermGen 移至堆）"]
    end

    PermGen -->|"JDK 8 移除"| Metaspace
```

| 参数 | 说明 |
|------|------|
| `-XX:MetaspaceSize` | 触发 Metaspace GC 的初始阈值 |
| `-XX:MaxMetaspaceSize` | 元空间最大大小（默认无上限） |
| `-XX:MinMetaspaceFreeRatio` | GC 后 Metaspace 最小空闲比例 |

---

## 5. 面试要点

- JVM 运行时数据区 5 大块及线程归属（私有 vs 共享）
- 堆分代结构（Eden:S0:S1 = 8:1:1）
- 对象晋升老年代的条件（年龄/大对象/空间担保）
- JDK 7 → JDK 8 方法区变化（PermGen → Metaspace + 字符串常量池转移）
- 栈帧四部分（局部变量表/操作数栈/动态链接/返回地址）
- StackOverflowError vs OOM 的区别