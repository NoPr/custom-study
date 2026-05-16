package stack;

/**
  {@code @Package} day.day01.stack
  {@code @Project} custom-collection-java
  {@code @Filename} TestMain
  {@code @Author} 18991/NoPr
  {@code @Date} 2025/7/2  21:28
  {@code @Description} 测试类
*/


public class TestMain {

    public static void main1(String[] args) {
        ArrayStack<Number> arrayStack = new ArrayStack<>(5);
        arrayStack.push(1);
        arrayStack.push("11");
        arrayStack.push(12L);
        arrayStack.push("--");
        arrayStack.push(12-1);
        arrayStack.push("12-1");

        System.out.println("栈大小为："+arrayStack.size());
        for (int i = 0; i < 7; i++) {
            System.out.println("取出的元素为"+arrayStack.pop());
            System.out.println("栈是否为空"+arrayStack.isEmpty());
            if (arrayStack.isEmpty()) break;
        }
    }

    public static void main(String[] args) {
        BtacketStackTest btacketStackTest = new BtacketStackTest();
        btacketStackTest.calculator();
    }
}
