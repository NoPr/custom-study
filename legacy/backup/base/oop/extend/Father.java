package base.oop.extend;

/**
 * {@code @Package} base.oop.extend
 * {@code @Project} custom-collection
 * {@code @Filename} Parent
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/12  20:57
 * {@code @Description} 父类
 */


// final 类不能被继承，不能被重写
public  final class Father extends GrandFather {

    private int age = 50;


    Father(int age){
        // 父类存在有参构造方法，子类必须手动调用父类构造方法。
        super(age);
    }

    @Override
    protected void method() {
        // 可以用过调用非私有getter方法获取父类私有属性
        System.out.println("Parent method" + getAge());
        method4();
    }

    // 子类若未定义同名静态方法，则可以直接使用父类的静态方法；若定义了，父类的静态方法就被隐藏
    public static void method4(){
        System.out.println("GrandFather method4");
    }

    // 私有方法不能被重写
    private void method2(){
        System.out.println("Father method2 age " + age);
        System.out.println(getWifeAge());
        System.out.println(super.wifeAge);
        method3();
    }


    // final 方法不能被重写
//    @Override
//    public void method3() {
//        System.out.println("Father method3");
//    }
}
