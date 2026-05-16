package base.dolphinscheduler;

import java.util.*;

/**
 * DAG 编排引擎：手写 SimpleDAG 拓扑排序 + TaskNode 依赖 + 并行任务分组 + 条件分支 + 子工作流。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>DAG (Directed Acyclic Graph)</b>：有向无环图，工作流的底层数据结构，
 *       保证任务不会循环依赖</li>
 *   <li><b>Topological Sort (拓扑排序)</b>：Kahn 算法（BFS + 入度表），
 *       确定任务执行顺序，O(V+E) 时间复杂度</li>
 *   <li><b>并行任务分组</b>：同一层级（入度同时为 0）的任务可并行执行</li>
 *   <li><b>条件分支</b>：根据上游任务输出决定下游走哪个分支</li>
 *   <li><b>子工作流 (SubProcess)</b>：嵌套另一个 DAG 作为子任务</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。
 *
 * @author study-tuling
 */
public class DAGDemo {

    /* ==================== 1. 数据模型 ==================== */

    /** 任务节点 */
    static class TaskNode {
        final String name;
        final int durationMs;   // 模拟执行耗时(ms)
        final Set<TaskNode> dependencies = new LinkedHashSet<>(); // 上游依赖
        final Set<TaskNode> successors = new LinkedHashSet<>();   // 下游节点

        TaskNode(String name, int durationMs) {
            this.name = name;
            this.durationMs = durationMs;
        }
    }

    /** 条件分支节点：根据上游输出值选择下游分支 */
    static class ConditionNode extends TaskNode {
        final String conditionExpr;          // 条件表达式描述
        final Map<String, TaskNode> branches = new LinkedHashMap<>(); // 分支映射

        ConditionNode(String name, String conditionExpr) {
            super(name, 50);
            this.conditionExpr = conditionExpr;
        }

        /** 根据模拟的上游输出值选择分支 */
        TaskNode selectBranch(String upstreamOutput) {
            return branches.getOrDefault(upstreamOutput, branches.get("default"));
        }
    }

    /** 子工作流节点：嵌套另一个 DAG */
    static class SubProcessNode extends TaskNode {
        final SimpleDAG subDag;  // 嵌套的子 DAG

        SubProcessNode(String name, SimpleDAG subDag) {
            super(name, 300);
            this.subDag = subDag;
        }
    }

    /* ==================== 2. SimpleDAG 编排引擎 ==================== */

    static class SimpleDAG {
        final String workflowName;
        final List<TaskNode> allNodes = new ArrayList<>();
        final Map<String, TaskNode> nodeMap = new LinkedHashMap<>();

        SimpleDAG(String workflowName) {
            this.workflowName = workflowName;
        }

        void addNode(TaskNode node) {
            allNodes.add(node);
            nodeMap.put(node.name, node);
        }

        /** 添加依赖边：from 执行完后才能执行 to */
        void addEdge(String from, String to) {
            TaskNode fromNode = nodeMap.get(from);
            TaskNode toNode = nodeMap.get(to);
            assert fromNode != null && toNode != null : "节点不存在: " + from + " -> " + to;
            fromNode.successors.add(toNode);
            toNode.dependencies.add(fromNode);
        }

        /**
         * Kahn 拓扑排序：BFS + 入度表。
         *
         * <p>算法步骤：
         * <ol>
         *   <li>计算每个节点的入度（上游依赖数量）</li>
         *   <li>将所有入度为 0 的节点入队——这些是第一批可执行的节点</li>
         *   <li>出队执行，将其所有后继节点的入度减 1</li>
         *   <li>如果后继节点入度变为 0，入队——成为下一批可执行的节点</li>
         *   <li>重复直到队列为空</li>
         * </ol>
         *
         * <p>时间复杂度 O(V+E)，空间复杂度 O(V)，用于检测环和确定执行批次。
         *
         * @return 按层级分组的任务列表，每个内层 List 是一批可并行执行的任务
         */
        List<List<TaskNode>> topologicalSort() {
            // 1. 计算入度
            Map<TaskNode, Integer> inDegree = new LinkedHashMap<>();
            for (TaskNode node : allNodes) {
                inDegree.put(node, node.dependencies.size());
            }

            // 2. 入度为 0 的节点作为第一批
            Queue<TaskNode> zeroInDegreeQueue = new LinkedList<>();
            for (TaskNode node : allNodes) {
                if (inDegree.get(node) == 0) {
                    zeroInDegreeQueue.offer(node);
                }
            }

            // 3. BFS 分层输出（同一层可并行）
            List<List<TaskNode>> levels = new ArrayList<>();
            int processedCount = 0;

            while (!zeroInDegreeQueue.isEmpty()) {
                int levelSize = zeroInDegreeQueue.size();
                List<TaskNode> currentLevel = new ArrayList<>();

                for (int i = 0; i < levelSize; i++) {
                    TaskNode current = zeroInDegreeQueue.poll();
                    currentLevel.add(current);
                    processedCount++;

                    for (TaskNode successor : current.successors) {
                        int newDegree = inDegree.get(successor) - 1;
                        inDegree.put(successor, newDegree);
                        if (newDegree == 0) {
                            zeroInDegreeQueue.offer(successor);
                        }
                    }
                }
                levels.add(currentLevel);
            }

            // 4. 环检测：如果处理的节点数 < 总节点数，说明存在环
            if (processedCount != allNodes.size()) {
                throw new IllegalStateException(
                        "DAG 存在循环依赖！已处理 " + processedCount + "/" + allNodes.size() + " 个节点");
            }

            return levels;
        }

