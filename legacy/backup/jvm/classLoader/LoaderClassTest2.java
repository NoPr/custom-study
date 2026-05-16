package jvm.classLoader;

/**
 * {@code @Package} day.day03.jvm.classLoader
 * {@code @Project} custom-collection
 * {@code @Filename} LoaderClassTest
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2025/7/17  09:54
 * {@code @Description} 外部jar包导入测试
 */


public class LoaderClassTest2 {

    public void testLoaderClass() throws ClassNotFoundException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        System.out.println("------");
        System.out.println(contextClassLoader);
        System.out.println(contextClassLoader.getParent());
        System.out.println(contextClassLoader.getParent().getParent());
        System.out.println("------");
    }
}
