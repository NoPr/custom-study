package base.devops;

import java.util.*;

/**
 * Git 版本控制核心概念大合集: 工作流 + merge/rebase/cherry-pick + 冲突解决 + reset + reflog
 *
 * <p>Git 三大工作流:
 * GitFlow: master + develop + feature/release/hotfix 分支, 适合固定发版周期
 * GitHub Flow: master + feature 分支, PR 审查后合并, 适合持续部署
 * TrunkBased: 所有人往 trunk(master) 提交, 短生命周期分支, 适合 CI/CD 成熟团队
 *
 * <p>merge vs rebase:
 * merge: 创建 merge commit, 保留分支历史, 3-way merge 算法
 * rebase: 将 feature 的提交"搬"到 master 最新提交之后, 线性历史
 * cherry-pick: 将指定 commit 的变更应用到当前分支
 *
 * <p>reset:
 * --soft: 仅移动 HEAD, 不改变暂存区和工作区
 * --mixed(默认): 移动 HEAD + 重置暂存区, 保留工作区
 * --hard: 移动 HEAD + 重置暂存区 + 覆盖工作区 (危险!)
 *
 * <p>reflog: 记录 HEAD 所有变更历史, 可用于恢复误删分支/误 reset 的提交
 */
public class GitDemo {

    /** Git 三大工作流对比 */
    static void printWorkflowComparison() {
        System.out.println("=== Git 工作流对比: GitFlow vs GitHub Flow vs TrunkBased ===");
        String fmt = "| %-16s | %-26s | %-26s | %-26s |%n";
        System.out.printf(fmt, "特性", "GitFlow", "GitHub Flow", "TrunkBased");
        System.out.println("|------------------|----------------------------|----------------------------|----------------------------|");
        System.out.printf(fmt, "分支数量", "master+develop+feature+...+hotfix", "master + feature", "trunk(master) + 短分支");
        System.out.printf(fmt, "主分支", "master (生产), develop (开发)", "master (随时可部署)", "trunk (唯一主干)");
        System.out.printf(fmt, "feature 生命周期", "较长, 可能跨版本", "PR 审查后合并删除", "≤1天, 频繁合并");
        System.out.printf(fmt, "发布方式", "release 分支 → master+develop", "master 打 tag 即发布", "trunk 打 tag 发布");
        System.out.printf(fmt, "hotfix", "从 master 拉 hotfix 分支", "从 master 拉修复分支", "从 trunk 拉修复分支");
        System.out.printf(fmt, "适合场景", "固定发版, 移动端/客户端", "SaaS/Web 持续部署", "CI/CD 成熟, 大型团队");
        System.out.printf(fmt, "历史可读性", "复杂, 网状历史", "线性的 PR 合并记录", "极简, 线性历史");
        System.out.printf(fmt, "回滚难度", "中等(revert merge)", "简单(revert PR)", "简单(revert commit)");
    }

