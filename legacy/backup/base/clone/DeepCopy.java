package base.clone;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import org.springframework.beans.BeanUtils;

/**
 * {@code @Package} base.clone
 * {@code @Project} custom-collection
 * {@code @Filename} DeepCopy
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  19:46
 * {@code @Description} 深拷贝
 */


/**
 * 核心知识点：
 * 1. 深拷贝：创建新对象 + 所有层级引用类型都克隆（完全独立，不共享任何对象）
 * 2. 自定义引用类型必须实现Cloneable，重写clone()，嵌套引用需要递归克隆
 * 4. 修改原对象的任意属性，深拷贝对象完全不受影响（和浅拷贝最大区别）
 *
 * 深拷贝方式
 * 1.原生 Cloneable 递归深拷贝：实现 Cloneable，层层重写 clone()，递归克隆所有嵌套引用
 * 2.手动 new + 赋值（最基础、最直观）
 * 3.JDK 序列化深拷贝/Gson 序列化深拷贝
 * 4.MapStruct（企业开发首选深拷贝）
 * 5.Spring BeanUtils（⚠️ 仅浅拷贝，补充说明）
 */
@Getter
@Setter
public class DeepCopy implements Cloneable {

    // 基本属性
    private String name;
    // 自定义引用类型属性（包含嵌套引用，演示完整深拷贝）
    private Tags tags;

    // ===================== 核心：深拷贝重写 clone() =====================
    @Override
    public DeepCopy clone() throws CloneNotSupportedException {
        // 1. 先执行浅拷贝，创建当前类的新对象
        DeepCopy deepCopy = (DeepCopy) super.clone();

        // 2. 🔥 深拷贝核心：手动克隆引用类型成员（调用Tags自身的clone方法）
        // 解决：不共享引用，创建全新的Tags对象
        deepCopy.tags = this.tags.clone();

        // 解决：不共享引用，创建全新的Info对象，递归克隆Info对象
        // 确保最内层嵌套引用类型也克隆，避免断链问题，实现深拷贝。
        deepCopy.tags.setInfo(this.tags.getInfo().clone());

        // 3. 返回完全独立的深拷贝对象
        return deepCopy;
    }

    // ===================== 对标 ShallowClone 的测试方法 =====================
    public static void main(String[] args) throws CloneNotSupportedException {
        DeepCopy deepCopy = new DeepCopy();
        deepCopy.test1();
        System.out.println("-----------------");
        deepCopy.test2();
        System.out.println("-----------------");
        deepCopy.test3();
        System.out.println("-----------------");
        deepCopy.test4();
    }

    /**
     * 引用赋值（不是拷贝！和浅拷贝类完全一致）
     * 无新对象，指向同一个数组，修改互相影响
     */
    public void test1() {
        String[] arr = {"a", "b", "c"};
        String[] arr1 = arr;
        arr1[0] = "d";
        System.out.println(Arrays.toString(arr));
        System.out.println(arr == arr1);
    }

    /**
     * 数组深拷贝演示（对标类的深拷贝逻辑）
     * 数组深拷贝后，两个数组完全独立
     */
    public void test2() {
        String[] arr = {"a", "b", "c"};
        // 数组克隆本质：对于String数组，等效深拷贝（不可变对象）
        String[] arr1 = arr.clone();
        System.out.println(arr == arr1);
        arr[0] = "d";
        System.out.println(Arrays.toString(arr));
        System.out.println(Arrays.toString(arr1));
        System.out.println(arr == arr1);
    }

    /**
     * 自定义对象数组 深拷贝演示
     * 对标浅拷贝test3：修改原对象，深拷贝数组【不会变化】
     */
    public void test3() throws CloneNotSupportedException {
        DeepCopy original = new DeepCopy();
        original.setName("NoPr");
        // 初始化引用类型
        Tags tags = new Tags();
        tags.setTagName("Java深拷贝");
        Info info = new Info();
        info.setInfoDesc("嵌套引用");
        tags.setInfo(info);
        original.setTags(tags);

        // 数组深拷贝
        DeepCopy[] arr = {original};
        DeepCopy[] copyArr = arr.clone();

        System.out.println("拷贝后初始值：" + copyArr[0].getTags().getInfo().getInfoDesc());

        // 修改原对象的【嵌套引用属性】
        original.getTags().getInfo().setInfoDesc("修改原对象");

        // 🔥 深拷贝结果：拷贝数组完全不受影响（和浅拷贝相反！）
        System.out.println("修改后拷贝对象值：" + copyArr[0].getTags().getInfo().getInfoDesc());
        System.out.println("数组地址是否相同：" + (arr == copyArr));
    }

    /**
     * 核心：本类的深拷贝调用（对标浅拷贝test4）
     * 完整演示：自定义引用类型 + 嵌套引用 + 递归克隆
     */
    public void test4() throws CloneNotSupportedException {
        System.out.println("调用本类clone() → 完整深拷贝演示");
        // 1. 创建原对象（包含两层引用：Tags → Info）
        DeepCopy original = new DeepCopy();
        original.setName("原始对象");

        // 内层嵌套引用对象
        Info info = new Info();
        info.setInfoDesc("深拷贝嵌套对象");
        // 外层引用对象
        Tags tags = new Tags();
        tags.setTagName("深拷贝标签");
        tags.setInfo(info);

        original.setTags(tags);

        // 2. 调用本类深拷贝方法
        DeepCopy copy = original.clone();

        // 3. 初始状态一致
        System.out.println("原对象name：" + original.getName());
        System.out.println("拷贝对象name：" + copy.getName());
        System.out.println("两个对象地址：" + (original == copy)); // false

        // 4. 关键：修改原对象的【嵌套引用类型】
        original.getTags().setTagName("修改后的标签");
        original.getTags().getInfo().setInfoDesc("修改嵌套对象");

        // 5. 🔥 深拷贝最终效果：拷贝对象完全独立，无任何变化
        System.out.println("------------------------------------");
        System.out.println("原对象Tags：" + original.getTags().getTagName());
        System.out.println("拷贝对象Tags：" + copy.getTags().getTagName());
        System.out.println("原对象嵌套Info：" + original.getTags().getInfo().getInfoDesc());
        System.out.println("拷贝对象嵌套Info：" + copy.getTags().getInfo().getInfoDesc());
        System.out.println("结论：深拷贝完全独立，修改任意层级都不影响拷贝对象！");
    }

    /**
     * 序列化，反序列化深拷贝
     * 这个过程会创建一个全新的对象图，与原对象图完全独立
     * 缺点是：性能开销大
     */
    public DeepCopy deepCopyBySerializable() {
        try {
            // 序列化
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(this);

            // 反序列化
            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bis);
            return (DeepCopy) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gson 深拷贝
     * 1. 序列化为 JSON 字符串
     * 2. 反序列化为新对象
     * 开发常用
     */
    public void deepCopyByGson(){
        Gson gson = new Gson();
        String json = gson.toJson(this);
        DeepCopy copy = gson.fromJson(json, DeepCopy.class);
    }



}

// ===================== 自定义引用类型 Tags（实现克隆） =====================
@Getter
@Setter
class Tags implements Cloneable {

    private String tagName;
    // 🔥 嵌套引用类型（演示递归深拷贝）
    private Info info;

    @Override
    protected Tags clone() throws CloneNotSupportedException {
        return (Tags) super.clone();
    }
}

// ===================== 最内层嵌套引用类型 Info =====================
@Getter
@Setter
class Info implements Cloneable {
    private String infoDesc;

    @Override
    protected Info clone() throws CloneNotSupportedException {
        return (Info) super.clone();
    }
}
