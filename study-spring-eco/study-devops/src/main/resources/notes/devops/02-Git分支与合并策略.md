# 02-Git 分支与合并策略

## 三大工作流对比

```mermaid
graph TD
    subgraph GitFlow
        M1[master] --> D1[develop]
        D1 --> F1[feature/xxx]
        D1 --> R1[release/x.x]
        M1 --> H1[hotfix/xxx]
    end

    subgraph "GitHub Flow"
        M2[master/main] --> F2[feature/xxx]
        F2 -->|"PR Review"| M2
    end

    subgraph TrunkBased
        T[trunk] --> SF1[short-feature/1]
        T --> SF2[short-feature/2]
        SF1 -->|"≤1天合并"| T
        SF2 -->|"≤1天合并"| T
    end
```

| 策略 | 分支数量 | 适合场景 |
|------|---------|---------|
| GitFlow | 多 (master+develop+feature+release+hotfix) | 固定发版周期, 移动端/客户端 |
| GitHub Flow | 少 (master + feature) | SaaS/Web 持续部署 |
| TrunkBased | 极少 (trunk + 短分支) | CI/CD 成熟, 大型团队 |

---

## merge vs rebase vs cherry-pick

```mermaid
flowchart LR
    subgraph "merge 后"
        M1[a] --> M2[b] --> M3[c] --> M4["merge commit<br/>(3-way merge)"]
        M1 --> M5[d] --> M6[e] --> M4
    end

    subgraph "rebase 后"
        R1[a] --> R2[b] --> R3[c] --> R4["d'"] --> R5["e'"]
    end

    subgraph "cherry-pick"
        C1[a] --> C2[b] --> C3[c] --> C4["e' (仅e)"]
    end
```

| 操作 | 历史结果 | 黄金法则 |
|------|---------|---------|
| merge | 网状历史, 保留 merge commit | 公共分支用 merge |
| rebase | 线性历史, commit 被重写 | 推送过的分支严禁 rebase |
| cherry-pick | 复制单个 commit 到当前分支 | 跨分支迁移单个修复 |

---

## 冲突标记格式

```
<<<<<<< HEAD                    ← 当前分支 (ours)
public String getName() {
    return "Alice";            ← 我们的修改
}
=======                         ← 分隔线
public String getName() {
    return "Bob";              ← 合并分支的修改
}
>>>>>>> feature/user-manage    ← 合并源分支名
```

**解决方式:**
1. 手动编辑删除标记, 保留正确内容
2. `git checkout --ours file` -- 全用当前分支版本
3. `git checkout --theirs file` -- 全用合并分支版本
4. IDE 可视化合并工具 (VSCode/IDEA)

---

## reset 三种模式

```mermaid
flowchart LR
    subgraph "--soft"
        direction LR
        S1["HEAD 移动"] --> S2["暂存区不变"] --> S3["工作区不变"]
    end
    subgraph "--mixed (默认)"
        direction LR
        M1["HEAD 移动"] --> M2["暂存区重置"] --> M3["工作区不变"]
    end
    subgraph "--hard"
        direction LR
        H1["HEAD 移动"] --> H2["暂存区重置"] --> H3["工作区重置<br/>(危险!)"]
    end
```

| 模式 | HEAD | 暂存区 | 工作区 | 用途 |
|------|:--:|:--:|:--:|------|
| --soft | 移 | 不变 | 不变 | 撤销 commit, 保留修改重新提交 |
| --mixed | 移 | 重置 | 不变 | 撤销 commit+add, 修改回工作区 |
| --hard | 移 | 重置 | 重置 | 完全丢弃 (危险!) |

---

## reflog 误删恢复流程

```mermaid
flowchart TD
    A[误执行 git reset --hard] --> B[git reflog 查找丢失的 commit]
    B --> C{选择恢复方式}
    C -->|"直接回退"| D[git reset --hard 目标SHA]
    C -->|"新建分支"| E[git checkout -b recovery 目标SHA]
    C -->|"应用单commit"| F[git cherry-pick 目标SHA]
```

---

## 常用 Git 命令

| 命令 | 用途 |
|------|------|
| `git log --oneline --graph --all` | 可视化分支历史 |
| `git stash / git stash pop` | 暂存/恢复工作区 |
| `git rebase -i HEAD~3` | 交互式 rebase 合并最近 3 个 commit |
| `git cherry-pick abc123..def456` | 批量 cherry-pick |
| `git revert abc123` | 撤销某个 commit (生成新 commit) |
| `git reflog` | 查看 HEAD 变更历史 |
| `git bisect start / good / bad` | 二分法定位 bug 引入的 commit |