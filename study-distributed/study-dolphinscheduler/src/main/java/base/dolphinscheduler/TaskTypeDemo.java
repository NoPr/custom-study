package base.dolphinscheduler;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 任务类型模拟：Shell(ProcessBuilder) + SQL(JDBC 执行) + HTTP(URLConnection) +
 * Python(脚本调用) + 依赖检测(数据依赖) + 任务间参数传递(Output -> Input)。
 *
 * <p>核心概念：
 * <ul>
 *   <li><b>Shell 任务</b>：通过 ProcessBuilder 调用操作系统 Shell 命令，
 *       捕获 stdout/stderr 和退出码</li>
 *   <li><b>SQL 任务</b>：通过 JDBC DataSource 执行 SQL，
 *       支持查询结果集解析和 DML 影响行数</li>
 *   <li><b>HTTP 任务</b>：通过 HttpURLConnection 发送 REST API 请求，
 *       支持 GET/POST 和 JSON 响应解析</li>
 *   <li><b>Python 任务</b>：通过 ProcessBuilder 调用 python3 脚本，
 *       支持命令行参数传递和输出捕获</li>
 *   <li><b>依赖检测</b>：上游任务写入 output 标记，下游任务检测标记是否存在再执行</li>
 *   <li><b>参数传递</b>：任务通过共享 Map 实现 Output -> Input 的参数传递链</li>
 * </ul>
 *
 * <p>运行方式：直接执行 main()。SQL 任务使用 H2 内存数据库。
 *
 * @author study-tuling
 */
public class TaskTypeDemo {

    /** 任务间参数传递的共享上下文 */
    static final Map<String, String> GLOBAL_PARAMS = new LinkedHashMap<>();

    /** 依赖检测标记集合 */
    static final Set<String> COMPLETED_TASKS = ConcurrentHashMap.newKeySet();

    /* ==================== 1. Shell 任务（ProcessBuilder） ==================== */

    /**
     * 执行 Shell 命令。
     *
     * <p>通过 ProcessBuilder 启动子进程，捕获标准输出、标准错误和退出码。
     * DolphinScheduler 中 Shell 任务类型即通过此机制在 Worker 节点执行脚本。
     */
    static class ShellTask {
        static class Result {
            final int exitCode;
            final String stdout;
            final String stderr;

            Result(int exitCode, String stdout, String stderr) {
                this.exitCode = exitCode;
                this.stdout = stdout;
                this.stderr = stderr;
            }
        }

        Result execute(String command) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder();
            // Windows 用 cmd /c，Linux/Mac 用 /bin/sh -c
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                processBuilder.command("cmd", "/c", command);
            } else {
                processBuilder.command("/bin/sh", "-c", command);
            }
            processBuilder.redirectErrorStream(false);

            Process process = processBuilder.start();

            // 读取 stdout
            String stdout = readStream(process.getInputStream());
            // 读取 stderr
            String stderr = readStream(process.getErrorStream());

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;

            if (!finished) {
                process.destroyForcibly();
            }

