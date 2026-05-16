package base.oop.extend;

/**
 * {@code @Package} base.oop.extend
 * {@code @Project} custom-collection
 * {@code @Filename} GrandFather
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/12  20:58
 * {@code @Description} 父类，保护方法，子类可以调用，但是不能直接调用
 */


public class GrandFather {

    protected int age;

    // final 修饰的属性不能被重写
    public final int wifeAge = 101;

    GrandFather(int age){
        this.age = age;
    }

    protected void method() {
        System.out.printf("GrandFather method" + age);
    }

    public int getAge() {
        return age;
    }

    private void method2() {
        System.out.println("GrandFather method2");
    }

    public int getWifeAge() {
        return wifeAge;
    }

    public static void method4(){
        System.out.println("GrandFather method4");
    }

    public final void method3(){
        System.out.printf("GrandFather method3");
    }
}
