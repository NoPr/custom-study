package base.clone;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.BeanUtils;

/**
 * @Description 浅拷贝 完整版
 * 核心知识点：
 * 1. 引用赋值 ≠ 浅拷贝（引用赋值不创建新对象）
 * 2. 浅拷贝：创建新对象，复制基本类型值、引用类型的地址（共享对象）
 * 3. 只有修改【引用指向的对象本身】，浅拷贝的对象才会同步变化
 * 4. 本类实现Cloneable，重写clone()，实现自定义对象的浅拷贝
 */
@Getter
@Setter
public class ShallowClone implements Cloneable {

    // 成员变量
    private String name;
    // 引用类型成员：用于演示浅拷贝「共享对象」的核心特性
    private List<String> tags;

    /**
     * 重写本类的clone()方法 → 实现【当前类的浅拷贝】
     */
    @Override
    public ShallowClone clone() throws CloneNotSupportedException {
        // 调用父类克隆方法：浅拷贝核心逻辑
        return (ShallowClone) super.clone();
    }

    public static void main(String[] args) throws CloneNotSupportedException {
        ShallowClone shallowClone = new ShallowClone();

        // 1. 引用赋值演示
        shallowClone.test1();
        System.out.println("-----------------");

        // 2. 数组浅拷贝演示
        shallowClone.test2();
        System.out.println("-----------------");

        // 3. 数组存储自定义对象 → 浅拷贝演示
        shallowClone.test3();
        System.out.println("-----------------");

        // 4. ✅ 核心：调用【本类的clone()方法】实现对象浅拷贝（你要的功能）
        shallowClone.test4();
    }

    /**
     * 引用赋值（不是浅拷贝）
     * 原因：没有创建新对象，两个变量指向同一个数组，修改互相影响
     */
    public void test1() {
        String[] arr = {"a", "b", "c"};
        String[] arr1 = arr;
        arr1[0] = "d";
        System.out.println(Arrays.toString(arr));
        System.out.println(arr == arr1);
    }

    /**
     * 数组浅拷贝
     * 1. clone() 创建新数组对象，地址不同 → == 为false
     * 2. 仅复制引用，String不可变 → 修改原数组引用指向，不影响拷贝数组
     */
    public void test2() {
        String[] arr = {"a", "b", "c"};
        String[] arr1 = arr.clone();
        System.out.println(arr == arr1);
        arr[0] = "d";
        System.out.println(Arrays.toString(arr));
        System.out.println(Arrays.toString(arr1));
        System.out.println(arr == arr1);
    }

    /**
     * 数组 + 自定义对象 浅拷贝
     * 修改原对象的属性 → 拷贝数组同步变化（浅拷贝共享对象）
     */
    public void test3() throws CloneNotSupportedException {
        ShallowClone shallowClone1 = new ShallowClone();
        shallowClone1.setName("NoPr");

        ShallowClone[] shallowClones = new ShallowClone[1];
        shallowClones[0] = shallowClone1;

        ShallowClone[] clone = shallowClones.clone();
        System.out.println(clone[0].getName());

        // 修改对象本身 → 浅拷贝的数组同步变化
        shallowClone1.setName("NoPr1");
        System.out.println(clone[0].getName());
        System.out.println(shallowClones == clone);
    }

    /**
     * ✅ 核心方法：调用【本类的clone()】 → 真正的自定义对象浅拷贝
     * 浅拷贝特征：创建新对象，复制引用类型地址，共享内部对象
     */
    public void test4() throws CloneNotSupportedException {
        System.out.println("调用本类clone() → 浅拷贝演示");
        // 1. 创建原对象
        ShallowClone original = new ShallowClone();
        original.setName("原始对象");
        original.setTags(Arrays.asList("Java", "克隆"));

        // 2. ✅ 调用我们自己类的 clone() → 浅拷贝出新对象
        ShallowClone copy = original.clone();

        // 3. 初始状态：值完全一样
        System.out.println("原对象name：" + original.getName() + "，拷贝对象name：" + copy.getName());
        System.out.println("两个对象地址是否相同：" + (original == copy)); // false（新对象）

        // 4. 关键：修改引用类型成员（对象本身）
        original.getTags().add("浅拷贝");

        // 5. 浅拷贝结果：拷贝对象同步变化（共享内部引用对象）
        System.out.println("原对象tags：" + original.getTags());
        System.out.println("拷贝对象tags：" + copy.getTags());
        System.out.println("结论：浅拷贝共享引用对象，修改一个，另一个同步变化！");
    }

    // 浅拷贝：使用 BeanUtils 复制属性
    // 1. 创建新对象
    // 2. 调用 BeanUtils.copyProperties() → 复制属性
    public void shallowCloneByBeanUtils(){
        ShallowClone shallowClone = new ShallowClone();
        BeanUtils.copyProperties(this, shallowClone);

    }
}
