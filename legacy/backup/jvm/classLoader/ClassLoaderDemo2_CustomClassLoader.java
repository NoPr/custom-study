package jvm.classLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code @Package} day.day03.jvm.classLoader
 * {@code @Project} custom-collection
 * {@code @Filename} classLoaderDemo2_CustomClassLoader
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2025/7/16  15:04
 * {@code @Description} 自定义类加载器-继承ClassLoader
 */


/*
 * 自定义类加载器
 * 1. 继承ClassLoader
 * 2. 重写findClass方法
 *
 * 重写findClass方法可以实现加密效果
 *
*/

public class ClassLoaderDemo2_CustomClassLoader extends ClassLoader implements AutoCloseable {

    private final String jarPath;

    public ClassLoaderDemo2_CustomClassLoader(String jarPath, ClassLoader parent) {
        super(parent);
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
//        ClassLoader parentLoader = ClassLoaderDemo2_CustomClassLoader.class.getClassLoader();
        ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
        try (ClassLoaderDemo2_CustomClassLoader customClassLoader = new ClassLoaderDemo2_CustomClassLoader(
                "C:/Users/18991/Desktop/custom-study-1.0-SNAPSHOT.jar",
                parentLoader)){

            // 加载并使用类
            Class<?> aClass = customClassLoader.loadClass("day.day03.jvm.HeapAStack");
            Object instance = aClass.getDeclaredConstructor().newInstance();
            Class<?> instanceClass = instance.getClass();
            // 调用方法
            Method stringsMemory = instanceClass.getMethod("StringsMemory3");
            stringsMemory.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {

    }
}