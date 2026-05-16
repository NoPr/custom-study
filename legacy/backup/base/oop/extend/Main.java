package base.oop.extend;

/**
 * {@code @Package} base.oop.extend
 * {@code @Project} custom-collection
 * {@code @Filename} Main
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/12  21:03
 * {@code @Description} 测试启动类
 */


public class Main {

    public static void main(String[] args) {
        Main main = new Main();
//        main.test1();
        main.test2();
    }


    public void test1(){
        Father father = new Father(100);
        System.out.println(father.getAge());
    }

    public void test2(){
        // 向上转型：父类引用指向子类对象，实例化的仍旧是son类。指向的对象（即实际创建的实例）是 Son 类的实例
        GrandFather son = new Son(200);

        // 子类没有重写父类的方法，所以调用的是父类的方法
        son.method();

        // 当前引用是 GrandFather 类型，所以调用的是 GrandFather 类的方法
//        son.getMoney(); 编译错误，因为编译器认为 son 是 GrandFather 类型，所以不能调用 Son 类的方法

        // 向下转型：强制类型转换,把父类引用转换为子类引用，才能调用子类的方法
        if ( son instanceof Son) {
            Son s = (Son) son;
            s.getMoney();
        }
    }
}
