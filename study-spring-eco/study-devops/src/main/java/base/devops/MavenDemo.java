package base.devops;

import java.util.*;

/**
 * Maven 构建工具核心概念大合集: 生命周期 + 依赖管理 + scope + 冲突解决 + 多模块聚合
 *
 * <p>Maven 生命周期 (lifecycle) 由 phases 组成, 每个 phase 绑定 plugin goals:
 * clean → validate → compile → test → package → verify → install → deploy
 *
 * <p>dependencyManagement vs dependencies:
 * dependencyManagement 只声明版本号, 不实际引入;
 * dependencies 实际引入 jar, 子模块继承父 POM 的 dependencyManagement 版本约束.
 *
 * <p>scope 控制依赖传递范围:
 * compile (默认): 编译/测试/运行 全阶段可用, 传递
 * provided: 编译/测试可用, 运行由容器提供 (如 servlet-api), 不传递
 * runtime: 测试/运行可用, 编译不需要 (如 JDBC 驱动), 传递
 * test: 仅测试可用 (如 JUnit), 不传递
 *
 * <p>依赖冲突解决三原则: 最短路径优先 → 最先声明优先 → 手动 exclusions
 */
public class MavenDemo {

    /** Maven 生命周期各阶段及对应操作 */
    static class MavenLifecycle {
        String phase;
        String description;
        String typicalGoal;

        MavenLifecycle(String phase, String description, String typicalGoal) {
            this.phase = phase;
            this.description = description;
            this.typicalGoal = typicalGoal;
        }
    }

    static void printLifecycleTable() {
        System.out.println("=== Maven 生命周期 (Lifecycle Phases) ===");
        List<MavenLifecycle> phases = List.of(
                new MavenLifecycle("clean", "清理 target 目录", "maven-clean-plugin:clean"),
                new MavenLifecycle("validate", "验证项目结构完整性", "—"),
                new MavenLifecycle("compile", "编译 src/main/java", "maven-compiler-plugin:compile"),
                new MavenLifecycle("test", "运行 src/test/java 单测", "maven-surefire-plugin:test"),
                new MavenLifecycle("package", "打包为 jar/war", "maven-jar-plugin:jar"),
                new MavenLifecycle("verify", "集成测试验证", "maven-failsafe-plugin:verify"),
                new MavenLifecycle("install", "安装到本地仓库 ~/.m2", "maven-install-plugin:install"),
                new MavenLifecycle("deploy", "发布到远程仓库 Nexus/Artifactory", "maven-deploy-plugin:deploy")
        );
        String fmt = "| %-10s | %-28s | %-36s |%n";
        System.out.printf(fmt, "Phase", "说明", "默认绑定 Goal");
        System.out.println("|------------|------------------------------|--------------------------------------|");
        for (MavenLifecycle p : phases) {
            System.out.printf(fmt, p.phase, p.description, p.typicalGoal);
        }
    }

    /** dependencyManagement vs dependencies 对比 */
    static void printDependencyManagementVsDependencies() {
        System.out.println("\n=== dependencyManagement vs dependencies ===");
        String fmt = "| %-22s | %-42s | %-20s |%n";
        System.out.printf(fmt, "特性", "dependencyManagement", "dependencies");
        System.out.println("|------------------------|--------------------------------------------|----------------------|");
        System.out.printf(fmt, "是否实际引入 jar", "否, 仅声明版本", "是, 实际下载并引入");
        System.out.printf(fmt, "子模块继承", "继承版本号, 写 groupId+artifactId", "继承整个依赖, 无需再声明");
        System.out.printf(fmt, "典型场景", "父 POM 统一版本管控", "父 POM 公共依赖");
        System.out.printf(fmt, "子模块使用方式", "写 <dependency> 但不写 <version>", "自动继承, 也可 exclude");
        System.out.printf(fmt, "BOM 导入", "type=pom + scope=import", "—");
    }

    /** scope 作用域详解 */
    static void printScopeTable() {
        System.out.println("\n=== Maven Scope 依赖范围 ===");
        String fmt = "| %-10s | %-10s | %-10s | %-10s | %-8s | %-18s |%n";
        System.out.printf(fmt, "Scope", "编译", "测试", "运行", "传递", "典型依赖");
        System.out.println("|------------|------------|------------|------------|----------|--------------------|");
        System.out.printf(fmt, "compile(默认)", "O", "O", "O", "O", "spring-boot-starter");
        System.out.printf(fmt, "provided", "O", "O", "X", "X", "servlet-api, lombok");
        System.out.printf(fmt, "runtime", "X", "O", "O", "O", "mysql-connector-java");
        System.out.printf(fmt, "test", "X", "O", "X", "X", "junit, mockito");
        System.out.printf(fmt, "system", "O", "O", "O", "X", "本地 jar (不推荐)");
        System.out.printf(fmt, "import", "—", "—", "—", "—", "BOM (dependencyManagement)");
    }

