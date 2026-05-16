# Sentinel 流控与熔断降级

> 对应 Java Demo：[SentinelDemo.java](../../../java/base/spring/alibaba/SentinelDemo.java)

---

## 一、滑动窗口结构图

```mermaid
graph TB
    subgraph "滑动窗口 (LeapArray) - 1s 窗口, 2 个 Bucket"
        direction LR
        B0["Bucket 0<br/>[0ms ~ 500ms)<br/>pass: 3<br/>block: 1<br/>---<br/>当前时间落在 B0"]
        B1["Bucket 1<br/>[500ms ~ 1000ms)<br/>pass: 2<br/>block: 0<br/>---<br/>即将过期"]
    end

    subgraph "QPS 计算逻辑"
        CALC["totalPass = B0.pass + B1.pass = 5<br/>QPS = 5/s<br/>当前时间戳 = 350ms"]
    end

    B0 --> CALC
    B1 --> CALC

    subgraph "Bucket 轮换策略"
        SWITCH["当前时间 > Bucket 起始 + 窗口长度<br/>→ 清零该 Bucket + 更新起始时间"]
    end
```

**核心要点**：
- 每个 Bucket 独立统计 `pass` / `block` / `complete` / `error` / `rt`
- 滑动窗口随时间推移不断轮换过期的 Bucket
- N Bucket 覆盖 N * bucketSizeMs 的时间范围
- QPS = 当前窗口内所有有效 Bucket 的 pass 之和

---

## 二、熔断状态流转图

```mermaid
stateDiagram-v2
    [*] --> CLOSED: 初始状态

    state CLOSED {
        [*] --> 统计中: 正常通过请求
        统计中 --> 触发熔断: badRatio >= threshold
    }

    CLOSED --> OPEN: 触发熔断条件

    state OPEN {
        [*] --> 拒绝请求: 直接返回 BlockException
        拒绝请求 --> 等待恢复: 经过 timeWindow
    }

    OPEN --> HALF_OPEN: 经过 timeWindow ms

    state HALF_OPEN {
        [*] --> 探测请求: 允许少量请求通过
        探测请求 --> 探测成功: 探测期间全部正常
        探测请求 --> 探测失败: 探测期间出现异常
    }

    HALF_OPEN --> CLOSED: 探测成功 (minRequestAmount 次全部正常)
    HALF_OPEN --> OPEN: 探测失败 (再次触发熔断)

    note right of OPEN
        timeWindow 默认 10s
        可通过 DegradeRule 配置
    end note

    note right of HALF_OPEN
        探测请求数 = minRequestAmount
        Sentinel 默认为 5
    end note
```

---

## 三、限流算法对比

```mermaid
graph LR
    subgraph "漏桶算法 (Leaky Bucket)"
        direction TB
        REQ1["请求1"] --> LB["漏桶<br/>(固定容量)"]
        REQ2["请求2"] --> LB
        REQ3["请求3"] --> LB
        LB --> OUT["以恒定速率流出<br/>→ 平滑流量"]
        OVERFLOW["超出容量 → 丢弃"]
        LB --> OVERFLOW
    end

    subgraph "令牌桶算法 (Token Bucket)"
        direction TB
        TOKEN["定时生成令牌<br/>(固定速率)"] --> TB["令牌桶<br/>(最大容量 N)"]
        REQ_A["请求A"] --> CHECK{"桶中有令牌?"}
        CHECK -->|"是"| PASS["通过"]
        CHECK -->|"否"| BLOCK["阻塞/拒绝"]
        TB --> CHECK
    end

    subgraph "Sentinel 滑动窗口"
        direction TB
        W["滑动窗口计数器<br/>统计最近 1s 的通过数"] --> JUDGE{"passCount >= threshold?"}
        JUDGE -->|"是"| BLOCK2["BlockHandler 降级"]
        JUDGE -->|"否"| PASS2["放行"]
    end
```

| 算法 | 流量整形 | 突发处理 | Sentinel 使用场景 |
|------|---------|---------|------------------|
| 漏桶 | 恒定速率流出 | 不允许突发 | WarmUp 预热模式 |
| 令牌桶 | 允许突发 (桶中积累) | 允许 | 排队等待模式 |
| 滑动窗口 | 固定窗口内计数 | 不允许 | 默认 QPS 限流 |

---

## 四、blockHandler vs fallback 区别

| 维度 | blockHandler | fallback |
|------|-------------|----------|
| 触发条件 | 限流/熔断 (BlockException) | 业务异常 (任意 Throwable) |
| 参数要求 | 必须包含 BlockException | 必须包含 Throwable (可选) |
| 是否统计 | 被限流的请求不计入熔断统计 | 异常请求计入熔断统计 |
| 优先级 | 先于 fallback 执行 | 后于 blockHandler 执行 |
| 配置示例 | `@SentinelResource(blockHandler="xxx")` | `@SentinelResource(fallback="xxx")` |

**同时配置时的执行顺序**：
1. 先检查限流/熔断规则 --> 触发 `blockHandler`
2. 通过规则检查 --> 执行业务方法
3. 业务方法抛异常 --> 触发 `fallback`