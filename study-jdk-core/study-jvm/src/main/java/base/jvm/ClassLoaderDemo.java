package base.jvm;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * 类加载器演示
 *
 * 核心知识点：
 * 1. 类加载器三层架构：Bootstrap / Extension(Platform) / Application
 * 2. 双亲委派机制（Parent Delegation）：loadClass 源码逻辑
 * 3. Class.forName vs ClassLoader.loadClass 区别
 * 4. 自定义类加载器实现
 * 5. 类加载过程：加载 → 验证 → 准备 → 解析 → 初始化
 */
public class ClassLoaderDemo {

    public static void main(String[] args) throws Exception {
        classLoaderHierarchy();
        parentDelegationMechanism();
        classForNameVsLoadClass();
        customClassLoaderDemo();
        classLoadingProcess();
    }

    /**
     * 类加载器三层架构（JDK 9+）
     *
     * JDK 8 及以前：
     * Bootstrap ClassLoader ← Extension ClassLoader ← Application ClassLoader
     *
     * JDK 9+ 模块化后：
     * Bootstrap ClassLoader ← Platform ClassLoader ← Application ClassLoader
     * （Extension → Platform，加载路径变更）
     *
     * Bootstrap：加载核心类库（rt.jar / java.base 模块），C++ 实现，Java 中为 null
     * Platform（原 Extension）：加载 java.se 等 Java SE 平台模块
     * Application：加载 classpath 下的用户类
     */
    static void classLoaderHierarchy() {
        System.out.println("=== 类加载器三层架构 ===");
        System.out.println();

        ClassLoader appClassLoader = ClassLoaderDemo.class.getClassLoader();
        ClassLoader platformClassLoader = appClassLoader.getParent();
        ClassLoader bootstrapClassLoader = platformClassLoader.getParent();

        System.out.println("Application ClassLoader: " + appClassLoader);
        System.out.println("Platform ClassLoader:    " + platformClassLoader);
        System.out.println("Bootstrap ClassLoader:   " + bootstrapClassLoader);
        System.out.println("  （Bootstrap 由 C++ 实现，Java 中返回 null）");
        System.out.println();

        System.out.println("各 ClassLoader 加载路径：");
        System.out.println("  Bootstrap:  <JAVA_HOME>/lib 或 -Xbootclasspath 指定");
        System.out.println("  Platform:   <JAVA_HOME>/lib/ext（JDK 8）/ java.se 等模块（JDK 9+）");
        System.out.println("  Application: classpath / -cp 指定的路径");
        System.out.println();
    }

    /**
     * 双亲委派机制（Parent Delegation）
     *
     * loadClass 核心逻辑：
     * 1. 检查该类是否已被加载（findLoadedClass）
     * 2. 若未加载，委托父加载器尝试加载（parent.loadClass）
     * 3. 若父加载器加载失败（ClassNotFoundException），自己尝试加载（findClass）
     * 4. 若自己也加载失败，抛出 ClassNotFoundException
     *
     * 优点：
     * - 避免重复加载（全盘负责制）
     * - 防止核心 API 被篡改（安全性）
     *   如用户自定义 java.lang.String 不会被加载（Bootstrap 已加载）
     */
    static void parentDelegationMechanism() {
        System.out.println("=== 双亲委派机制 ===");
        System.out.println();
        System.out.println("loadClass 伪代码流程：");
        System.out.println("  protected Class<?> loadClass(String name, boolean resolve) {");
        System.out.println("      // ① 检查是否已加载");
        System.out.println("      Class<?> c = findLoadedClass(name);");
        System.out.println("      if (c == null) {");
        System.out.println("          try {");
        System.out.println("              // ② 委托父加载器");
        System.out.println("              if (parent != null)");
        System.out.println("                  c = parent.loadClass(name, false);");
        System.out.println("              else");
        System.out.println("                  c = findBootstrapClassOrNull(name);");
        System.out.println("          } catch (ClassNotFoundException e) {");
        System.out.println("              // ③ 父加载器失败，自己加载");
        System.out.println("              c = findClass(name);");
        System.out.println("          }");
        System.out.println("      }");
        System.out.println("      if (resolve) resolveClass(c);");
        System.out.println("      return c;");
        System.out.println("  }");
        System.out.println();
        System.out.println("委托顺序：Application → Platform → Bootstrap");
        System.out.println("加载顺序：Bootstrap → Platform → Application（自顶向下）");
        System.out.println();
        System.out.println("安全验证示例：");
        System.out.println("  用户自定义 java.lang.String 类 — 无法替代核心 String");
        System.out.println("  → Bootstrap 先加载，发现已加载 → 返回核心 String");
        System.out.println("  → 用户自定义的 String 不会被执行");
        System.out.println();

        showStringClassLoader();
    }

    static void showStringClassLoader() {
        ClassLoader stringLoader = String.class.getClassLoader();
        System.out.println("  String 的类加载器: " + stringLoader + " ← Bootstrap（null）");
        ClassLoader myLoader = ClassLoaderDemo.class.getClassLoader();
        System.out.println("  ClassLoaderDemo 的类加载器: " + myLoader + " ← Application");
        System.out.println();
    }