        /** 打印 DAG 结构（Mermaid 风格） */
        void printDAG() {
            System.out.println("\n--- DAG 结构: " + workflowName + " ---");
            for (TaskNode node : allNodes) {
                if (node.successors.isEmpty()) {
                    System.out.printf("  [%s] (叶子节点)%n", node.name);
                } else {
                    for (TaskNode successor : node.successors) {
                        System.out.printf("  [%s] --> [%s]%n", node.name, successor.name);
                    }
                }
            }
        }
    }

    /* ==================== 3. DAG 执行引擎 ==================== */

    /** 模拟执行单个任务 */
    static void executeTask(TaskNode node) {
        System.out.printf("  >>> 执行: %-20s (耗时 %dms)", node.name, node.durationMs);
        try {
            Thread.sleep(Math.min(node.durationMs, 100)); // demo 做时间压缩
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println(" [完成]");

        // 子工作流特殊处理
        if (node instanceof SubProcessNode subNode) {
            System.out.printf("    └─ 进入子工作流: %s%n", subNode.subDag.workflowName);
            executeDAG(subNode.subDag);
        }
    }

    /** 模拟并行执行同一批次的任务 */
    static void executeParallelLevel(List<TaskNode> level) {
        if (level.size() == 1) {
            executeTask(level.get(0));
        } else {
            System.out.printf("  *** 并行执行 %d 个任务 ***%n", level.size());
            List<Thread> threads = new ArrayList<>();
            for (TaskNode node : level) {
                Thread thread = new Thread(() -> executeTask(node), "worker-" + node.name);
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 执行整个 DAG */
    static void executeDAG(SimpleDAG dag) {
        System.out.println("\n========== 开始执行工作流: " + dag.workflowName + " ==========");

        List<List<TaskNode>> levels = dag.topologicalSort();
        System.out.printf("拓扑排序完成, 共 %d 层:%n", levels.size());
        for (int i = 0; i < levels.size(); i++) {
            List<String> names = levels.get(i).stream().map(n -> n.name).toList();
            System.out.printf("  Layer %d (并行度 %d): %s%n", i + 1, levels.get(i).size(), names);
        }

        // 逐层执行
        for (int i = 0; i < levels.size(); i++) {
            System.out.printf("%n--- 第 %d/%d 层执行 ---%n", i + 1, levels.size());
            List<TaskNode> currentLevel = levels.get(i);

            // 处理条件分支节点
            List<TaskNode> actualLevel = new ArrayList<>();
            for (TaskNode node : currentLevel) {
                if (node instanceof ConditionNode conditionNode) {
                    // 模拟上游输出决定分支
                    String simulatedOutput = "success"; // 模拟上游返回 success
                    TaskNode selectedBranch = conditionNode.selectBranch(simulatedOutput);
                    System.out.printf("  条件分支 [%s] 条件='%s' 上游输出='%s' -> 分支 [%s]%n",
                            conditionNode.name, conditionNode.conditionExpr,
                            simulatedOutput,
                            selectedBranch != null ? selectedBranch.name : "无(跳过)");
                    if (selectedBranch != null) {
                        actualLevel.add(selectedBranch);
                    }
                } else {
                    actualLevel.add(node);
                }
            }

            executeParallelLevel(actualLevel);
        }

        System.out.println("========== 工作流 " + dag.workflowName + " 执行完毕 ==========");
    }

    /* ==================== 4. Demo 场景 ==================== */

    /** 场景 1：标准 ETL DAG — 抽取 → 转换 → 加载 + 清洗并行 */
    static void demoETLWorkflow() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 1：标准 ETL 工作流（含并行清洗）");
        System.out.println("=".repeat(60));

        SimpleDAG etlDag = new SimpleDAG("ETL-数据集成");

        // 创建节点
        TaskNode extract   = new TaskNode("Extract-数据抽取", 200);
        TaskNode clean1    = new TaskNode("Clean-空值清洗", 150);
        TaskNode clean2    = new TaskNode("Clean-格式标准化", 120);
        TaskNode transform = new TaskNode("Transform-数据转换", 180);
        TaskNode load      = new TaskNode("Load-数据加载", 100);
        TaskNode report    = new TaskNode("Report-生成报告", 80);

        for (TaskNode node : List.of(extract, clean1, clean2, transform, load, report)) {
            etlDag.addNode(node);
        }

        // 添加依赖边
        etlDag.addEdge("Extract-数据抽取", "Clean-空值清洗");
        etlDag.addEdge("Extract-数据抽取", "Clean-格式标准化");
        etlDag.addEdge("Clean-空值清洗",   "Transform-数据转换");
        etlDag.addEdge("Clean-格式标准化", "Transform-数据转换");
        etlDag.addEdge("Transform-数据转换", "Load-数据加载");
        etlDag.addEdge("Load-数据加载",     "Report-生成报告");

        etlDag.printDAG();
        executeDAG(etlDag);
    }

    /** 场景 2：条件分支 — 数据质量检查后分支 */
    static void demoConditionBranch() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 2：条件分支工作流");
        System.out.println("=".repeat(60));

        SimpleDAG branchDag = new SimpleDAG("DataQuality-数据质量检查");

        TaskNode source     = new TaskNode("Source-数据源读取", 100);
        TaskNode quality    = new TaskNode("Quality-Check-质量检查", 80);
        TaskNode highBranch = new TaskNode("Branch-High-高质量处理", 120);
        TaskNode lowBranch  = new TaskNode("Branch-Low-低质量告警", 50);

        // 条件分支节点模拟
        ConditionNode conditionNode = new ConditionNode("Condition-Router-路由分支",
                "qualityScore > 0.8 ? highBranch : lowBranch");
        conditionNode.branches.put("success", highBranch);
        conditionNode.branches.put("default", lowBranch);

        branchDag.addNode(source);
        branchDag.addNode(quality);
        // 条件节点不入 DAG 的执行列表，而是作为调度逻辑的一部分
        // 这里添加分支目标节点
        branchDag.addNode(highBranch);
        branchDag.addNode(lowBranch);

        branchDag.addEdge("Source-数据源读取",   "Quality-Check-质量检查");
        // 条件节点的两个可能分支都提前注册在 DAG 中
        branchDag.addEdge("Quality-Check-质量检查", "Branch-High-高质量处理");
        // 注意：实际 DolphinScheduler 中，条件节点只会执行其中一个分支；
        // 这里两端都注册用于拓扑演示

        branchDag.printDAG();
        System.out.println("\n条件分支逻辑演示:");
        System.out.println("  条件: " + conditionNode.conditionExpr);
        System.out.println("  模拟 qualityScore=0.92 -> 走 Branch-High-高质量处理");
        TaskNode selected = conditionNode.selectBranch("success");
        System.out.println("  选中分支: " + (selected != null ? selected.name : "无"));

        System.out.println("  模拟 qualityScore=0.55 -> 走 Branch-Low-低质量告警");
        selected = conditionNode.selectBranch("fail");
        System.out.println("  选中分支: " + (selected != null ? selected.name : "无"));
    }

    /** 场景 3：子工作流嵌套 — 主流程嵌套子 DAG */
    static void demoSubProcess() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 3：子工作流 SubProcess 嵌套");
        System.out.println("=".repeat(60));

        // 子 DAG：数据预处理子流程
        SimpleDAG subDag = new SimpleDAG("Preprocess-数据预处理(子流程)");
        TaskNode sub1 = new TaskNode("Sub-去重", 50);
        TaskNode sub2 = new TaskNode("Sub-补齐缺失值", 60);
        TaskNode sub3 = new TaskNode("Sub-归一化", 40);
        subDag.addNode(sub1);
        subDag.addNode(sub2);
        subDag.addNode(sub3);
        subDag.addEdge("Sub-去重", "Sub-补齐缺失值");
        subDag.addEdge("Sub-补齐缺失值", "Sub-归一化");

        // 主 DAG：模型训练流程
        SimpleDAG mainDag = new SimpleDAG("ML-Training-模型训练");
        TaskNode dataLoad       = new TaskNode("DataLoad-加载数据集", 80);
        SubProcessNode preSub   = new SubProcessNode("Preprocess-数据预处理", subDag);
        TaskNode train           = new TaskNode("Train-模型训练", 200);
        TaskNode eval            = new TaskNode("Eval-模型评估", 100);

        mainDag.addNode(dataLoad);
        mainDag.addNode(preSub);
        mainDag.addNode(train);
        mainDag.addNode(eval);

        mainDag.addEdge("DataLoad-加载数据集", "Preprocess-数据预处理");
        mainDag.addEdge("Preprocess-数据预处理", "Train-模型训练");
        mainDag.addEdge("Train-模型训练", "Eval-模型评估");

        mainDag.printDAG();
        executeDAG(mainDag);
    }

    /** 场景 4：窄依赖 vs 宽依赖对比 */
    static void demoDependencyTypes() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 4：窄依赖 vs 宽依赖 -- 任务分区映射");
        System.out.println("=".repeat(60));

        SimpleDAG narrowDag = new SimpleDAG("Narrow-窄依赖(map)");
        TaskNode n1 = new TaskNode("N-Partition-0", 30);
        TaskNode n2 = new TaskNode("N-Partition-1", 30);
        TaskNode n3 = new TaskNode("N-Partition-2", 30);
        narrowDag.addNode(n1);
        narrowDag.addNode(n2);
        narrowDag.addNode(n3);
        System.out.println("窄依赖 DAG (无依赖边 = 各分区独立并行):");
        narrowDag.printDAG();
        System.out.println("  特征: 父 RDD 每个分区最多被一个子 RDD 分区使用 -> 无需 Shuffle, pipeline 执行");

        SimpleDAG wideDag = new SimpleDAG("Wide-宽依赖(groupByKey-like)");
        TaskNode w1 = new TaskNode("W-Source-A", 50);
        TaskNode w2 = new TaskNode("W-Source-B", 50);
        TaskNode w3Agg = new TaskNode("W-Aggregate-聚合", 100);
        wideDag.addNode(w1);
        wideDag.addNode(w2);
        wideDag.addNode(w3Agg);
        wideDag.addEdge("W-Source-A", "W-Aggregate-聚合");
        wideDag.addEdge("W-Source-B", "W-Aggregate-聚合");
        System.out.println("\n宽依赖 DAG (多父→1子 = 需要 shuffle 数据重分布):");
        wideDag.printDAG();
        System.out.println("  特征: 父 RDD 每个分区可能被多个子分区使用 -> 触发 Shuffle(磁盘+网络)");
    }