    /** merge 三步合并 (3-way merge) 算法模拟 */
    static void simulateThreeWayMerge() {
        System.out.println("\n=== 3-Way Merge 算法模拟 ===");
        System.out.println("Git 合并时比较三个版本:");
        System.out.println("  BASE (共同祖先):   line = \"Hello World\"");
        System.out.println("  OURS (当前分支):   line = \"Hello Java\"");
        System.out.println("  THEIRS (合并分支):  line = \"Hello World v2\"");
        System.out.println();
        System.out.println("合并决策:");
        System.out.println("  OURS == THEIRS    → 任意选 (未冲突)");
        System.out.println("  OURS != BASE, THEIRS == BASE  → 选 OURS (仅我们改了)");
        System.out.println("  OURS == BASE, THEIRS != BASE  → 选 THEIRS (仅他们改了)");
        System.out.println("  OURS != BASE, THEIRS != BASE, OURS != THEIRS → 冲突!");

        class ThreeWayMerge {
            String base;
            String ours;
            String theirs;

            ThreeWayMerge(String base, String ours, String theirs) {
                this.base = base;
                this.ours = ours;
                this.theirs = theirs;
            }

            String resolve() {
                if (ours.equals(theirs)) {
                    return ours + " ← 无冲突";
                }
                if (!ours.equals(base) && theirs.equals(base)) {
                    return ours + " ← 取 OURS";
                }
                if (ours.equals(base) && !theirs.equals(base)) {
                    return theirs + " ← 取 THEIRS";
                }
                if (!ours.equals(base) && !theirs.equals(base)) {
                    return "<<<<<<< OURS\n" + ours + "\n=======\n" + theirs + "\n>>>>>>> THEIRS ← 冲突!";
                }
                return base;
            }
        }

        ThreeWayMerge merge1 = new ThreeWayMerge("Hello World", "Hello World", "Hello World");
        System.out.println("\n案例1 (相同): " + merge1.resolve());

        ThreeWayMerge merge2 = new ThreeWayMerge("Hello World", "Hello Java", "Hello World");
        System.out.println("\n案例2 (仅OURS改): " + merge2.resolve());

        ThreeWayMerge merge3 = new ThreeWayMerge("Hello World", "Hello World", "Hello Maven");
        System.out.println("\n案例3 (仅THEIRS改): " + merge3.resolve());

        ThreeWayMerge merge4 = new ThreeWayMerge("Hello World", "Hello Java", "Hello Maven");
        System.out.println("\n案例4 (双方都改→冲突):\n" + merge4.resolve());
    }

    /** merge vs rebase vs cherry-pick 对比 */
    static void printMergeRebaseCherryPick() {
        System.out.println("\n=== merge vs rebase vs cherry-pick ===");
        String fmt = "| %-14s | %-30s | %-30s | %-30s |%n";
        System.out.printf(fmt, "特性", "merge", "rebase", "cherry-pick");
        System.out.println("|----------------|--------------------------------|--------------------------------|--------------------------------|");
        System.out.printf(fmt, "操作对象", "合并整个分支", "将当前分支移到目标后", "单个/多个 commit");
        System.out.printf(fmt, "历史结果", "产生 merge commit, 网状", "线性历史, 无分叉", "复制 commit 到当前分支");
        System.out.printf(fmt, "哈希值", "原 commit 不变", "应用 rebase 的 commit 重写", "新 commit 新哈希");
        System.out.printf(fmt, "冲突处理", "一次性解决", "每个 commit 逐次解决", "每个 pick 单独处理");
        System.out.printf(fmt, "回滚难度", "git revert -m 1", "硬回滚, 历史已改写", "git revert 新 commit");
        System.out.printf(fmt, "黄金法则", "公共分支用 merge", "推送过的分支严禁 rebase", "跨分支迁移单个修复");
        System.out.printf(fmt, "典型命令", "git merge feature", "git rebase master", "git cherry-pick abc123");
    }

    /** 冲突标记模拟 */
    static void simulateConflictMarkers() {
        System.out.println("\n=== Git 冲突标记格式 ===");
        String conflictExample =
                "文件冲突时的内容:\n" +
                "  <<<<<<< HEAD                  ← 当前分支 (ours) 的内容\n" +
                "  public String getName() {\n" +
                "      return \"Alice\";          ← 我们改的\n" +
                "  }\n" +
                "  =======                       ← 分隔线\n" +
                "  public String getName() {\n" +
                "      return \"Bob\";            ← 合并进来的分支改的\n" +
                "  }\n" +
                "  >>>>>>> feature/user-manage   ← 合并源分支名\n" +
                "\n解决方式:\n" +
                "  1. 手动编辑: 删除 <<<<<< / ====== / >>>>>> 标记, 保留正确内容\n" +
                "  2. git checkout --ours file    → 全用当前分支版本\n" +
                "  3. git checkout --theirs file  → 全用合并分支版本\n" +
                "  4. IDE 可视化合并工具 (VSCode/IDEA 内建)";
        System.out.println(conflictExample);
    }

