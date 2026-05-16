package study;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 学习模块启动器。
 *
 * <p>通过模块名 + 类名直接启动对应的 Demo，无需编译另一个 Maven 工程。
 * 支持交互式浏览和直接命令行参数两种方式。
 *
 * <p>用法：
 * <pre>{@code
 *   java study.StudyRunner                    # 交互式浏览
 *   java study.StudyRunner list               # 列出所有可用类
 *   java study.StudyRunner create <dir>       # 创建新模块目录骨架
 * }</pre>
 *
 * <p>由于 study-base 和 study-tuling 是不同的 classpath，
 * study-base 中的 StudyRunner 仅能直接运行 study-base 下的 Demo。
 * study-tuling 的 Demo 请在 IDE 中直接右键运行。
 */
public class StudyRunner {

    static final Map<String, String[]> DOCK = StudyOverview.STAGE1;

    public static void main(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "list":
                    listAll();
                    return;
                case "run":
                    if (args.length > 1) {
                        runByClassName(args[1]);
                    } else {
                        System.out.println("用法: run <完整类名>  例: run base.collection.HashMapDemo");
                    }
                    return;
                case "find":
                    if (args.length > 1) {
                        findClasses(args[1]);
                    }
                    return;
                default:
                    System.out.println("未知命令: " + args[0]);
                    printUsage();
                    return;
            }
        }
        interactive();
    }

    static void listAll() {
        System.out.println("\n  study-base 可用 Demo（直接 run 即可）：\n");
        for (Map.Entry<String, String[]> entry : DOCK.entrySet()) {
            String module = entry.getKey();
            for (String cls : entry.getValue()) {
                String fqcn = "base." + module + "." + cls;
                System.out.printf("  %-60s  %s%n", fqcn, module);
            }
        }
        System.out.println();
    }

    static void runByClassName(String fqcn) {
        try {
            Class<?> clazz = Class.forName(fqcn);
            Method main = clazz.getMethod("main", String[].class);
            System.out.println("启动 " + fqcn + " ...\n");
            main.invoke(null, (Object) new String[0]);
        } catch (ClassNotFoundException e) {
            System.out.println("类未找到: " + fqcn + "。可能属于 study-tuling，请在 IDE 中直接运行。");
        } catch (NoSuchMethodException e) {
            System.out.println(fqcn + " 缺少 main 方法");
        } catch (Exception e) {
            System.err.println("运行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static void findClasses(String keyword) {
        boolean found = false;
        for (Map.Entry<String, String[]> entry : DOCK.entrySet()) {
            for (String cls : entry.getValue()) {
                if (cls.toLowerCase().contains(keyword.toLowerCase())) {
                    String fqcn = "base." + entry.getKey() + "." + cls;
                    System.out.println("  " + fqcn + "  [" + entry.getKey() + "]");
                    found = true;
                }
            }
        }
        if (!found) {
            System.out.println("  未找到包含 '" + keyword + "' 的类。study-tuling 的类请在 IDE 中搜索。");
        }
    }

    static void printUsage() {
        System.out.println("\n  java study.StudyRunner list          列出所有可用 Demo");
        System.out.println("  java study.StudyRunner run <类名>    运行指定 Demo");
        System.out.println("  java study.StudyRunner find <关键词> 按关键词搜索\n");
    }

    static void interactive() {
        System.out.println("\n  === custom-study 交互式启动器 (study-base) ===\n");
        System.out.println("  命令: list | find <关键词> | run <类名> | help | exit\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("  study> ");
                String line = reader.readLine();
                if (line == null || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("  再见。");
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("help")) {
                    printUsage();
                } else if (line.equalsIgnoreCase("list")) {
                    listAll();
                } else if (line.startsWith("run ")) {
                    runByClassName(line.substring(4).trim());
                } else if (line.startsWith("find ")) {
                    findClasses(line.substring(5).trim());
                } else {
                    System.out.println("  未知命令: " + line);
                    printUsage();
                }
            }
        } catch (Exception e) {
            System.err.println("交互式启动器异常: " + e.getMessage());
        }
    }
}