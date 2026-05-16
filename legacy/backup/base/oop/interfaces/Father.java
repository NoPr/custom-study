package base.oop.interfaces;

/**
 * {@code @Package} base.oop.interfaces
 * {@code @Project} custom-collection
 * {@code @Filename} Father
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  00:54
 * {@code @Description} 父类接口
 */


public interface Father {

    int a = 10;

    void method();

    // 默认为public abstract
    public abstract void abstractMethod();


    static void staticMethod() {
        System.out.println("staticMethod");
    }

    default void defaultMethod() {
        System.out.println("defaultMethod");
    }

    private void privateMethod() {
        System.out.println("privateMethod");
    }

    public static void main(String[] args) {
        staticMethod();
    }
}