    /**
     * 依赖冲突解决: 模拟 Maven 仲裁算法
     *
     * <p>规则:
     * 1. 最短路径优先 — 路径短的依赖优先
     * 2. 最先声明优先 — 路径长度相同时, 谁先在 POM 声明谁优先
     * 3. exclusions — 手动排除传递性依赖
     */
    static void simulateDependencyConflict() {
        System.out.println("\n=== 依赖冲突解决模拟 ===");
        System.out.println("场景: A -> B -> D(v1.0), A -> C -> D(v2.0)");

        class Dependency {
            String name;
            String version;
            int depth;
            int declarationOrder;

            Dependency(String name, String version, int depth, int declarationOrder) {
                this.name = name;
                this.version = version;
                this.depth = depth;
                this.declarationOrder = declarationOrder;
            }

            @Override
            public String toString() {
                return name + ":" + version + " (depth=" + depth + ", order=" + declarationOrder + ")";
            }
        }

        Dependency d1 = new Dependency("D", "v1.0", 2, 1);  // A->B->D, depth=2, 先声明
        Dependency d2 = new Dependency("D", "v2.0", 2, 2);  // A->C->D, depth=2, 后声明

        System.out.println("冲突依赖: " + d1 + " vs " + d2);
        System.out.println("规则1-最短路径: depth 相同(" + d1.depth + "), 跳过");
        System.out.println("规则2-最先声明: order " + d1.declarationOrder + " < " + d2.declarationOrder + " → 选中 " + d1.version);
        System.out.println("规则3-exclusions: 在 A->C 中 <exclusion>D</exclusion> 可排除 " + d2.version);
        System.out.println("最终仲裁结果: " + d1.version);
    }

    /** 多模块聚合 (pom packaging) 目录结构模拟 */
    static void simulateMultiModule() {
        System.out.println("\n=== 多模块聚合 (pom packaging) 目录结构 ===");
        String treeDiagram =
                "project-parent (pom)                     ← <packaging>pom</packaging>\n" +
                "├── pom.xml                                ← <modules> 声明子模块\n" +
                "├── project-common (jar)                   ← 公共工具类\n" +
                "│   └── pom.xml                            ← <parent> 指向 project-parent\n" +
                "├── project-api (jar)                      ← DTO / 接口定义\n" +
                "│   └── pom.xml\n" +
                "├── project-service (jar)                  ← 业务逻辑层\n" +
                "│   └── pom.xml\n" +
                "└── project-web (war)                      ← Web 入口层\n" +
                "    └── pom.xml\n" +
                "\n父 POM 核心配置:\n" +
                "  <packaging>pom</packaging>\n" +
                "  <modules>\n" +
                "    <module>project-common</module>\n" +
                "    <module>project-api</module>\n" +
                "    <module>project-service</module>\n" +
                "    <module>project-web</module>\n" +
                "  </modules>\n" +
                "\n子模块继承:\n" +
                "  <parent>\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>project-parent</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "  </parent>\n" +
                "\n聚合 vs 继承:\n" +
                "  聚合: 父模块知道有哪些子模块 (<modules>)\n" +
                "  继承: 子模块知道父模块是谁 (<parent>)\n" +
                "  两者通常同时使用, 但方向相反";
        System.out.println(treeDiagram);
    }

    /** 常用 Maven 命令速查 */
    static void printCommonCommands() {
        System.out.println("\n=== 常用 Maven 命令 ===");
        System.out.println("mvn clean                      — 清理 target");
        System.out.println("mvn compile                    — 编译源码");
        System.out.println("mvn test                       — 运行单元测试");
        System.out.println("mvn package -DskipTests        — 打包跳过测试");
        System.out.println("mvn install                    — 安装到本地仓库");
        System.out.println("mvn deploy                     — 发布到远程仓库");
        System.out.println("mvn dependency:tree            — 查看依赖树");
        System.out.println("mvn dependency:tree -Dincludes=com.google — 过滤查看");
        System.out.println("mvn help:effective-pom         — 查看最终生效 POM");
        System.out.println("mvn versions:display-dependency-updates — 查看依赖更新");
        System.out.println("mvn -U clean install           — 强制更新 SNAPSHOT");
        System.out.println("mvn -T 4 clean install         — 4线程并行构建");
    }

    public static void main(String[] args) {
        printLifecycleTable();
        printDependencyManagementVsDependencies();
        printScopeTable();
        simulateDependencyConflict();
        simulateMultiModule();
        printCommonCommands();
    }
}