    /**
     * Class.forName vs ClassLoader.loadClass 区别
     *
     * Class.forName(name):
     * - 加载 + 连接 + 初始化（执行 static 块）
     * - JDBC 驱动注册用此方式（DriverManager 依赖 static 块注册）
     *
     * ClassLoader.loadClass(name):
     * - 仅加载 + 连接，不初始化（不执行 static 块）
     * - Spring IOC 懒加载依赖此方式
     *
     * JDBC 4.0+ 使用 SPI（ServiceLoader），不再需要手动 Class.forName
     */
    static void classForNameVsLoadClass() throws Exception {
        System.out.println("=== Class.forName vs loadClass ===");
        System.out.println();
        System.out.println("┌────────────────────┬─────────────────────────────────┐");
        System.out.println("│    Class.forName    │    ClassLoader.loadClass        │");
        System.out.println("├────────────────────┼─────────────────────────────────┤");
        System.out.println("│ 加载 + 连接 + 初始化 │ 仅加载 + 连接（不执行 static 块） │");
        System.out.println("│ JDBC 驱动注册       │ Spring IOC 懒加载               │");
        System.out.println("│ 默认用调用者的 CL    │ 必须手动指定 ClassLoader          │");
        System.out.println("└────────────────────┴─────────────────────────────────┘");
        System.out.println();

        System.out.println("演示：Class.forName 触发 static 块");
        try {
            Class.forName("base.jvm.ClassWithStaticBlock");
        } catch (ClassNotFoundException e) {
            System.out.println("  类不存在（预期行为，演示用）");
        }
        System.out.println();

        System.out.println("演示：loadClass 不触发 static 块");
        ClassLoader classLoader = ClassLoaderDemo.class.getClassLoader();
        try {
            Class<?> clazz = classLoader.loadClass("base.jvm.ClassWithStaticBlock");
            System.out.println("  loadClass 完成，但未触发 static 块（需 newInstance 或 Class.forName）");
        } catch (ClassNotFoundException e) {
            System.out.println("  类不存在（预期行为，演示用）");
        }
        System.out.println();
    }

    /**
     * 自定义类加载器
     *
     * 打破双亲委派：重写 loadClass（不推荐，破坏安全模型）
     * 遵循双亲委派：重写 findClass（推荐）
     *
     * 典型场景：
     * - 热部署（修改了 class 文件，重新加载）
     * - 加密 class 解密加载
     * - 从网络/数据库加载 class
     * - 多版本依赖隔离（Tomcat 的 WebAppClassLoader）
     */
    static void customClassLoaderDemo() throws Exception {
        System.out.println("=== 自定义类加载器 ===");
        System.out.println();

        System.out.println("自定义类加载器模板（重写 findClass）：");
        System.out.println("  class MyClassLoader extends ClassLoader {");
        System.out.println("      @Override");
        System.out.println("      protected Class<?> findClass(String name) {");
        System.out.println("          byte[] bytes = loadClassData(name);  // 读 .class 字节码");
        System.out.println("          return defineClass(name, bytes, 0, bytes.length);");
        System.out.println("      }");
        System.out.println("  }");
        System.out.println();

        System.out.println("Tomcat 打破双亲委派（WebAppClassLoader）：");
        System.out.println("  ① 先在本地缓存查找");
        System.out.println("  ② 本地未找到 → 委托父加载器");
        System.out.println("  ③ 父加载器未找到 → 自己加载（WEB-INF/classes, WEB-INF/lib）");
        System.out.println("  目的：隔离不同 Web 应用的类版本");

        URL resource = ClassLoaderDemo.class.getClassLoader()
                .getResource("base/jvm/ClassLoaderDemo.class");
        if (resource != null) {
            System.out.println();
            System.out.println("本类 .class 文件位置: " + resource);
        }
        System.out.println();
    }

    /**
     * 类加载过程五阶段
     */
    static void classLoadingProcess() {
        System.out.println("=== 类加载过程（5 阶段）===");
        System.out.println();
        System.out.println("  加载 Loading");
        System.out.println("    ↓  获取二进制字节流 → 方法区生成 Class 对象");
        System.out.println("  验证 Verification");
        System.out.println("    ↓  文件格式、元数据、字节码、符号引用验证");
        System.out.println("  准备 Preparation");
        System.out.println("    ↓  为 static 变量分配内存并赋零值");
        System.out.println("  解析 Resolution");
        System.out.println("    ↓  符号引用 → 直接引用（可与初始化交叉进行）");
        System.out.println("  初始化 Initialization");
        System.out.println("    ↓  执行 static 块和 static 变量赋值");
        System.out.println();
        System.out.println("触发初始化的 6 种情况（主动引用）：");
        System.out.println("  1. new / getstatic / putstatic / invokestatic");
        System.out.println("  2. java.lang.reflect 反射调用");
        System.out.println("  3. 初始化子类前先初始化父类");
        System.out.println("  4. 虚拟机启动时 main 方法所在类");
        System.out.println("  5. MethodHandle 解析结果");
        System.out.println("  6. default 接口方法实现类初始化 → 接口初始化");
        System.out.println();
    }
}

class ClassWithStaticBlock {
    static {
        System.out.println("  ClassWithStaticBlock static 块执行了！");
    }
}