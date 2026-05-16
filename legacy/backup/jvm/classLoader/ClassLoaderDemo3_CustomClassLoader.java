package jvm.classLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.*;
import java.security.cert.Certificate;
import java.util.PropertyPermission;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code @Package} day.day03.jvm.classLoader
 * {@code @Project} custom-collection
 * {@code @Filename} classLoaderDemo3_CustomClassLoader
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2025/7/16  15:49
 * {@code @Description} 带保护域的自定义类加载器-继承SecureClassLoader
 */


public class ClassLoaderDemo3_CustomClassLoader extends SecureClassLoader {

    private final PermissionCollection permissions;

    private final String jarPath;

    public ClassLoaderDemo3_CustomClassLoader(PermissionCollection perms, String jarPath, ClassLoader parent) {
        super(parent);
        this.permissions = perms;
        this.jarPath = jarPath;
    }

    @Override
    protected Class<?> findClass(String name) {
        try {
            byte[] bytes = loadClassBytes(name);
            CodeSource cs = new CodeSource(null, (Certificate[]) null);
            permissions.add(new RuntimePermission("accessDeclaredMembers"));
            // 创建类加载器
            permissions.add(new RuntimePermission("createClassLoader"));
            permissions.add(new PropertyPermission("java.version", "read"));
            ProtectionDomain pd = new ProtectionDomain(cs, permissions);
            // 使用带保护域的defineClass
            return defineClass(name, bytes, 0, bytes.length, pd);
        }catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 加载类字节码
     * @param className class name
     * @return class bytes
     * @throws IOException IO exception
     */
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

            ClassLoader parentLoader = ClassLoaderDemo3_CustomClassLoader.class.getClassLoader();
//            ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
            ClassLoaderDemo3_CustomClassLoader urlClassLoader = new ClassLoaderDemo3_CustomClassLoader(
                    permissions,"C:/Users/18991/Desktop/custom-study-1.0-SNAPSHOT.jar",
                    parentLoader);

            // 3. 加载并使用类
            Class<?> aClass = urlClassLoader.loadClass("day.day03.jvm.HeapAStack");
            Object instance = aClass.getDeclaredConstructor().newInstance();
            Class<?> instanceClass = instance.getClass();
            // 4. 调用方法
            Method stringsMemory = instanceClass.getMethod("StringsMemory2");
            stringsMemory.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
