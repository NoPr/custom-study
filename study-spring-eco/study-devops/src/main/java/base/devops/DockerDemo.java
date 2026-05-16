package base.devops;

import java.util.*;

/**
 * Docker 容器化核心概念大合集: Dockerfile 指令 + 镜像分层 + docker-compose + 网络模式 + Port mapping
 *
 * <p>Dockerfile 核心指令:
 * FROM: 指定基础镜像, 必须第一条指令
 * RUN: 构建时执行命令 (shell 或 exec 形式), 每行 RUN 产生新层
 * COPY: 从构建上下文复制文件到镜像 (推荐, 比 ADD 更透明)
 * ADD: COPY + 自动解压 tar + 支持 URL (不推荐, 行为不透明)
 * CMD: 容器启动默认命令, 可被 docker run 参数覆盖, 只有一个生效
 * ENTRYPOINT: 容器入口命令, docker run 参数作为追加参数
 * WORKDIR: 设置工作目录
 * EXPOSE: 声明容器监听端口 (仅文档作用, 实际映射靠 -p)
 * ENV: 设置环境变量
 * ARG: 构建参数 (--build-arg), 镜像中不保留
 *
 * <p>镜像分层 (Overlay2): 每行 RUN/COPY/ADD 生成一个只读层,
 * 容器启动时在顶层加一个可写层 (copy-on-write).
 * 相同层可跨镜像共享, 加速拉取和部署.
 *
 * <p>网络模式: bridge (默认, NAT), host (共享宿主机), none (无网络), overlay (跨主机)
 */
public class DockerDemo {

    /** Dockerfile 指令表 */
    static void printDockerfileInstructions() {
        System.out.println("=== Dockerfile 核心指令 ===");
        String fmt = "| %-16s | %-8s | %-44s |%n";
        System.out.printf(fmt, "指令", "产生新层?", "说明");
        System.out.println("|------------------|----------|----------------------------------------------|");
        System.out.printf(fmt, "FROM", "否(基础)", "指定基础镜像, 必须是第一条指令");
        System.out.printf(fmt, "RUN", "是", "构建时执行 shell 命令");
        System.out.printf(fmt, "COPY", "是", "从构建上下文复制文件 (推荐)");
        System.out.printf(fmt, "ADD", "是", "COPY + 自动解压tar + 支持URL (慎用)");
        System.out.printf(fmt, "CMD", "否", "容器启动默认命令, 可覆盖");
        System.out.printf(fmt, "ENTRYPOINT", "否", "容器入口, 不可覆盖, 参数追加");
        System.out.printf(fmt, "WORKDIR", "否", "设置后续指令的工作目录");
        System.out.printf(fmt, "EXPOSE", "否", "声明端口, 仅文档作用");
        System.out.printf(fmt, "ENV", "是", "设置环境变量, 容器运行时可用");
        System.out.printf(fmt, "ARG", "否", "构建参数 --build-arg, 镜像不保留");
        System.out.printf(fmt, "VOLUME", "是", "声明匿名卷, 持久化数据");
        System.out.printf(fmt, "USER", "否", "切换运行用户");
        System.out.printf(fmt, "LABEL", "是", "添加元数据标签");
        System.out.printf(fmt, "HEALTHCHECK", "否", "容器健康检查命令");
    }

    /** CMD vs ENTRYPOINT 对比 + 组合模式 */
    static void printCmdVsEntrypoint() {
        System.out.println("\n=== CMD vs ENTRYPOINT 对比 ===");
        System.out.println();

        System.out.println("1. 仅 CMD:");
        System.out.println("   CMD [\"java\", \"-jar\", \"app.jar\"]");
        System.out.println("   docker run myimg java -jar other.jar  ← 完全覆盖 CMD");
        System.out.println();

        System.out.println("2. 仅 ENTRYPOINT:");
        System.out.println("   ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]");
        System.out.println("   docker run myimg --server.port=8081   ← 将参数追加到 ENTRYPOINT 后面");
        System.out.println("   实际执行: java -jar app.jar --server.port=8081");
        System.out.println();

        System.out.println("3. 组合模式 (最佳实践):");
        System.out.println("   ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]   ← 固定执行程序");
        System.out.println("   CMD [\"--server.port=8080\"]                 ← 默认参数, 可覆盖");
        System.out.println("   docker run myimg                            → java -jar app.jar --server.port=8080");
        System.out.println("   docker run myimg --server.port=9090         → java -jar app.jar --server.port=9090");
    }

