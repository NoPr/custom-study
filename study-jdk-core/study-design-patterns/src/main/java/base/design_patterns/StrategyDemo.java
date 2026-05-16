package base.design_patterns;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略模式 — 定义算法族，分别封装，让它们可以互相替换
 *
 * 核心知识点：
 * 1. 策略模式 = Context（上下文）+ Strategy（策略接口）+ ConcreteStrategy（具体策略）
 * 2. 符合开闭原则：新增策略无需修改 Context
 * 3. 消除大量 if-else / switch：用策略映射替代条件分支
 * 4. 与状态模式的区别：
 *    - 策略模式：客户端主动选择策略，策略之间互不知晓
 *    - 状态模式：状态自动流转，状态之间可能相互引用
 * 5. 典型应用：Comparator、ThreadPoolExecutor 的 RejectedExecutionHandler、Spring 的 Resource
 */
public class StrategyDemo {

    public static void main(String[] args) {
        System.out.println("=== 策略模式：优惠计算 ===");

        ShoppingCart cart = new ShoppingCart();
        cart.addItem(new Item("Java 编程思想", BigDecimal.valueOf(108.00)));
        cart.addItem(new Item("Effective Java", BigDecimal.valueOf(119.00)));
        cart.addItem(new Item("设计模式", BigDecimal.valueOf(85.00)));

        System.out.println("商品列表:");
        for (Item item : cart.getItems()) {
            System.out.println("  " + item.getName() + " - ¥" + item.getPrice());
        }

        DiscountStrategy noDiscount = new NoDiscountStrategy();
        DiscountStrategy percentage = new PercentageDiscountStrategy(BigDecimal.valueOf(0.8));
        DiscountStrategy fixed = new FixedDiscountStrategy(
                BigDecimal.valueOf(200), BigDecimal.valueOf(50));
        DiscountStrategy vip = new VIPDiscountStrategy(BigDecimal.valueOf(0.75));

        cart.setDiscountStrategy(noDiscount);
        System.out.println("\n无优惠: ¥" + cart.calculateTotal());

        cart.setDiscountStrategy(percentage);
        System.out.println("8折优惠: ¥" + cart.calculateTotal());

        cart.setDiscountStrategy(fixed);
        System.out.println("满200减50: ¥" + cart.calculateTotal());

        cart.setDiscountStrategy(vip);
        System.out.println("VIP 75折: ¥" + cart.calculateTotal());

        System.out.println("\n=== 策略模式消除 if-else ===");
        PaymentProcessor processor = new PaymentProcessor();
        processor.pay(PaymentMethod.ALIPAY, BigDecimal.valueOf(100));
        processor.pay(PaymentMethod.WECHAT, BigDecimal.valueOf(200));
        processor.pay(PaymentMethod.CREDIT_CARD, BigDecimal.valueOf(300));
    }
}

/** 商品 */
class Item {
    private final String name;
    private final BigDecimal price;

    Item(String name, BigDecimal price) {
        this.name = name;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }
}

/** 购物车 — Context */
class ShoppingCart {
    private final java.util.ArrayList<Item> items = new java.util.ArrayList<>();
    private DiscountStrategy discountStrategy = new NoDiscountStrategy();

    void addItem(Item item) {
        items.add(item);
    }

    void setDiscountStrategy(DiscountStrategy strategy) {
        this.discountStrategy = strategy;
    }

    BigDecimal calculateTotal() {
        BigDecimal total = items.stream()
                .map(Item::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return discountStrategy.apply(total);
    }

    List<Item> getItems() {
        return items;
    }
}

/** 策略接口 */
interface DiscountStrategy {
    BigDecimal apply(BigDecimal originalPrice);
}

/** 无优惠 */
class NoDiscountStrategy implements DiscountStrategy {
    @Override
    public BigDecimal apply(BigDecimal originalPrice) {
        return originalPrice;
    }
}

/** 百分比折扣 */
class PercentageDiscountStrategy implements DiscountStrategy {
    private final BigDecimal rate;

    PercentageDiscountStrategy(BigDecimal rate) {
        this.rate = rate;
    }

    @Override
    public BigDecimal apply(BigDecimal originalPrice) {
        return originalPrice.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

/** 满减 */
class FixedDiscountStrategy implements DiscountStrategy {
    private final BigDecimal threshold;
    private final BigDecimal discount;

    FixedDiscountStrategy(BigDecimal threshold, BigDecimal discount) {
        this.threshold = threshold;
        this.discount = discount;
    }

    @Override
    public BigDecimal apply(BigDecimal originalPrice) {
        if (originalPrice.compareTo(threshold) >= 0) {
            return originalPrice.subtract(discount);
        }
        return originalPrice;
    }
}

/** VIP 折扣 */
class VIPDiscountStrategy implements DiscountStrategy {
    private final BigDecimal vipRate;

    VIPDiscountStrategy(BigDecimal vipRate) {
        this.vipRate = vipRate;
    }

    @Override
    public BigDecimal apply(BigDecimal originalPrice) {
        return originalPrice.multiply(vipRate).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

/** ============ 支付方式：策略模式替代 if-else ============ */

enum PaymentMethod {
    ALIPAY, WECHAT, CREDIT_CARD
}

interface PaymentStrategy {
    void pay(BigDecimal amount);
}

class AlipayStrategy implements PaymentStrategy {
    @Override
    public void pay(BigDecimal amount) {
        System.out.println("  支付宝支付: ¥" + amount);
    }
}

class WechatStrategy implements PaymentStrategy {
    @Override
    public void pay(BigDecimal amount) {
        System.out.println("  微信支付: ¥" + amount);
    }
}

class CreditCardStrategy implements PaymentStrategy {
    @Override
    public void pay(BigDecimal amount) {
        System.out.println("  信用卡支付: ¥" + amount);
    }
}

class PaymentProcessor {
    private final java.util.Map<PaymentMethod, PaymentStrategy> strategyMap =
            new java.util.HashMap<>();

    PaymentProcessor() {
        strategyMap.put(PaymentMethod.ALIPAY, new AlipayStrategy());
        strategyMap.put(PaymentMethod.WECHAT, new WechatStrategy());
        strategyMap.put(PaymentMethod.CREDIT_CARD, new CreditCardStrategy());
    }

    void pay(PaymentMethod method, BigDecimal amount) {
        PaymentStrategy strategy = strategyMap.get(method);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的支付方式: " + method);
        }
        strategy.pay(amount);
    }
}