package jvm.classLoader;

/**
 {@code @Package} day.day03.jvm.classLoader
 {@code @Project} custom-collection-java
 {@code @Filename} LoaderDemo
 {@code @Author} 18991/NoPr
 {@code @Date} 2025/7/8  22:32
 {@code @Description} 类加载器的加载机制
*/


public class LoaderDemo {

    public static void main(String[] args) throws ClassNotFoundException{

        ClassLoader cl1 = LoaderDemo.class.getClassLoader();

        System.out.println("classLoader"+cl1);  //classLoaderjdk.internal.loader.ClassLoaders$AppClassLoader@63947c6b

        System.out.println("parent of classLoader"+cl1.getParent());    //parent of classLoaderjdk.internal.loader.ClassLoaders$PlatformClassLoader@3d075dc0

        System.out.println("grant parent of classLoader"+cl1.getParent().getParent());

        // String,Int等基础类由BootStrap Classloader加载。
        ClassLoader cl2 = String.class.getClassLoader();
        System.out.println("cl2 > " + cl2); //null
        System.out.println(cl1.loadClass("java.util.List").getClass().getClassLoader());    //null



        // 获取系统类加载器（默认用户类加载器）
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        System.out.println("System ClassLoader: " + systemLoader);

        // 获取其父加载器 -> Platform ClassLoader
        ClassLoader platformLoader = systemLoader.getParent();
        System.out.println("Platform ClassLoader: " + platformLoader);

        // 再获取父加载器 -> Bootstrap 显示为 null（JVM 实现）
        ClassLoader bootstrapLoader = platformLoader.getParent();
        System.out.println("Bootstrap ClassLoader: " + bootstrapLoader); // 输出 null

    }
}