            return new Result(exitCode, stdout, stderr);
        }

        private String readStream(InputStream inputStream) throws IOException {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            return output.toString().trim();
        }
    }

    /* ==================== 2. SQL 任务（JDBC + H2 内存库） ==================== */

    /**
     * 通过 JDBC 执行 SQL 语句，使用 H2 内存数据库模拟。
     */
    static class SQLTask {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        SQLTask(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        /** 执行 DDL/DML（返回影响行数） */
        int executeUpdate(String sql) throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        }

        /** 执行 SELECT（返回结果集） */
        List<Map<String, Object>> executeQuery(String sql) throws SQLException {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {

                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), resultSet.getObject(i));
                    }
                    results.add(row);
                }
            }
            return results;
        }

        /** 检查指定表的数据是否就绪（依赖检测） */
        boolean isDataReady(String tableName, int expectedMinRows) throws SQLException {
            String countSql = "SELECT COUNT(*) AS cnt FROM " + tableName;
            List<Map<String, Object>> result = executeQuery(countSql);
            if (result.isEmpty()) {
                return false;
            }
            long count = ((Number) result.get(0).get("CNT")).longValue();
            return count >= expectedMinRows;
        }
    }

    /* ==================== 3. HTTP 任务（HttpURLConnection） ==================== */

    /**
     * 通过 HttpURLConnection 发送 HTTP 请求。
     */
    static class HTTPTask {
        static class HttpResponse {
            final int statusCode;
            final String body;

            HttpResponse(int statusCode, String body) {
                this.statusCode = statusCode;
                this.body = body;
            }
        }

        HttpResponse get(String urlString) throws IOException {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");

            int statusCode = connection.getResponseCode();
            String body = readResponse(connection);
            connection.disconnect();
            return new HttpResponse(statusCode, body);
        }

        HttpResponse post(String urlString, String jsonBody) throws IOException {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            String body = readResponse(connection);
            connection.disconnect();
            return new HttpResponse(statusCode, body);
        }

        private String readResponse(HttpURLConnection connection) throws IOException {
            InputStream inputStream;
            try {
                inputStream = connection.getInputStream();
            } catch (IOException e) {
                inputStream = connection.getErrorStream();
            }
            if (inputStream == null) return "";

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            return response.toString();
        }
    }

    /* ==================== 4. Python 任务（ProcessBuilder 调用脚本） ==================== */

    /**
     * 通过 ProcessBuilder 调用 Python 解释器执行脚本。
     */
    static class PythonTask {
        static class PythonResult {
            final int exitCode;
            final String output;

            PythonResult(int exitCode, String output) {
                this.exitCode = exitCode;
                this.output = output;
            }
        }

        /** 执行 Python 脚本（内联方式：-c 参数直接传代码） */
        PythonResult execute(String pythonCode) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder("python3", "-c", pythonCode);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator());
                }
                output = builder.toString().trim();
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            if (!finished) {
                process.destroyForcibly();
            }
            return new PythonResult(exitCode, output);
        }
    }

    /* ==================== 5. 依赖检测 + 参数传递链 ==================== */

    /**
     * 任务依赖检测：下游任务执行前检查上游任务标记是否存在。
     *
     * <p>数据依赖检测模式（DolphinScheduler 的 Dependent 节点）：
     * 上游任务完成后写入完成标记，下游任务轮询检查标记，就绪后才执行。
     */
    static class DependencyChecker {
        /** 注册上游任务完成标记 */
        void markCompleted(String upstreamTaskName, String outputParam) {
            COMPLETED_TASKS.add(upstreamTaskName);
            GLOBAL_PARAMS.put(upstreamTaskName + ".output", outputParam);
            System.out.printf("[依赖] 上游任务 [%s] 完成, output='%s' 已注册%n",
                    upstreamTaskName, outputParam);
        }

        /** 下游任务检查所有上游依赖是否就绪 */
        boolean checkDependencies(String downstreamName, String... upstreamTasks) {
            List<String> missing = new ArrayList<>();
            for (String upstream : upstreamTasks) {
                if (!COMPLETED_TASKS.contains(upstream)) {
                    missing.add(upstream);
                }
            }
            if (missing.isEmpty()) {
                System.out.printf("[依赖] 下游任务 [%s] 所有上游依赖已就绪%n", downstreamName);
                return true;
            }
            System.out.printf("[依赖] 下游任务 [%s] 等待上游: %s%n",
                    downstreamName, missing);
            return false;
        }

        /** 下游任务读取上游传递的参数 */
        Map<String, String> fetchUpstreamParams(String... upstreamTasks) {
            Map<String, String> params = new LinkedHashMap<>();
            for (String upstream : upstreamTasks) {
                String output = GLOBAL_PARAMS.get(upstream + ".output");
                params.put(upstream, output);
            }
            return params;
        }
    }

    /* ==================== 6. Demo 场景 ==================== */

    static void demoShellTask() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 1：Shell 任务 -- ProcessBuilder 调用系统命令");
        System.out.println("=".repeat(60));

        ShellTask shellTask = new ShellTask();
        String os = System.getProperty("os.name").toLowerCase();

        try {
            // 1. 简单命令
            String cmd1 = os.contains("win") ? "echo Hello DolphinScheduler" : "echo 'Hello DolphinScheduler'";
            ShellTask.Result result1 = shellTask.execute(cmd1);
            System.out.printf("Shell[echo]: exitCode=%d, stdout='%s'%n",
                    result1.exitCode, result1.stdout);

            // 2. 带数据输出的命令（用于任务间参数传递）
            String cmd2 = os.contains("win")
                    ? "powershell -Command \"Get-Date -Format 'yyyy-MM-dd HH:mm:ss'\""
                    : "date '+%Y-%m-%d %H:%M:%S'";
            ShellTask.Result result2 = shellTask.execute(cmd2);
            String bizDate = result2.stdout.trim();
            GLOBAL_PARAMS.put("shell.bizDate", bizDate);
            System.out.printf("Shell[date]: exitCode=%d, bizDate='%s' -> 写入全局参数 shell.bizDate%n",
                    result2.exitCode, bizDate);

        } catch (IOException | InterruptedException e) {
            System.err.println("Shell 执行异常: " + e.getMessage());
        }
    }

    static void demoSQLTask() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 2：SQL 任务 -- JDBC 执行 DDL/DML/SELECT (H2 内存库)");
        System.out.println("=".repeat(60));

        String jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
        String user = "sa";
        String password = "";

        try {
            // 加载 H2 驱动
            Class.forName("org.h2.Driver");
            SQLTask sqlTask = new SQLTask(jdbcUrl, user, password);

            // 1. DDL: 建表
            System.out.println(">>> 建表 + 插入数据");
            sqlTask.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS order_info (
                        id INT PRIMARY KEY,
                        user_name VARCHAR(50),
                        amount DECIMAL(10,2),
                        status VARCHAR(20)
                    )""");

            // 2. DML: 插入数据
            int inserted = sqlTask.executeUpdate("""
                    INSERT INTO order_info VALUES
                    (1, '张三', 299.00, 'PAID'),
                    (2, '李四', 599.00, 'PAID'),
                    (3, '王五', 199.00, 'PENDING')
                    """);
            System.out.printf("  插入行数: %d%n%n", inserted);

            // 3. SELECT: 查询
            System.out.println(">>> SELECT 查询");
            List<Map<String, Object>> orders = sqlTask.executeQuery(
                    "SELECT id, user_name, amount, status FROM order_info WHERE status='PAID'");
            for (Map<String, Object> row : orders) {
                System.out.printf("  id=%s, user=%s, amount=%.2f, status=%s%n",
                        row.get("ID"), row.get("USER_NAME"),
                        ((Number) row.get("AMOUNT")).doubleValue(),
                        row.get("STATUS"));
            }
            System.out.printf("  查询到 %d 条 PAID 订单%n%n", orders.size());

            // 4. 依赖检测: 检查表数据是否就绪
            System.out.println(">>> 数据依赖检测");
            boolean ready = sqlTask.isDataReady("order_info", 2);
            System.out.printf("  order_info 表数据>=2行? %s (实际 %d 行)%n",
                    ready ? "就绪" : "未就绪", orders.size());

        } catch (ClassNotFoundException e) {
            System.out.println("H2 Driver 未找到，请添加依赖: <groupId>com.h2database</groupId>");
        } catch (SQLException e) {
            System.err.println("SQL 执行异常: " + e.getMessage());
        }
    }

    static void demoHTTPTask() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 3：HTTP 任务 -- URLConnection 调用 REST API");
        System.out.println("=".repeat(60));

        HTTPTask httpTask = new HTTPTask();

        // 1. GET 请求（调用公开 API）
        System.out.println(">>> GET 请求: https://httpbin.org/get");
        try {
            HTTPTask.HttpResponse response = httpTask.get("https://httpbin.org/get");
            System.out.printf("  Status: %d%n", response.statusCode);
            System.out.printf("  Body(前100字符): %s%n%n",
                    response.body.length() > 100
                            ? response.body.substring(0, 100) + "..."
                            : response.body);
        } catch (IOException e) {
            System.out.println("  GET 请求异常 (可能网络不通): " + e.getMessage());
        }

        // 2. POST 请求（模拟回调）
        System.out.println(">>> POST 请求: 模拟任务完成回调");
        String callbackBody = """
                {"taskId":"ETL-001","status":"SUCCESS","timestamp":"2026-05-15 10:30:00"}""";
        try {
            HTTPTask.HttpResponse postResponse = httpTask.post("https://httpbin.org/post", callbackBody);
            System.out.printf("  Status: %d%n", postResponse.statusCode);
            System.out.printf("  请求体: %s%n%n", callbackBody);
        } catch (IOException e) {
            System.out.println("  POST 请求异常 (可能网络不通): " + e.getMessage());
        }

        System.out.println("DolphinScheduler HTTP 任务类型用于: 调用第三方 API、触发 WebHook、数据同步通知");
    }

    static void demoPythonTask() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 4：Python 任务 -- 内联脚本调用");
        System.out.println("=".repeat(60));

        PythonTask pythonTask = new PythonTask();

        // Python 内联脚本：数据清洗
        String pythonCode = """
                import json, sys
                data = {"raw": ["  Alice ", "  Bob  ", " Charlie"]}
                cleaned = [name.strip() for name in data["raw"]]
                data["cleaned"] = cleaned
                data["count"] = len(cleaned)
                print(json.dumps(data, ensure_ascii=False))
                """;
        // 转成一行（-c 参数要求一行）
        String inlineCode = pythonCode.replace("\n", "; ").replace("\"", "\\\"");

        try {
            PythonTask.PythonResult result = pythonTask.execute(inlineCode);
            System.out.printf("  Python 退出码: %d%n", result.exitCode);
            System.out.printf("  Python 输出:   %s%n", result.output);
            System.out.println("  作用: 在 DolphinScheduler 中 Python 节点可执行 ML/ETL 脚本");
        } catch (IOException | InterruptedException e) {
            System.out.println("  Python 执行异常 (可能未安装 python3): " + e.getMessage());
            System.out.println("  Python 节点默认调用执行机的 python3 解释器");
        }
    }

    static void demoDependencyAndParamPassing() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 5：依赖检测 + 任务间参数传递链");
        System.out.println("=".repeat(60));

        DependencyChecker checker = new DependencyChecker();

        // 模拟上游任务 A 完成，产出参数 (output -> input 链)
        System.out.println("--- 上游任务 A: 数据抽取 ---");
        checker.markCompleted("ETL-Extract", "raw_data_path=/data/2026-05-15/*.log");

        // 下游任务 B 检测依赖
        System.out.println("\n--- 下游任务 B: 数据清洗 (检查上游 A) ---");
        boolean readyB = checker.checkDependencies("ETL-Clean", "ETL-Extract");
        if (readyB) {
            Map<String, String> paramsB = checker.fetchUpstreamParams("ETL-Extract");
            System.out.printf("  任务 B 收到上游参数: %s%n", paramsB);
            // B 完成后注册自己的输出
            checker.markCompleted("ETL-Clean", "cleaned_path=/data/cleaned/2026-05-15/");
        }

        // 下游任务 C 依赖 A 和 B
        System.out.println("\n--- 下游任务 C: 数据加载 (检查上游 A + B) ---");
        boolean readyC = checker.checkDependencies("ETL-Load", "ETL-Extract", "ETL-Clean");
        if (readyC) {
            Map<String, String> paramsC = checker.fetchUpstreamParams("ETL-Extract", "ETL-Clean");
            System.out.printf("  任务 C 收到上游参数: %s%n", paramsC);
            checker.markCompleted("ETL-Load", "loaded_rows=100000");
        }

        System.out.println("\n参数传递链: A.output -> B.input -> B.output -> C.input -> C.output");
        System.out.println("DolphinScheduler 通过「全局参数」和「上游任务输出参数」实现此机制");
        System.out.println("当前全局参数池: " + GLOBAL_PARAMS);
    }

    /* ==================== 7. 综合任务类型对比 ==================== */

    static void demoTaskTypeComparison() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("    场景 6：DolphinScheduler 任务类型总览对比");
        System.out.println("=".repeat(60));

        String fmt = "| %-18s | %-10s | %-35s | %-20s |%n";
        System.out.printf(fmt, "任务类型", "引擎", "典型场景", "输出/传递");
        System.out.println("|--------------------|------------|-------------------------------------|----------------------|");
        System.out.printf(fmt, "Shell",     "ProcessBuilder",  "数据归档/脚本执行/文件操作",     "stdout + exitCode");
        System.out.printf(fmt, "SQL",       "JDBC DataSource", "数据清洗/聚合统计/依赖检测",     "ResultSet + rowsAffected");
        System.out.printf(fmt, "HTTP",      "URLConnection",   "API 调用/WebHook/服务触发",      "statusCode + responseBody");
        System.out.printf(fmt, "Python",    "python3 syscall", "ML 训练/数据分析/爬虫",          "stdout + exitCode");
        System.out.printf(fmt, "Dependent", "轮询检查",         "上游数据就绪检测/依赖触发",      "Boolean isReady");
        System.out.printf(fmt, "SubProcess","嵌套 DAG",        "子工作流封装/流程复用",           "父流程 await 子流程");
        System.out.printf(fmt, "DataX",     "DataX Engine",    "异构数据源同步/MySQL->Hive",     "传输记录数");
        System.out.println();

        System.out.println("参数传递机制（Output→Input）:");
        System.out.println("  1. 上游任务将输出写入「全局参数」或「自定义输出参数」");
        System.out.println("  2. 下游任务通过 ${taskName.outputParam} 引用上游输出");
        System.out.println("  3. 支持变量替换：${bizDate}、${processInstanceId} 等内置变量");
        System.out.println("  4. 依赖检测：Dependent 节点支持「日/周/月」级别的数据就绪检查");
    }

    /* ==================== 8. 主入口 ==================== */

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("    DolphinScheduler 任务类型模拟演示");
        System.out.println("    覆盖: Shell | SQL | HTTP | Python | 依赖检测 | 参数传递");
        System.out.println("=".repeat(60));

        demoShellTask();
        demoSQLTask();
        demoHTTPTask();
        demoPythonTask();
        demoDependencyAndParamPassing();
        demoTaskTypeComparison();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("    TaskTypeDemo 全部演示完毕");
        System.out.println("=".repeat(60));
    }
}