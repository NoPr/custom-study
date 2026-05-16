package jvm.classLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.SecureClassLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 {@code @Package} day.day03.jvm.classLoader
 {@code @Project} custom-collection-java
 {@code @Filename} ClassLoaderDemo4_SecureCustomClassLoader
 {@code @Author} 18991/NoPr
 {@code @Date} 2025/7/16  22:52
 {@code @Description} 自定义类加载器-加载jar包中的class
*/


public class ClassLoaderDemo4_CustomClassLoader extends SecureClassLoader {

    private final PermissionCollection permissions;

    private final String jarPath;

    public ClassLoaderDemo4_CustomClassLoader(PermissionCollection perms, String jarPath, ClassLoader parent) {
        super(parent);
        this.permissions = perms;
        this.jarPath = jarPath;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            // 从 JAR 读取类字节码
            byte[] classBytes = loadClassFromJar(name);
            // 调⽤defineClass方法,将⼆进制数组转化成⼀个JVM中的类。
            return defineClass(name, classBytes, 0, classBytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException("Class not found: " + name, e);
        }
    }

    private byte[] loadClassFromJar(String className) throws IOException {
        String classPath = className.replace('.', '/') + ".class";
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

            ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
            ClassLoaderDemo4_CustomClassLoader urlClassLoader = new ClassLoaderDemo4_CustomClassLoader(
                    permissions,"C:/Users/18991/Desktop/custom-study-1.0-SNAPSHOT.jar",
                    parentLoader);

            // 3. 加载并使用类
            Class<?> aClass = urlClassLoader.loadClass("day.day03.jvm.classLoader.LoaderClassTest");
            Object instance = aClass.getDeclaredConstructor().newInstance();
            Class<?> instanceClass = instance.getClass();
            // 4. 调用方法
            Method stringsMemory = instanceClass.getMethod("testLoaderClass3");
            stringsMemory.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
