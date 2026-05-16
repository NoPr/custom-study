package jvm.classLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * {@code @Package} day.day03.jvm
 * {@code @Project} custom-collection
 * {@code @Filename} ClassLoaderDemo1_SystemClassLoader
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2025/7/11  17:04
 * {@code @Description} 系统类加载器导入jar包
 */


public class ClassLoaderDemo1_SystemClassLoader {

    public static void main(String[] args) throws FileNotFoundException {

        File jarFile = new File("C:/Users/18991/Desktop/custom-study-1.0-SNAPSHOT.jar");
        if (!jarFile.exists()) {
            throw new FileNotFoundException("JAR file not found: " + jarFile.getAbsolutePath());
        }

        try {
            // 定义外部jar路径
            URL[] urls = new URL[]{
                    new URL("file:/C:/Users/18991/Desktop/custom-study-1.0-SNAPSHOT.jar")
            };

            // 2. 创建类加载器（使用当前类加载器作为父加载器），也可以使用默认类加载器
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            URLClassLoader urlClassLoader = new URLClassLoader(urls,null);

            // 3. 加载并使用类
            Class<?> aClass = urlClassLoader.loadClass("day.day03.jvm.HeapAStack");

            // 打印所有公共方法
            System.out.println("Public methods in HeapAStack:");
            for (Method m : aClass.getMethods()) {
                System.out.println(" - " + m.getName());
            }

            Object instance = aClass.getDeclaredConstructor().newInstance();
            Class<?> instanceClass = instance.getClass();
            // 4. 调用方法
            Method stringsMemory = instanceClass.getMethod("StringsMemory3");
            stringsMemory.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
