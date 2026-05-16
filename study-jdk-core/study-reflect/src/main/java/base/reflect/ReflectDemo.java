package base.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射核心 API 演示
 *
 * 核心知识点：
 * 1. 获取 Class 三种方式（Class.forName / .class / getClass()）
 * 2. 反射创建对象（Constructor.newInstance + setAccessible）
 * 3. 操作私有字段（Field + setAccessible）
 * 4. invoke 私有方法
 * 5. 反射破坏单例 + 防御
 */
public class ReflectDemo {

    public static void main(String[] args) throws Exception {
        getClassThreeWays();
        createInstanceByReflect();
        accessPrivateField();
        invokePrivateMethod();
        breakSingleton();
        defendSingleton();
    }

    /**
     * 获取 Class 对象的三种方式
     *
     * 方式 1：Class.forName("全限定类名") — 适用于配置文件读取类名
     * 方式 2：类名.class — 方法参数传递时使用
     * 方式 3：对象.getClass() — 已有对象实例时使用
     */
    static void getClassThreeWays() throws ClassNotFoundException {
        System.out.println("=== 获取 Class 对象的三种方式 ===");

        Class<?> clazz1 = Class.forName("base.reflect.Person");
        System.out.println("方式 1 (Class.forName): " + clazz1.getName());

        Class<Person> clazz2 = Person.class;
        System.out.println("方式 2 (.class):       " + clazz2.getName());

        Person person = new Person("张三", 25);
        Class<? extends Person> clazz3 = person.getClass();
        System.out.println("方式 3 (getClass()):   " + clazz3.getName());

        System.out.println("三个 Class 对象是否相同？ " +
                (clazz1 == clazz2 && clazz2 == clazz3));
        System.out.println();
    }

    /**
     * 反射创建对象
     * - 无参构造：Class.newInstance()（JDK 9+ 已废弃，推荐 Constructor.newInstance()）
     * - 有参构造：getDeclaredConstructor( paramTypes ).newInstance( args )
     * - 私有构造：constructor.setAccessible(true) 后调用
     */
    static void createInstanceByReflect() throws Exception {
        System.out.println("=== 反射创建对象 ===");

        Class<Person> clazz = Person.class;

        Constructor<Person> noArgConstructor = clazz.getDeclaredConstructor();
        noArgConstructor.setAccessible(true);
        Person p1 = noArgConstructor.newInstance();
        System.out.println("私有无参构造创建: " + p1);

        Constructor<Person> argConstructor = clazz.getDeclaredConstructor(String.class, int.class);
        Person p2 = argConstructor.newInstance("李四", 30);
        System.out.println("有参构造创建:     " + p2);

        System.out.println();
    }

    /**
     * 反射操作私有字段
     * getDeclaredField + setAccessible(true)
     */
    static void accessPrivateField() throws Exception {
        System.out.println("=== 反射操作私有字段 ===");

        Person person = new Person("王五", 28);
        System.out.println("修改前: " + person);

        Class<? extends Person> clazz = person.getClass();

        Field nameField = clazz.getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(person, "赵六");
        System.out.println("修改 name 后: " + person);

        Field ageField = clazz.getDeclaredField("age");
        ageField.setAccessible(true);
        int age = (int) ageField.get(person);
        System.out.println("读取私有 age 字段值: " + age);

        System.out.println();
    }

    /**
     * 反射调用私有方法
     * getDeclaredMethod + setAccessible(true) + invoke
     */
    static void invokePrivateMethod() throws Exception {
        System.out.println("=== 反射调用私有/公有方法 ===");

        Person person = new Person("钱七", 22);

        Class<? extends Person> clazz = person.getClass();

        Method publicMethod = clazz.getDeclaredMethod("sayHello");
        publicMethod.invoke(person);

        Method privateMethod = clazz.getDeclaredMethod("secret");
        privateMethod.setAccessible(true);
        String result = (String) privateMethod.invoke(person);
        System.out.println("私有方法 secret() 返回: " + result);

        Method argMethod = clazz.getDeclaredMethod("greet", String.class);
        argMethod.invoke(person, "大家好");

        System.out.println();
    }

    /**
     * 反射破坏单例
     * 通过反射获取私有构造器 → setAccessible(true) → 创建新实例
     */
    static void breakSingleton() throws Exception {
        System.out.println("=== 反射破坏单例 ===");

        Singleton instance1 = Singleton.getInstance();
        System.out.println("instance1: " + instance1);

        Constructor<Singleton> constructor = Singleton.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Singleton instance2 = constructor.newInstance();
        System.out.println("instance2 (反射创建): " + instance2);

        System.out.println("两个实例是否相同？ " + (instance1 == instance2));
        System.out.println("→ 单例被破坏了！");
        System.out.println();
    }

    /**
     * 防御反射破坏单例
     * 在私有构造器中检测：若已存在实例则抛出异常
     */
    static void defendSingleton() throws Exception {
        System.out.println("=== 防御反射破坏单例 ===");

        DefendedSingleton instance1 = DefendedSingleton.getInstance();
        System.out.println("instance1: " + instance1);

        Constructor<DefendedSingleton> constructor =
                DefendedSingleton.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        try {
            DefendedSingleton instance2 = constructor.newInstance();
            System.out.println("instance2: " + instance2);
        } catch (Exception e) {
            System.out.println("反射创建失败: " + e.getCause().getMessage());
            System.out.println("→ 防御成功！");
        }
    }
}

/**
 * 演示用的 Person 类，包含私有字段和方法
 */
class Person {
    private String name;
    private int age;

    private Person() {
        this.name = "默认";
        this.age = 0;
    }

    Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public void sayHello() {
        System.out.println("Hello, 我是 " + name + ", 今年 " + age + " 岁");
    }

    private String secret() {
        return "这是我的秘密: " + name + " 的密码是 pwd123";
    }

    public void greet(String message) {
        System.out.println(name + " 说: " + message);
    }

    @Override
    public String toString() {
        return "Person{name='" + name + "', age=" + age + "}";
    }
}

/**
 * 普通单例 — 可被反射破坏
 */
class Singleton {
    private static final Singleton INSTANCE = new Singleton();

    private Singleton() {}

    public static Singleton getInstance() {
        return INSTANCE;
    }
}

/**
 * 防反射单例 — 私有构造器中检测重复实例化
 * created 字段必须声明在 INSTANCE 之前，否则静态初始化顺序导致标志位被重置
 */
class DefendedSingleton {
    private static volatile boolean created = false;
    private static final DefendedSingleton INSTANCE = new DefendedSingleton();

    private DefendedSingleton() {
        if (created) {
            throw new RuntimeException("单例已被创建，反射攻击被拦截");
        }
        created = true;
    }

    public static DefendedSingleton getInstance() {
        return INSTANCE;
    }
}