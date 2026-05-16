# Seata 分布式事务

> 对应 Java Demo：[SeataDemo.java](../../../java/base/spring/alibaba/SeataDemo.java)

---

## 一、AT 模式 undolog 提交时序图

```mermaid
sequenceDiagram
    participant TM as Transaction Manager
    participant TC as Transaction Coordinator
    participant RM1 as Resource Manager 1 (DB1)
    participant RM2 as Resource Manager 2 (DB2)

    TM->>TC: 1. 开启全局事务 → XID
    TC-->>TM: XID = abc123

    Note over TM: @GlobalTransactional 方法体开始执行

    TM->>RM1: 2. 分支事务1: UPDATE account SET balance=balance-100
    RM1->>RM1: 执行业务 SQL
    RM1->>RM1: 记录 undolog (before-image)
    RM1->>RM1: 获取全局锁 (account:1)
    RM1->>TC: 3. 注册分支事务: XID=abc123, Branch=B1
    TC-->>RM1: 注册成功

    TM->>RM2: 4. 分支事务2: UPDATE account SET balance=balance+100
    RM2->>RM2: 执行业务 SQL
    RM2->>RM2: 记录 undolog (before-image)
    RM2->>RM2: 获取全局锁 (account:2)
    RM2->>TC: 5. 注册分支事务: XID=abc123, Branch=B2
    TC-->>RM2: 注册成功

    TM->>TC: 6. 全局提交 XID=abc123

    rect rgb(220, 255, 220)
        Note over TC,RM2: 二阶段提交
        TC->>RM1: 7. 分支提交 Branch=B1
        RM1->>RM1: 删除 undolog
        RM1->>RM1: 释放全局锁
        RM1-->>TC: OK

        TC->>RM2: 8. 分支提交 Branch=B2
        RM2->>RM2: 删除 undolog
        RM2->>RM2: 释放全局锁
        RM2-->>TC: OK
    end

    TC-->>TM: 全局事务提交完成
```

**undolog 内容**：
- `beforeImage`：修改前的行数据（用于回滚还原）
- `afterImage`：修改后的行数据（用于回滚时校验，防止脏写）

**全局锁的作用**：在 AT 模式一阶段到二阶段之间，防止其他事务修改同一行数据。

---

## 二、TCC 三阶段图

```mermaid
sequenceDiagram
    participant TM as Transaction Manager
    participant TC as TC
    participant RM1 as RM1 (Try/Confirm/Cancel)
    participant RM2 as RM2 (Try/Confirm/Cancel)

    TM->>TC: 开启全局事务

    rect rgb(255, 240, 220)
        Note over TM,RM2: Try 阶段（资源预留）
        TM->>RM1: Try: 冻结账户1的 100 元
        RM1-->>TM: 冻结成功
        TM->>RM2: Try: 冻结账户2的 100 元
        RM2-->>TM: 冻结成功
    end

    alt 所有 Try 成功
        rect rgb(220, 255, 220)
            Note over TM,RM2: Confirm 阶段（确认执行）
            TM->>TC: 全局提交
            TC->>RM1: Confirm: 解冻并扣减账户1
            TC->>RM2: Confirm: 解冻并扣减账户2
        end
    else 任一 Try 失败
        rect rgb(255, 220, 220)
            Note over TM,RM2: Cancel 阶段（取消回滚）
            TM->>TC: 全局回滚
            TC->>RM1: Cancel: 解冻并恢复余额
            TC->>RM2: Cancel: 解冻并恢复余额
        end
    end
```

**TCC 三阶段职责**：

| 阶段 | 职责 | 示例（转账） |
|------|------|------------|
| Try | 资源预留/锁定 | 冻结 A 账户 100 元 |
| Confirm | 确认执行 | 扣减 A 账户，增加 B 账户 |
| Cancel | 取消回滚 | 解冻 A 账户 100 元 |

**空回滚**：Try 未执行（网络超时等），TC 发起 Cancel，RM 发现无 Try 记录，直接返回成功。

**悬挂**：Cancel 先于 Try 到达，RM 需要记录 Cancel 操作，拒绝后续到达的 Try。

---

## 三、TC/TM/RM 交互时序图

```mermaid
sequenceDiagram
    participant App as 业务应用 (@GlobalTransactional)
    participant TM as Transaction Manager
    participant TC as Transaction Coordinator
    participant RM_A as RM-A (库存服务)
    participant RM_B as RM-B (订单服务)

    App->>TM: 调用入口方法
    TM->>TC: beginGlobalTransaction()
    TC-->>TM: XID = "abc123"

    App->>RM_A: 扣减库存 (携带 XID)
    RM_A->>TC: registerBranch(XID, "stock-branch")
    RM_A->>RM_A: 一阶段: 执行 SQL + undolog
    RM_A-->>App: 扣减成功

    App->>RM_B: 创建订单 (携带 XID)
    RM_B->>TC: registerBranch(XID, "order-branch")
    RM_B->>RM_B: 一阶段: 执行 SQL + undolog
    RM_B-->>App: 创建成功

    TM->>TC: commitGlobal(XID)
    TC->>RM_A: 二阶段: 分支提交
    TC->>RM_B: 二阶段: 分支提交
    TC-->>TM: 全局事务完成
```

---

## 四、四种模式对比

| 维度 | AT | TCC | Saga | XA |
|------|-----|-----|------|-----|
| **侵入性** | 无（自动代理） | 高（三接口） | 中（补偿接口） | 无（自动） |
| **一致性** | 最终一致 | 最终一致 | 最终一致 | 强一致（2PC） |
| **性能** | 高 | 高 | 高 | 低（锁资源） |
| **回滚方式** | undolog 自动 | Cancel 手动 | 补偿正向操作 | 自动 |
| **适用场景** | 通用 CRUD | 金额/库存/积分 | 长事务 (>10s) | 传统事务数据库 |
| **全局锁** | 有（防脏写） | 无 | 无 | 数据库行锁 |
| **空回滚处理** | 自动 | 需自行处理 | 需自行处理 | 自动 |

> 推荐顺序：AT > TCC > Saga > XA（AT 优先，侵入性最低，90% 场景满足需求）