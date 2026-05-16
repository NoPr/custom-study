package base.jvm.interview;

/**
 * 面试题：类加载器双亲委派机制
 *
 * 高频考点：
 * 1. 三层类加载器架构（Bootstrap / Platform / Application）
 * 2. 双亲委派机制的原理和源码流程
 * 3. 为什么要双亲委派？（安全性 + 避免重复加载）
 * 4. 如何打破双亲委派？（重写 loadClass / 线程上下文类加载器 / SPI）
 * 5. Tomcat 如何打破双亲委派实现应用隔离
 */
public class Q01_ClassLoader_ParentDelegation {

    public static void main(String[] args) {
        q1_classLoaderHierarchy();
        q2_parentDelegationFlow();
        q3_whyParentDelegation();
        q4_breakParentDelegation();
        q5_tomcatClassLoader();
        q6_spiBreakDelegation();
    }

    /**
     * Q1：JVM 有哪些类加载器？层级关系是怎样的？
     *
     * JDK 8:  Bootstrap → Extension → Application
     * JDK 9+: Bootstrap → Platform → Application
     *
     * Bootstrap（启动类加载器）：
     * - C++ 实现，Java 中表示为 null
     * - 加载 <JAVA_HOME>/lib 下的核心类库（rt.jar, java.base 等）
     *
     * Platform / Extension（平台/扩展类加载器）：
     * - JDK 8: 加载 <JAVA_HOME>/lib/ext 目录下的 jar
     * - JDK 9+: 加载 java.se 等 Java SE 平台模块
     *
     * Application（应用类加载器）：
     * - 加载 classpath / -cp 下的用户类
     */
    static void q1_classLoaderHierarchy() {
        System.out.println("=== Q1：JVM 类加载器层级 ===");
        System.out.println();

        ClassLoader appCL = Q01_ClassLoader_ParentDelegation.class.getClassLoader();
        ClassLoader platformCL = appCL.getParent();
        ClassLoader bootstrapCL = platformCL.getParent();

        System.out.println("Application ClassLoader : " + appCL);
        System.out.println("Platform ClassLoader    : " + platformCL);
        System.out.println("Bootstrap ClassLoader   : " + bootstrapCL + " (null = C++ 实现)");
        System.out.println();
        System.out.println("委托关系：Application → Platform → Bootstrap");
        System.out.println("加载顺序：Bootstrap → Platform → Application");
        System.out.println();
    }

    /**
     * Q2：请描述双亲委派机制的 loadClass 流程
     *
     * 核心三步：
     *  ① findLoadedClass(name) — 检查是否已加载
     *  ② parent.loadClass(name) — 委托父加载器
     *  ③ findClass(name)         — 父加载器失败，自己尝试
     *
     * 面试答案关键点：
     * - "自底向上委托，自顶向下加载"
     * - 先问爹能不能加载，爹加载不了儿子再自己来
     * - 防止核心类被篡改（安全沙箱）
     */
    static void q2_parentDelegationFlow() {
        System.out.println("=== Q2：双亲委派 loadClass 流程 ===");
        System.out.println();
        System.out.println("loadClass 核心源码逻辑（简化）：");
        System.out.println();
        System.out.println("  protected Class<?> loadClass(String name, boolean resolve)");
        System.out.println("      throws ClassNotFoundException {");
        System.out.println("    synchronized (getClassLoadingLock(name)) {");
        System.out.println("      // ① 检查是否已经加载");
        System.out.println("      Class<?> c = findLoadedClass(name);");
        System.out.println("      if (c == null) {");
        System.out.println("        try {");
        System.out.println("          // ② 有父加载器 → 委托父加载器");
        System.out.println("          if (parent != null)");
        System.out.println("            c = parent.loadClass(name, false);");
        System.out.println("          else");
        System.out.println("            // 父加载器为 null → 尝试 Bootstrap");
        System.out.println("            c = findBootstrapClassOrNull(name);");
        System.out.println("        } catch (ClassNotFoundException e) {");
        System.out.println("          // ③ 父加载器加载失败 → 自己加载");
        System.out.println("          c = findClass(name);");
        System.out.println("        }");
        System.out.println("      }");
        System.out.println("      if (resolve) resolveClass(c);");
        System.out.println("      return c;");
        System.out.println("    }");
        System.out.println("  }");
        System.out.println();
        System.out.println("运行示例：");
        System.out.println("  加载 com.example.User →");
        System.out.println("    AppCL: findLoadedClass → 未找到");
        System.out.println("    AppCL: parent.loadClass → PlatCL");
        System.out.println("      PlatCL: findLoadedClass → 未找到");
        System.out.println("      PlatCL: parent.loadClass → Bootstrap");
        System.out.println("        Bootstrap: 未找到（非核心类）→ CNFE");
        System.out.println("      PlatCL: findClass → 未找到 → CNFE");
        System.out.println("    AppCL: findClass → classpath 中找到 → 加载成功！");
        System.out.println();
    }

