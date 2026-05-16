package base.oop.extend;

/**
 * {@code @Package} base.oop.extend
 * {@code @Project} custom-collection
 * {@code @Filename} Sone
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/12  20:57
 * {@code @Description} 子类
 */


public class Son extends GrandFather {

    private int age;

    Son(int age) {
        super(age);
        this.age = age;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    private int money;


    public int getMoney() {
        return money;
    }

    public void setMoney(int money) {
        this.money = money;
    }
}
