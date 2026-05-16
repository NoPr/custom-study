package queue;

/**
 {@code @Package} day.day02
 {@code @Project} custom-collection-java
 {@code @Filename} MyQueue
 {@code @Author} 18991/NoPr
 {@code @Date} 2025/7/2  23:15
 {@code @Description} 队列接口
*/


public interface MyQueue<Item> {

    void push(Item item);

    Item pop();

    boolean isEmpty();

    boolean isFull();

    int size();
}
