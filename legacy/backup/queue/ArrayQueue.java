package queue;

/**
 {@code @Package} day.day02.queue
 {@code @Project} custom-collection-java
 {@code @Filename} ArrayQueue
 {@code @Author} 18991/NoPr
 {@code @Date} 2025/7/2  23:19
 {@code @Description} 数组实现队列
*/


public class ArrayQueue<Item> implements MyQueue<Item>{

    // 大小
    private int size;
    // 头下标
    private int front = 0;
    // 尾下标
    private int rear = 0;

    private Item[] items;

    public ArrayQueue(int capacity) {
        items = (Item[]) new Object[capacity];
        size = capacity;
    }

    @Override
    public void push(Item item) {
        judgeSize();
        items[rear] = item;
        rear++;
    }

    private void judgeSize(){
        if (size >= items.length){
            Item[] newItems = (Item[]) new Object[items.length * 2];
            // 可以使用System.arraycopy()替代手动循环
            System.arraycopy(items, 0, newItems, 0, items.length);
            items = newItems;
        }
    }

    @Override
    public Item pop() {
        if (isEmpty()){
            return null;
        }
        return items[front++];
    }

    @Override
    public boolean isEmpty() {
        return front == rear;
    }

    @Override
    public boolean isFull() {
        return size == items.length;
    }

    @Override
    public int size() {
        return size;
    }
}
