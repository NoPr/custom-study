package base.design_patterns;

/**
 * 工厂方法模式 — 将对象创建延迟到子类
 *
 * 核心知识点：
 * 1. 定义创建对象的接口，让子类决定实例化哪一个类
 * 2. 符合开闭原则：新增产品只需新增具体工厂，无需修改已有代码
 * 3. 简单工厂 vs 工厂方法 vs 抽象工厂：
 *    - 简单工厂：一个工厂生产所有产品（违反开闭原则）
 *    - 工厂方法：一个工厂对应一个产品（符合开闭原则）
 *    - 抽象工厂：一个工厂生产一族产品
 */
public class FactoryMethodDemo {

    public static void main(String[] args) {
        System.out.println("=== 工厂方法模式 ===");

        AnimalFactory dogFactory = new DogFactory();
        AnimalFactory catFactory = new CatFactory();

        Animal dog = dogFactory.createAnimal();
        Animal cat = catFactory.createAnimal();

        dog.speak();
        cat.speak();

        System.out.println("\n=== 简单工厂 vs 工厂方法 ===");
        Animal dog2 = SimpleAnimalFactory.createAnimal("dog");
        Animal cat2 = SimpleAnimalFactory.createAnimal("cat");
        dog2.speak();
        cat2.speak();

        System.out.println("\n=== 开闭原则演示：新增产品无需修改已有代码 ===");
        AnimalFactory birdFactory = new BirdFactory();
        Animal bird = birdFactory.createAnimal();
        bird.speak();
    }
}

/** 产品接口 */
interface Animal {
    void speak();
}

/** 具体产品：狗 */
class Dog implements Animal {
    @Override
    public void speak() {
        System.out.println("Dog: 汪汪汪！");
    }
}

/** 具体产品：猫 */
class Cat implements Animal {
    @Override
    public void speak() {
        System.out.println("Cat: 喵喵喵！");
    }
}

/** 具体产品：鸟（新增产品，不修改已有代码） */
class Bird implements Animal {
    @Override
    public void speak() {
        System.out.println("Bird: 叽叽喳喳！");
    }
}

/** 工厂接口 */
interface AnimalFactory {
    Animal createAnimal();
}

/** 具体工厂：狗工厂 */
class DogFactory implements AnimalFactory {
    @Override
    public Animal createAnimal() {
        return new Dog();
    }
}

/** 具体工厂：猫工厂 */
class CatFactory implements AnimalFactory {
    @Override
    public Animal createAnimal() {
        return new Cat();
    }
}

/** 具体工厂：鸟工厂（新增工厂，不修改已有代码） */
class BirdFactory implements AnimalFactory {
    @Override
    public Animal createAnimal() {
        return new Bird();
    }
}

/** 简单工厂 — 所有创建逻辑集中在一个方法中，新增产品需修改此方法 */
class SimpleAnimalFactory {
    public static Animal createAnimal(String type) {
        switch (type) {
            case "dog":
                return new Dog();
            case "cat":
                return new Cat();
            default:
                throw new IllegalArgumentException("未知动物类型: " + type);
        }
    }
}