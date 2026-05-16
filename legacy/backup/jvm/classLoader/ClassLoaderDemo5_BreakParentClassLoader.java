package jvm.classLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code @Package} day.day03.jvm.classLoader
 * {@code @Project} custom-collection-java
 * {@code @Filename} ClassLoaderDemo4_BreakParentClassLoader
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2025/7/16  23:42
 * {@code @Description} 打破双亲委派
 */


public class ClassLoaderDemo5_BreakParentClassLoader extends ClassLoader {


    private final PermissionCollection permissions;

    private final String jarPath;

    // ? 隔离包列表 这样写为什么不行？
    private final Set<String> isolatedPackages = Set.of("day/day03/jvm");

    public ClassLoaderDemo5_BreakParentClassLoader(PermissionCollection perms, String jarPath, ClassLoader parent) {
        super(parent);
        this.permissions = perms;
        this.jarPath = jarPath;
    }

    /**
     *  打破打破双亲委派
     * @param name class name
     * @return
     * @throws ClassNotFoundException
     */
//    @Override
//    public Class<?> loadClass(String name) throws ClassNotFoundException {
//        System.out.println(name);
//        // 可以对指定类实现自定义寻找父加载器,避免所有类都打破委派
//    /*    if (name.startsWith("day.day03.jvm")){
//            return this.findClass(name);
//        }else {
//            return super.loadClass(name);
//        }*/
//
//
//        // 1. 检查是够已经加载过当前类
////        Class<?> aClass = null;
////        synchronized (getClassLoadingLock(name)){
////            aClass = findLoadedClass(name);
////            if (aClass == null){
////                aClass =findClass(name);
////                if (aClass == null){
////                    return super.loadClass(name);
////                }
////            }
////        }
////        return aClass;
//        // 仅对A类（com.example.A）应用自定义加载逻辑
//        if (name.startsWith("day.day03.jvm")) {
//            return findClass(name); // 直接调用findClass，不委托父类
//        }
//        // 其他类仍遵循双亲委派
//        return super.loadClass(name);
//    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 获取类加载锁
        synchronized (getClassLoadingLock(name)) {
            // 检查是否已加载
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 检查是否在隔离包中
            boolean shouldIsolate = isolatedPackages.stream().anyMatch(name::startsWith);

            if (shouldIsolate) {
                try {
                    return findClass(name); // 优先自己加载
                } catch (ClassNotFoundException e) {
                    // 继续尝试父加载器
                }
            }

            // 尝试父加载器
            return super.loadClass(name, resolve);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            byte[] bytes = loadClassBytes(name);
            CodeSource cs = new CodeSource(null, (Certificate[]) null);
//            permissions.add(new RuntimePermission("accessDeclaredMembers"));
            // 创建类加载器
//            permissions.add(new RuntimePermission("createClassLoader"));
//            permissions.add(new PropertyPermission("java.version", "read"));

            ProtectionDomain pd = new ProtectionDomain(cs, permissions);
            // 使用带保护域的defineClass
            return defineClass(name, bytes, 0, bytes.length, pd);
        }catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] loadClassBytes(String className) throws IOException {
        String classPath = className.replace('.', '/') + ".class";
        System.out.println(classPath);
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(classPath);
            try (InputStream is = jar.getInputStream(entry)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int bytesRead;
                // 将jar文件内容读取到内存
                byte[] data = new byte[4096];
                while ((bytesRead = is.read(data)) != -1) {
                    buffer.write(data, 0, bytesRead);
                }
                return buffer.toByteArray();
            }
        }
    }

    public static void main(String[] args) {
        try {
            // 2. 创建类加载器（使用当前类加载器作为父加载器），也可以使用默认类加载器
            Permissions permissions = new Permissions();
            // 明确拒绝危险权限,设为只读防止修改
            permissions.setReadOnly();

            ClassLoader parentLoader = ClassLoaderDemo5_BreakParentClassLoader.class.getClassLoader();
//            ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
            ClassLoaderDemo5_BreakParentClassLoader urlClassLoader = new ClassLoaderDemo5_BreakParentClassLoader(
                    permissions,"E:/3-PROJECT/Project Java/MySelf/custom-collection-java/custom-study/target/custom-study-1.0-SNAPSHOT.jar",
                    parentLoader);

            // 3. 加载并使用类
            Class<?> aClass = urlClassLoader.loadClass("day.day03.jvm.HeapAStack");
            Object instance = aClass.getDeclaredConstructor().newInstance();
            Class<?> instanceClass = instance.getClass();
            // 4. 调用方法
            Method stringsMemory = instanceClass.getMethod("StringsMemory");
            stringsMemory.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