    /* ==================== 5. 环检测验证 ==================== */

    static void demoCycleDetection() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 5：环检测 -- DAG 的「有向无环」约束");
        System.out.println("=".repeat(60));

        SimpleDAG cyclicDag = new SimpleDAG("Cyclic-循环依赖(错误示例)");
        TaskNode a = new TaskNode("A", 50);
        TaskNode b = new TaskNode("B", 50);
        TaskNode c = new TaskNode("C", 50);
        cyclicDag.addNode(a);
        cyclicDag.addNode(b);
        cyclicDag.addNode(c);
        // 故意构造 A → B → C → A 循环
        cyclicDag.addEdge("A", "B");
        cyclicDag.addEdge("B", "C");
        cyclicDag.addEdge("C", "A"); // 回环！

        cyclicDag.printDAG();
        System.out.println("该图包含环 A→B→C→A，拓扑排序应检测到:");
        try {
            cyclicDag.topologicalSort();
            System.out.println("  [错误] 未检测到环！");
        } catch (IllegalStateException e) {
            System.out.println("  [正确] " + e.getMessage());
            System.out.println("  DolphinScheduler 同样禁止循环依赖，DAG 合法性校验会拒绝提交");
        }
    }

    /* ==================== 6. 主入口 ==================== */

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("    DolphinScheduler DAG 编排引擎核心原理演示");
        System.out.println("    覆盖: 拓扑排序 | 并行分组 | 条件分支 | 子工作流 | 环检测");
        System.out.println("=".repeat(60));

        demoETLWorkflow();
        demoConditionBranch();
        demoSubProcess();
        demoDependencyTypes();
        demoCycleDetection();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("    DAGDemo 全部演示完毕");
        System.out.println("=".repeat(60));
    }
}