    /**
     * Q3：为什么要设计双亲委派机制？
     *
     * 两个核心目的：
     * 1. 安全性：防止核心 API 被篡改
     *    - 如果用户可以自定义 java.lang.String，可能植入恶意代码
     *    - 双亲委派保证核心 String 由 Bootstrap 加载，用户定义的无效
     *
     * 2. 避免重复加载：确保一个类在 JVM 中只有一份
     *    - 全盘负责制：一旦被父加载器加载，子加载器不会再加载
     *    - 保证 Java 类型体系的一致性
     */
    static void q3_whyParentDelegation() {
        System.out.println("=== Q3：为什么需要双亲委派？===");
        System.out.println();
        System.out.println("原因 1 — 安全性：");
        System.out.println("  如果用户可以自定义 java.lang.String：");
        System.out.println("  package java.lang;");
        System.out.println("  public class String {");
        System.out.println("    // 恶意代码：拦截所有字符串，窃取密码");
        System.out.println("  }");
        System.out.println("  没有双亲委派 → 用户的 String 可能被加载");
        System.out.println("  有双亲委派   → Bootstrap 先加载 String → 用户定义的无效");
        System.out.println();
        System.out.println("原因 2 — 避免重复加载：");
        System.out.println("  全盘负责制：一个类只被加载一次");
        System.out.println("  findLoadedClass 检查 → 已加载直接返回");
        System.out.println("  保证 instanceof / 类型转换的一致性");
        System.out.println();
    }

    /**
     * Q4：如何打破双亲委派机制？
     *
     * 三种方式：
     *
     * 1. 重写 loadClass()（不推荐，破坏安全模型）
     *    - 先自己加载，失败再委托父加载器
     *    - 缺点：核心类也可能被替换
     *
     * 2. 线程上下文类加载器（Thread Context ClassLoader）
     *    - Java 官方 SPI 机制采用的方式（如 JDBC、JNDI）
     *    - SPI 核心接口在 rt.jar（Bootstrap 加载），具体实现在 classpath
     *    - Bootstrap 加载 SPI 接口时，需要加载 classpath 中的实现类
     *    - 解决：用 Thread.currentThread().getContextClassLoader() 获取 AppCL
     *
     * 3. 重写 findClass() + 不委托（推荐方式）
     *    - Tomcat WebAppClassLoader 的方式
     *    - 先自己加载 WEB-INF 下的类，特定类才委托父加载器
     */
    static void q4_breakParentDelegation() {
        System.out.println("=== Q4：如何打破双亲委派？===");
        System.out.println();
        System.out.println("方式 1：重写 loadClass（不推荐）");
        System.out.println("  @Override");
        System.out.println("  public Class<?> loadClass(String name) {");
        System.out.println("    Class<?> c = findLoadedClass(name);");
        System.out.println("    if (c == null) {");
        System.out.println("      c = findClass(name);  // 先自己加载");
        System.out.println("      if (c == null)");
        System.out.println("        c = super.loadClass(name); // 失败再委托");
        System.out.println("    }");
        System.out.println("    return c;");
        System.out.println("  }");
        System.out.println();
        System.out.println("方式 2：线程上下文类加载器（SPI 模式）");
        System.out.println("  // 核心库（Bootstrap 加载）中获取 AppCL");
        System.out.println("  ClassLoader cl = Thread.currentThread()");
        System.out.println("      .getContextClassLoader();");
        System.out.println("  // 用 AppCL 加载 classpath 中的实现类");
        System.out.println("  ServiceLoader.load(Driver.class);");
        System.out.println();
        System.out.println("方式 3：Tomcat 的 WebAppClassLoader");
        System.out.println("  ① 已加载？ → 返回");
        System.out.println("  ② 系统类？ → 委托 Bootstrap");
        System.out.println("  ③ WEB-INF/classes → 自己加载");
        System.out.println("  ④ WEB-INF/lib     → 自己加载");
        System.out.println("  ⑤ 都没找到         → 委托父加载器");
        System.out.println();
    }

