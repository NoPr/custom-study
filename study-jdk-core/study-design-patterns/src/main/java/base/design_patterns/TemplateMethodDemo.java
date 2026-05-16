package base.design_patterns;

/**
 * 模板方法模式 — 定义算法骨架，将具体步骤延迟到子类
 *
 * 核心知识点：
 * 1. 父类定义模板方法（final），子类实现抽象步骤（hook methods）
 * 2. 好莱坞原则："Don't call us, we'll call you" — 父类调用子类，子类不调用父类
 * 3. 钩子方法（hook）：提供默认实现的步骤方法，子类可选择性地覆写
 * 4. 典型应用：
 *    - InputStream.read() — 模板方法，read(byte[], int, int) 内部调用 read()
 *    - AbstractQueuedSynchronizer — AQS 模板方法模式
 *    - JdbcTemplate、RestTemplate — Spring 大量使用
 *    - HttpServlet.service() — doGet/doPost 由子类实现
 * 5. 模板方法 vs 策略模式：
 *    - 模板方法用继承（编译期确定），策略模式用组合（运行时切换）
 *    - 模板方法适合流程固定但步骤可变，策略适合算法整体替换
 */
public class TemplateMethodDemo {

    public static void main(String[] args) {
        System.out.println("=== 模板方法模式：饮料制作 ===");

        BeverageMaker coffee = new CoffeeMaker();
        BeverageMaker tea = new TeaMaker();
        BeverageMaker milkTea = new MilkTeaMaker();

        coffee.makeBeverage();

        System.out.println();

        tea.makeBeverage();

        System.out.println();

        milkTea.makeBeverage();

        System.out.println("\n=== 钩子方法演示 ===");
        BeverageMaker coffeeNoSugar = new CoffeeMaker() {
            @Override
            protected boolean customerWantsSugar() {
                return false;
            }
        };
        coffeeNoSugar.makeBeverage();
    }
}

/**
 * 饮料制作模板
 * makeBeverage() 是模板方法（final），定义算法骨架
 * 子类实现具体的步骤方法
 */
abstract class BeverageMaker {

    /** 模板方法 — final 防止子类覆写 */
    public final void makeBeverage() {
        boilWater();
        brew();
        pourInCup();
        if (customerWantsSugar()) {
            addSugar();
        }
        addCondiments();
        System.out.println(name() + "制作完成！");
    }

    private void boilWater() {
        System.out.println("  1. 烧水");
    }

    /** 冲泡 — 子类实现 */
    protected abstract void brew();

    private void pourInCup() {
        System.out.println("  3. 倒入杯中");
    }

    /** 钩子方法：是否需要加糖（默认需要） */
    protected boolean customerWantsSugar() {
        return true;
    }

    private void addSugar() {
        System.out.println("  4. 加糖");
    }

    /** 添加调味料 — 子类实现 */
    protected abstract void addCondiments();

    protected abstract String name();
}

class CoffeeMaker extends BeverageMaker {
    @Override
    protected void brew() {
        System.out.println("  2. 用沸水冲泡咖啡粉");
    }

    @Override
    protected void addCondiments() {
        System.out.println("  5. 加入牛奶");
    }

    @Override
    protected String name() {
        return "☕ 咖啡";
    }
}

class TeaMaker extends BeverageMaker {
    @Override
    protected void brew() {
        System.out.println("  2. 用沸水浸泡茶叶");
    }

    @Override
    protected void addCondiments() {
        System.out.println("  5. 加入柠檬");
    }

    @Override
    protected String name() {
        return "🍵 茶";
    }
}

class MilkTeaMaker extends BeverageMaker {
    @Override
    protected void brew() {
        System.out.println("  2. 用沸水冲泡红茶 + 加入炼乳");
    }

    @Override
    protected void addCondiments() {
        System.out.println("  5. 加入珍珠");
    }

    @Override
    protected boolean customerWantsSugar() {
        return false;
    }

    @Override
    protected String name() {
        return "🧋 珍珠奶茶";
    }
}