    /** reset 三种模式对比 */
    static void printResetModes() {
        System.out.println("\n=== git reset 三种模式对比 ===");
        String fmt = "| %-16s | %-14s | %-14s | %-14s | %-30s |%n";
        System.out.printf(fmt, "模式", "HEAD 位置", "暂存区(Index)", "工作区", "用途");
        System.out.println("|------------------|----------------|----------------|----------------|--------------------------------|");
        System.out.printf(fmt, "--soft", "移动到指定 commit", "不变", "不变", "撤销 commit 但保留修改, 重新提交");
        System.out.printf(fmt, "--mixed (默认)", "移动到指定 commit", "重置为 HEAD", "不变", "撤销 commit+add, 修改回工作区");
        System.out.printf(fmt, "--hard", "移动到指定 commit", "重置为 HEAD", "重置为 HEAD", "完全丢弃, 危险! 数据可能丢失");
        System.out.printf(fmt, "--keep", "移动到指定 commit", "重置为 HEAD", "保留与 HEAD 不同的", "类似 hard 但保留本地修改");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  git reset --soft HEAD~1   ← 撤销最近1次commit, 修改保留在暂存区");
        System.out.println("  git reset --mixed HEAD~1  ← 撤销最近1次commit+add, 修改回到工作区");
        System.out.println("  git reset --hard HEAD~1   ← 彻底删除最近1次commit及其修改");
        System.out.println("  git reset --hard origin/master ← 强制与远程同步");
    }

    /** reflog 误删恢复 */
    static void simulateReflogRecovery() {
        System.out.println("\n=== reflog 误删恢复流程 ===");
        System.out.println("场景: 误执行 git reset --hard 丢失了重要的 commit");
        System.out.println();
        System.out.println("恢复步骤:");
        System.out.println("  1. git reflog");
        System.out.println("     abc1234 HEAD@{0}: reset: moving to HEAD~3");
        System.out.println("     def5678 HEAD@{1}: commit: 重要功能-A");
        System.out.println("     ghi9012 HEAD@{2}: commit: 重要功能-B");
        System.out.println();
        System.out.println("  2. 找到丢失的 commit 哈希 (def5678)");
        System.out.println("  可选恢复方式:");
        System.out.println("    a) git reset --hard def5678      ← 直接回到该 commit");
        System.out.println("    b) git checkout -b recovery def5678 ← 创建新分支指向该 commit");
        System.out.println("    c) git cherry-pick def5678       ← 将该 commit 应用到当前分支");
        System.out.println();
        System.out.println("  3. 恢复已删除的分支:");
        System.out.println("     git reflog --all | grep feature");
        System.out.println("     找到该分支最后的 commit, git checkout -b feature-xxx <sha>");
    }

    /** 常用 Git 命令速查 */
    static void printCommonCommands() {
        System.out.println("\n=== 常用 Git 命令速查 ===");
        System.out.println("git log --oneline --graph --all   — 可视化分支历史");
        System.out.println("git stash / git stash pop         — 暂存/恢复工作区");
        System.out.println("git rebase -i HEAD~3              — 交互式 rebase 合并最近3个commit");
        System.out.println("git cherry-pick abc123..def456    — 批量 cherry-pick 一个范围");
        System.out.println("git revert abc123                 — 撤销某个 commit (生成新commit)");
        System.out.println("git reflog                        — 查看 HEAD 变更历史");
        System.out.println("git diff HEAD~1                   — 与上一次 commit 的差异");
        System.out.println("git blame file.java               — 查看文件每行的修改人和 commit");
        System.out.println("git bisect start / good / bad     — 二分法定位 bug 引入的 commit");
    }

    public static void main(String[] args) {
        printWorkflowComparison();
        simulateThreeWayMerge();
        printMergeRebaseCherryPick();
        simulateConflictMarkers();
        printResetModes();
        simulateReflogRecovery();
        printCommonCommands();
    }
}