    /** 镜像分层 Overlay2 写时复制 */
    static void simulateOverlay2Layers() {
        System.out.println("\n=== 镜像分层 (Overlay2 copy-on-write) ===");

        class ImageLayer {
            String instruction;
            String description;
            boolean shared;

            ImageLayer(String instruction, String description, boolean shared) {
                this.instruction = instruction;
                this.description = description;
                this.shared = shared;
            }
        }

        List<ImageLayer> layers = List.of(
                new ImageLayer("FROM openjdk:17-slim", "Alpine Linux 根文件系统 (R/O)", true),
                new ImageLayer("RUN apk add curl", "安装 curl, 产生新层 (R/O)", true),
                new ImageLayer("COPY app.jar /app/", "复制 jar 包, 新层 (R/O)", false),
                new ImageLayer("CMD [\"java\",\"-jar\",\"app.jar\"]", "元数据, 不产生层", false),
                new ImageLayer("--- 容器可写层 ---", "容器启动时自动创建, copy-on-write", false)
        );

        System.out.println("Dockerfile → 镜像分层视图:");
        for (ImageLayer layer : layers) {
            String prefix = layer.shared ? "  [SHARED] " : "  [UNIQUE] ";
            System.out.println(prefix + layer.instruction + "  — " + layer.description);
        }

        System.out.println();
        System.out.println("copy-on-write 原理:");
        System.out.println("  1. 多个容器共享同一个镜像的所有只读层");
        System.out.println("  2. 容器需要写入时, 将文件从下层 copy 到顶层, 在顶层修改");
        System.out.println("  3. 原始下层文件保持不变, 其他容器不受影响");
        System.out.println("  4. 好处: 节省磁盘空间 + 加速容器启动 (层缓存)");
    }

    /** docker-compose.yml 核心结构模拟 */
    static void simulateDockerCompose() {
        System.out.println("\n=== docker-compose.yml 核心结构模拟 ===");
        String composeYaml =
                "version: '3.9'                     ← Compose 文件格式版本\n" +
                "\n" +
                "services:                          ← 定义所有服务容器\n" +
                "  app:                             ← 服务名 (也是 DNS 主机名)\n" +
                "    build: .                       ← 使用当前目录 Dockerfile 构建\n" +
                "    image: myapp:1.0               ← 也可直接使用镜像\n" +
                "    ports:\n" +
                "      - \"8080:8080\"               ← 宿主机:容器 端口映射\n" +
                "    environment:                    ← 环境变量\n" +
                "      - SPRING_PROFILES_ACTIVE=prod\n" +
                "    depends_on:                     ← 启动顺序依赖\n" +
                "      - redis\n" +
                "      - mysql\n" +
                "    networks:\n" +
                "      - app-network               ← 加入自定义网络\n" +
                "    volumes:\n" +
                "      - ./logs:/app/logs          ← 宿主机:容器 目录挂载\n" +
                "    restart: always                ← 崩溃自动重启\n" +
                "\n" +
                "  redis:\n" +
                "    image: redis:7-alpine\n" +
                "    volumes:\n" +
                "      - redis-data:/data          ← 命名卷挂载 (持久化)\n" +
                "    networks:\n" +
                "      - app-network\n" +
                "\n" +
                "  mysql:\n" +
                "    image: mysql:8.0\n" +
                "    environment:\n" +
                "      MYSQL_ROOT_PASSWORD: secret\n" +
                "    volumes:\n" +
                "      - mysql-data:/var/lib/mysql\n" +
                "    networks:\n" +
                "      - app-network\n" +
                "\n" +
                "networks:                          ← 自定义网络定义\n" +
                "  app-network:\n" +
                "    driver: bridge                 ← bridge 驱动 (默认)\n" +
                "\n" +
                "volumes:                           ← 命名卷定义\n" +
                "  redis-data:\n" +
                "  mysql-data:";
        System.out.println(composeYaml);
    }

