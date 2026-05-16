package stack;

/**
  {@code @Package} day.day01.stack
  {@code @Project} custom-collection-java
  {@code @Filename} MyStack
  {@code @Author} 18991/NoPr
  {@code @Date} 2025/7/2  21:04
  {@code @Description} 自定义栈接口
*/


public interface MyStack<Item> {

    // 入栈
    void push(Item item);

    // 出栈
    Item pop();

    // 取值但不删除
    Item peek();

    // 大小
    int size();

    // 是否为空
    boolean isEmpty();

}
