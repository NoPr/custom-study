# HBase RowKey 设计核心方案

## 1. 散列法 (MD5 前缀反转)

对原始 RowKey 做 MD5 哈希, 取前 N 个十六进制字符, 反转后拼接到原 RowKey 前面。

### 流程

```mermaid
graph LR
    A[原始 Key: user_10001] --> B[MD5 哈希]
    B --> C[取前4位: c4ca]
    C --> D[反转: ac4c]
    D --> E[RowKey: ac4c_user_10001]
    E --> F[字典序存储, 打散分布]
```

### 效果对比

| 原始 Key | MD5 前4位 | 反转 | 最终 RowKey |
|----------|-----------|------|-------------|
| user_10001 | c4ca | ac4c | ac4c_user_10001 |
| user_10002 | c81e | e18c | e18c_user_10002 |
| user_10003 | eccb | bcce | bcce_user_10003 |

相邻的 userId 被彻底打散, 写入分散到不同 Region。

---

## 2. 加盐法 (Salt)

在 RowKey 前拼接随机盐值, 将数据分散到 N 个 bucket。

```mermaid
graph TB
    subgraph "写入路径"
        W[数据写入] --> S[随机生成 salt=0~N-1]
        S --> R[RowKey = salt + '_' + originalKey]
    end
    subgraph "读取路径"
        Q[查询请求] --> L[遍历所有 bucket 前缀]
        L --> S1[Scan '0_']
        L --> S2[Scan '1_']
        L --> S3[Scan '2_']
        L --> S4[Scan '3_']
    end
```

**特点**: 查询放大 N 倍, 适合写多读少场景。

---

## 3. 反转法 (手机号)

将手机号反转后作为 RowKey, 手机号低位变高位, 提高前缀区分度。

```mermaid
graph LR
    A[原始手机号] --> B[反转]
    B --> C[RowKey]
    subgraph "原始: 前7位相同 → 同 Region"
        M1["13800001111"]
        M2["13800002222"]
        M3["13800003333"]
    end
    subgraph "反转: 高位不同 → 分散"
        R1["11110000831"]
        R2["22220000831"]
        R3["33330000831"]
    end
```

---

## 4. 时间戳反转防热点

针对时间序列数据, 反转时间戳避免单调递增带来的 Region 热点。

```mermaid
graph TB
    subgraph "错误做法: 裸时间戳"
        T1["t=1700000000 → user_01_1700000000"]
        T2["t=1700000100 → user_01_1700000100"]
        T3["t=1700000200 → user_01_1700000200"]
        T4["全部写入同一个 Region (尾部) → 热点!"]
        T1 --> T2 --> T3 --> T4
    end
    subgraph "正确做法: 反转时间戳"
        RT1["t=1700000000 → user_01_0000000071"]
        RT2["t=1700000100 → user_01_0000000081"]
        RT3["t=1700000200 → user_01_0000000091"]
        RT4["前缀不同 → 写入分散到不同 Region"]
        RT1 --> RT2 --> RT3 --> RT4
    end
```

**两种策略**: 字符串反转 `new StringBuilder(tsStr).reverse()` 或 `Long.MAX_VALUE - timestamp`。

---

## 5. 预分区设计

建表时通过 `splitKeys` 指定 Region 边界, 避免自动 Split。

```mermaid
graph LR
    subgraph "预分区设计 (splitKeys=['40','80','C0'])"
        R0["Region-0<br/>(-inf, 40]"]
        R1["Region-1<br/>(40, 80]"]
        R2["Region-2<br/>(80, C0]"]
        R3["Region-3<br/>(C0, +inf)"]
    end
```

建表命令:
```
create 'table_name', 'cf', SPLITS => ['40', '80', 'C0']
```

---

## RowKey 设计 5 大原则总结

| 原则 | 方法 | 原因 |
|------|------|------|
| 长度原则 | 50~100 字节 | RowKey 存储于每个 KeyValue, 过长增大 IO |
| 散列原则 | MD5/加盐/反转 | 均匀分布到各 Region, 避免热点 |
| 唯一原则 | RowKey 即主键 | 同一 RowKey 覆盖写 |
| 排序原则 | 字典序存储 | 高频查询条件放 RowKey 高位, 高效 Scan |
| 防单调递增 | 时间戳反转/加盐 | 防止写入集中在最后一个 Region |