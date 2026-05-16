# 02-SQL 优化实战

## OR -> UNION ALL 改写流程

```mermaid
flowchart TD
    START["原始 SQL: WHERE a=? OR b=?"]
    PARSER["MySQL 优化器分析"]
    CAN_USE_INDEX{"a 和 b 都有索引?"}
    ORIGINAL["执行计划: type=index_merge<br/>(index_merge 合并两个索引结果)"]
    NO_INDEX["type=ALL 全表扫描<br/>OR 导致无法使用单列索引"]
    UNION_REWRITE["改写: WHERE a=? UNION ALL WHERE b=?"]
    RESULT1["子查询1: 走 idx_a, type=ref"]
    RESULT2["子查询2: 走 idx_b, type=ref"]
    FINAL["UNION ALL 合并结果，去重"]

    START --> PARSER
    PARSER --> CAN_USE_INDEX
    CAN_USE_INDEX -->|YES| ORIGINAL
    CAN_USE_INDEX -->|NO| NO_INDEX
    NO_INDEX --> UNION_REWRITE
    UNION_REWRITE --> RESULT1
    UNION_REWRITE --> RESULT2
    RESULT1 --> FINAL
    RESULT2 --> FINAL
    ORIGINAL --> FINAL

    style NO_INDEX fill:#ffcdd2
    style UNION_REWRITE fill:#c8e6c9
```

### OR 不走索引的原因

```
WHERE name='Alice' OR age=25

B+Tree 索引结构:
  idx_name: 按 name 排序，无法定位 age=25
  idx_age:  按 age 排序，无法定位 name='Alice'

  OR 条件要求结果集是两个条件的并集
  单个索引无法同时满足两个维度的等值查找
  结果: 优化器放弃索引，走全表扫描

改写为 UNION ALL:
  (SELECT * FROM t WHERE name='Alice')  -- 走 idx_name
  UNION ALL
  (SELECT * FROM t WHERE age=25)        -- 走 idx_age
  每个子查询独立使用自己的最优索引
```

## 子查询 -> JOIN 改写

```mermaid
flowchart LR
    subgraph "子查询 (MySQL 5.5 之前)"
        S1["外层: SELECT * FROM orders<br/>WHERE user_id IN<br/>(子查询结果)"]
        S2["子查询: SELECT user_id FROM users<br/>WHERE age>18<br/>(每行执行一次)"]
        S1 --> S2
    end

    subgraph "JOIN 改写 (推荐)"
        J1["SELECT o.* FROM orders o<br/>JOIN users u<br/>ON o.user_id = u.user_id<br/>WHERE u.age>18"]
        J2["一次扫描 users, 一次扫描 orders<br/>Hash Join / Index Nested Loop"]
    end

    S1 -.->|改写| J1
```

| 对比 | 子查询 (IN) | JOIN 改写 |
|------|------------|-----------|
| 执行方式 | 外层每行执行子查询 | 两表关联一次扫描 |
| 驱动表 | 不可控 | 小表驱动大表 |
| 索引利用 | 子查询内部可能走索引 | 关联字段可用索引 |
| MySQL 5.6+ | 半连接优化(semi-join) | 已有优化，但仍推荐 JOIN |

## ORDER BY + LIMIT 优化

```mermaid
sequenceDiagram
    participant App as Application
    participant MySQL as MySQL Server
    participant Engine as Storage Engine
    participant Idx as 索引(idx_ct)

    Note over App,Idx: 无索引排序 (filesort)
    App->>MySQL: SELECT * FROM t ORDER BY ct DESC LIMIT 10
    MySQL->>Engine: 全表扫描
    Engine-->>MySQL: 100万行数据
    MySQL->>MySQL: 排序 100万行 (filesort)
    MySQL-->>App: 返回前 10 行

    Note over App,Idx: 有索引排序 (Using index)
    App->>MySQL: SELECT * FROM t ORDER BY ct DESC LIMIT 10
    MySQL->>Idx: 从索引尾部读 10 行
    Idx-->>MySQL: 10 行 (索引有序)
    MySQL->>Engine: 回表取完整数据
    Engine-->>MySQL: 10 行
    MySQL-->>App: 返回 10 行
```

### filesort vs index 排序对比

| 场景 | Extra 信息 | 排序方式 | IO | 代价 |
|------|-----------|---------|-----|------|
| ORDER BY non_index_col | Using filesort | 内存/磁盘排序 | 全表扫描 | O(N log N) |
| ORDER BY indexed_col | Using index | 索引顺序读 | 索引扫描 + 回表 | O(log N + K) |
| WHERE a=? ORDER BY b | Using filesort | 过滤后排序 | 索引 + 排序 | O(M log M) |
| INDEX(a,b), WHERE a=? ORDER BY b | - | 索引有序 | 索引扫描 | O(log N + M) |

### filesort 内存阈值

```
sort_buffer_size (默认 256KB):
  - 排序数据 < sort_buffer_size: 内存排序(快)
  - 排序数据 > sort_buffer_size: 磁盘临时文件排序(慢)

max_length_for_sort_data (默认 1024字节):
  - 单行 < 该值: 全字段排序(一次回表)
  - 单行 > 该值: rowid 排序(两次回表)
```

## SELECT * 改写

| 问题 | 说明 |
|------|------|
| 网络开销 | 返回不需要的列，增加网络传输 |
| 覆盖索引失效 | 即使索引覆盖所有 WHERE 列，SELECT * 仍需回表 |
| 内存消耗 | 结果集在 Server 层占更多内存 |
| JOIN 膨胀 | JOIN 结果集列数 = 所有表列之和 |
| 表结构变更影响 | 新增列可能导致程序 OOM |