package stack;

import lombok.Data;

/**
  {@code @Package} day.day01.stack
  {@code @Project} custom-collection-java
  {@code @Filename} ArrayStack
  {@code @Author} 18991/NoPr
  {@code @Date} 2025/7/2  21:09
  {@code @Description} 数组类型的栈实现
*/

@Data
public class ArrayStack<Item> implements MyStack<Item>{

    // 此处为元素个数，对应的数组中的元素下标为 [个数-1]
    private int itemNums = 0;

    private Object[] items = new Object[itemNums];

    public ArrayStack(int itemNums) {
        this.items = new Object[itemNums];
    }

    @Override
    public void push(Object o) {
        judgeSize();
        items[itemNums++] = o;
    }

    @Override
    public Item pop() {
        if (isEmpty()){
            return null;
        }
        // 此处先元素个数-1获取数组下标，在根据下表获取元素
        Object item = items[--itemNums];
        // 此处只是获取了值，但是在数组中实际还存在
        items[itemNums] = null;
        return (Item) item;
    }

    @Override
    public Item peek() {
        if (isEmpty()){
            return null;
        }
        Object item = items[--itemNums];
        return (Item) item;
    }

    private void judgeSize1(){
        if (itemNums >= items.length){
            Object[] newItems = new Object[items.length * 2];
            for (int i = 0; i < items.length; i++) {
                newItems[i] = items[i];
            }
            items = newItems;
        }
    }

    private void judgeSize(){
        if (itemNums >= items.length){
            Object[] newItems = new Object[items.length * 2];
            // 可以使用System.arraycopy()替代手动循环
            System.arraycopy(items, 0, newItems, 0, items.length);
            items = newItems;
        }
    }


    @Override
    public int size() {
        return itemNums;
    }

    @Override
    public boolean isEmpty() {
        return itemNums <= 0;
    }
}
