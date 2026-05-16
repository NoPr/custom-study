# HBase Region 拆分与 Compaction

## 1. Region Split 全流程

Region 拆分完整链路: 写入 MemStore -> Flush 生成 HFile -> Region 大小超阈值 -> 计算 Split Point -> 创建 Daughter Regions -> 更新 META 表。

```mermaid
graph TB
    A[数据写入 MemStore] --> B{MemStore >= 128MB?}
    B -->|是| C[Flush → HFile]
    B -->|否| A
    C --> D[Region 增长]
    D --> E{Region >= splitThreshold?}
    E -->|否| A
    E -->|是| F[计算 Split Point]
    F --> G[创建 Daughter-A]
    F --> H[创建 Daughter-B]
    G --> I[META 表: DELETE parent, PUT daughter-A, PUT daughter-B]
    H --> I
    I --> J[父 Region 下线]
    J --> K[子 Region 上线]
```

---

## 2. MemStore Flush 触发条件

| 触发级别 | 条件 | 说明 |
|----------|------|------|
| Region 级 | MemStore >= 128MB (flush.size) | 单个 Region 的 Store 的 MemStore 达到阈值 |
| RS 全局 | 全局 MemStore >= 堆 * 40% | 大到小依次 Flush, 直到低于 lowerLimit(95%) |
| WAL 上限 | WAL 文件数 > maxlogs(默认32) | Flush 最早的 MemStore 以清理 WAL |
| 手动触发 | flush 'table_name' | Shell/API 手动执行 |
| Region 关闭 | Split/Balance 前 | 下线前强制 Flush |

---

## 3. Split Policy 策略对比

```mermaid
graph TB
    subgraph "Split Policy"
        A[ConstantSize] --> |"Region >= max.filesize"| SP[Split]
        B[IncreasingToUpperBound] --> |"Min(size*2/N^3, max.filesize)"| SP
        C[KeyPrefix] --> |"按 RowKey prefix 分组"| SP
        D[DelimitedKeyPrefix] --> |"按分隔符取前缀分组"| SP
        E[Disabled] --> |"永不自动拆分"| NS[需手动 Split]
    end
```

### IncreasingToUpperBound (默认策略)

| Region 数 | 拆分阈值 (TB) |
|-----------|--------------|
| 1 | 1 |
| 2 | 2 |
| 4 | 4 |
| 8 | 6.4 |
| 16+ | max.filesize (10GB 默认) |

防止大表产生过多小 Region。

---

## 4. 拆分期间读写一致性 (HBase 2.0+ Procedure V2)

```mermaid
graph LR
    S1[PREPARE<br/>获取写锁] --> S2[SPLITTING<br/>父 Region Flush<br/>生成 reference HFile]
    S2 --> S3[SPLIT<br/>META 表添加<br/>子 Region 条目]
    S3 --> S4[SPLIT_COMPLETED<br/>父 Region 下线<br/>子 Region 上线]
```

- **读一致性**: 拆分期间读仍路由到父 Region。Client 缓存过期 -> NotServingRegionException -> 重试 META 定位
- **写一致性**: 父 Region close -> 子 Region open 短暂写阻塞 (毫秒级)。WAL 保证数据不丢

---

## 5. Compaction 机制

### Minor vs Major Compaction

```mermaid
graph TB
    subgraph "Minor Compaction (高频)"
        M1[选取少量相邻小 HFile] --> M2[合并为一个 HFile]
        M2 --> M3[不清理过期 + tombstone]
    end
    subgraph "Major Compaction (低频)"
        MA1[选取 Store 内所有 HFile] --> MA2[合并为一个 HFile]
        MA2 --> MA3[清理 TTL 过期 + tombstone<br/>回收磁盘空间]
    end
```

### 对比表

| 类型 | 频率 | 开销 | 清理删除标记 | 适用时机 |
|------|------|------|-------------|---------|
| Minor | 高频 (秒~分钟) | 低 | 否 | 持续后台运行 |
| Major | 低频 (天~周, 默认7天) | 高 | 是 | 低峰期手动触发 |

### Compaction IO 优化

- **Throttling**: 限速, 避免影响在线读写
- **Stripe Compaction** (HBase 2.0+): 按 RowKey 范围分组合并, 减少单次开销
- **Date Tiered Compaction**: 按时序分层合并, 适合时间序列数据

---

## 6. LSM-Tree 写入模型

```mermaid
graph TB
    W[写入请求] --> WAL[WAL 写日志<br/>故障恢复]
    WAL --> MS[MemStore<br/>内存跳表, 有序]
    MS --> |"满(128MB)"| FL[Flush<br/>磁盘顺序写]
    FL --> HF[HFile<br/>不可变文件]
    HF --> |"HFile 数量 > 阈值"| MC[Minor Compaction]
    MC --> |"定期(7天)"| MJC[Major Compaction<br/>清理过期 + tombstone]
```

**写入快**: 顺序写磁盘 (WAL + HFile), 内存跳表结构
**读需优化**: 数据分散在 MemStore + 多 HFile -> BloomFilter + BlockCache