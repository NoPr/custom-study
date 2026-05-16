package base.arr;

import java.util.Arrays;

/**
 * {@code @Package} base.arr
 * {@code @Project} custom-collection
 * {@code @Filename} Main
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  17:50
 * {@code @Description} 数组
 */


public class Main {

    public void test1() {

        // 静态初始化数组
        int[] arr = {1, 2, 3, 4, 5};
        int[] arr2 = new int[]{1, 2, 3, 4, 5};
        for (int i = 0; i < arr.length; i++) {
            System.out.println(arr[i]);
        }
        for (int a = 0; a < arr2.length; a++) {
            System.out.println(arr2[a]);
        }
    }

    /**
     * {@code @Description} 增强for循环，
     */
    public void test2() {
        // 创建数组的时候必须指定长度
        int[] arr = new int[5];
        arr[0] = 1;
        arr[1] = 2;
        arr[2] = 3;
        arr[3] = 4;
        arr[4] = 5;
        for (int a : arr) {
            System.out.println(a);
        }
    }

    public void test3(){
        int[] a = {1, 2, 3, 4, 5};
        int i = 0;
        for (int e : a) {
//            e = 2; 增强for循环不能赋值
            a[i] = 2;
            i++;
        }

        Arrays.stream(a).forEach(System.out::println);
    }

    public static void main(String[] args) {
        Main main = new Main();
        main.test1();
        main.test2();
        main.test3();
    }
}