    /**
     * Q5：Tomcat 的类加载机制是怎样的？
     *
     * Tomcat 的类加载器层次：
     * Bootstrap
     *   └── System（Application）
     *        └── Common（加载 tomcat/lib 下的公共 jar）
     *             ├── Catalina（加载 Tomcat 自身类）
     *             └── Shared（加载所有 Web 应用共享的类）
     *                  └── WebApp（每个 Web 应用独立的类加载器，加载 WEB-INF/classes + lib）
     *
     * 核心特点：
     * - 每个 Web 应用独立的 WebAppClassLoader → 应用隔离
     * - WebAppClassLoader 打破双亲委派：先自己加载，特定类才委托
     * - 不同 Web 应用的同类不同版本互不冲突
     */
    static void q5_tomcatClassLoader() {
        System.out.println("=== Q5：Tomcat 类加载机制 ===");
        System.out.println();
        System.out.println("Tomcat 类加载器层次：");
        System.out.println("  Bootstrap");
        System.out.println("    └── System (AppCL)");
        System.out.println("         └── CommonCL      (tomcat/lib/*.jar)");
        System.out.println("              ├── CatalinaCL (Tomcat 自身)");
        System.out.println("              └── SharedCL   (共享 jar)");
        System.out.println("                   └── WebAppCL-1 (WEB-INF/classes + lib)");
        System.out.println("                   └── WebAppCL-2 (WEB-INF/classes + lib)");
        System.out.println();
        System.out.println("WebAppClassLoader 加载顺序（打破双亲委派）：");
        System.out.println("  ① 本地缓存查找");
        System.out.println("  ② J2SE 类 → 委托 Bootstrap（不能打破）");
        System.out.println("  ③ WEB-INF/classes → 自己加载");
        System.out.println("  ④ WEB-INF/lib/*.jar → 自己加载");
        System.out.println("  ⑤ 都没找到 → 委托父加载器（Common → App → Bootstrap）");
        System.out.println();
        System.out.println("面试加分：为什么步骤②必须先委托 Bootstrap？");
        System.out.println("  防止核心类被替换（如 javax.servlet.* 在 Tomcat lib 中，");
        System.out.println("  但 Web 应用也带了 servlet-api.jar → 可能导致 ClassCastException）");
        System.out.println();
    }

    /**
     * Q6：SPI 如何打破双亲委派？
     *
     * SPI（Service Provider Interface）：
     * - 接口定义在核心库（Bootstrap 加载）
     * - 实现在第三方 jar（AppCL 加载）
     * - Bootstrap 加载的类无法引用 AppCL 加载的类 → 需要"反向"加载
     *
     * 解决：线程上下文类加载器（Thread Context ClassLoader）
     * - 默认是 AppCL
     * - ServiceLoader 通过 TCCL 加载实现类
     *
     * 经典案例：JDBC 驱动加载
     * - java.sql.Driver 接口在 java.sql 模块（Platform 加载）
     * - MySQL Driver 实现类在 mysql-connector-java.jar（AppCL 加载）
     * - DriverManager 通过 ServiceLoader + TCCL 加载实现
     */
    static void q6_spiBreakDelegation() {
        System.out.println("=== Q6：SPI 如何打破双亲委派？===");
        System.out.println();
        System.out.println("问题：");
        System.out.println("  java.sql.Driver 接口 → Platform ClassLoader 加载");
        System.out.println("  com.mysql.cj.jdbc.Driver 实现 → AppCL 加载");
        System.out.println("  Platform 加载的类看不到 AppCL 加载的类！");
        System.out.println();
        System.out.println("解决：线程上下文类加载器（TCCL）");
        System.out.println("  Thread.currentThread().getContextClassLoader()");
        System.out.println("  TCCL 默认值 = AppCL（可通过 setContextClassLoader 修改）");
        System.out.println();
        System.out.println("ServiceLoader 加载流程：");
        System.out.println("  ServiceLoader.load(Driver.class)");
        System.out.println("    → 获取当前线程的 TCCL（AppCL）");
        System.out.println("    → 读取 META-INF/services/java.sql.Driver 文件");
        System.out.println("    → 用 TCCL（AppCL）反射加载文件中的具体实现类");
        System.out.println();
        System.out.println("JDBC 4.0+ 自动加载（META-INF/services 文件）：");
        System.out.println("  mysql-connector-java.jar");
        System.out.println("    └── META-INF/services/java.sql.Driver");
        System.out.println("        内容：com.mysql.cj.jdbc.Driver");
        System.out.println();
    }
}