    /** Docker 网络模式对比 */
    static void printNetworkModes() {
        System.out.println("\n=== Docker 网络模式 ===");
        String fmt = "| %-12s | %-14s | %-16s | %-38s |%n";
        System.out.printf(fmt, "模式", "与宿主机隔离", "跨容器互通", "典型场景");
        System.out.println("|--------------|----------------|------------------|----------------------------------------|");
        System.out.printf(fmt, "bridge (默认)", "是(NAT)", "同一 bridge 网络", "docker0 网桥, --link 或 compose 网络");
        System.out.printf(fmt, "host", "否(共用网络栈)", "— (无隔离)", "高性能网络, 端口不冲突");
        System.out.printf(fmt, "none", "完全隔离", "否", "无需网络的批处理容器");
        System.out.printf(fmt, "overlay", "是(VXLAN)", "跨主机多容器", "Swarm 集群跨节点通信");
        System.out.printf(fmt, "macvlan", "否(物理MAC)", "通过物理网络", "需要直接接入物理网络的遗留应用");
        System.out.println();
        System.out.println("Port mapping 格式:");
        System.out.println("  -p 8080:8080        ← 宿主机8080映射容器8080");
        System.out.println("  -p 8080:8080/udp    ← 指定 UDP 协议");
        System.out.println("  -p 127.0.0.1:8080:8080 ← 仅绑定本地回环地址");
        System.out.println("  -p 8080-8090:8080-8090 ← 端口范围映射");
        System.out.println("  -P                   ← 映射所有 EXPOSE 端口到随机宿主机端口");
    }

    /** 常用 docker 命令速查 */
    static void printCommonCommands() {
        System.out.println("\n=== 常用 Docker 命令速查 ===");
        System.out.println("===== 镜像管理 =====");
        System.out.println("docker build -t myapp:1.0 .           — 构建镜像");
        System.out.println("docker images                          — 列出本地镜像");
        System.out.println("docker rmi myapp:1.0                   — 删除镜像");
        System.out.println("docker pull openjdk:17-slim            — 拉取镜像");
        System.out.println("docker history myapp:1.0               — 查看镜像分层历史");
        System.out.println();
        System.out.println("===== 容器管理 =====");
        System.out.println("docker run -d -p 8080:8080 --name app myapp:1.0 — 启动容器");
        System.out.println("docker ps / docker ps -a               — 查看运行/全部容器");
        System.out.println("docker logs -f app                     — 实时查看容器日志");
        System.out.println("docker exec -it app sh                 — 进入容器 shell");
        System.out.println("docker stop/start/restart app           — 停止/启动/重启");
        System.out.println("docker rm app                          — 删除容器");
        System.out.println("docker stats                           — 容器资源使用统计");
        System.out.println();
        System.out.println("===== Compose =====");
        System.out.println("docker-compose up -d                   — 后台启动所有服务");
        System.out.println("docker-compose down                    — 停止并删除所有资源");
        System.out.println("docker-compose logs -f app             — 查看服务日志");
        System.out.println("docker-compose ps                      — 查看服务状态");
    }

    public static void main(String[] args) {
        printDockerfileInstructions();
        printCmdVsEntrypoint();
        simulateOverlay2Layers();
        simulateDockerCompose();
        printNetworkModes();
        printCommonCommands();
    }
}