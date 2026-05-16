package base.oop.interfaces;

/**
 * {@code @Package} base.oop.interfaces
 * {@code @Project} custom-collection
 * {@code @Filename} Son
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  00:56
 * {@code @Description} TODO
 */


public class Son implements Father {
    @Override
    public void method() {

    }

    @Override
    public void abstractMethod() {

    }

    // 可选重写默认方法
    @Override
    public void defaultMethod() {
        System.out.println("defaultMethod");
    }



    public void test1(){
        // 调用父类的静态方法
        Father.staticMethod();
        // 可选直接调用默认方法
        defaultMethod();
    }


}
