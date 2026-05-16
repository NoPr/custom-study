package base.arr;

/**
 * {@code @Package} base.arr
 * {@code @Project} custom-collection
 * {@code @Filename} TwoDimienalArray
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  21:34
 * {@code @Description} 二维数组
 */


public class TwoDimienalArray {

    public static void main(String[] args) {
        // 1. 二维数组的定义
    }

    // 1. 二维数组的定义
    public void test1() {
        // 每个元素都是一个一维数组，每个一维数组的长度可以不同
        String[][] ints = new String[3][4];
        System.out.println("二维数组的地址：" + ints);
        System.out.println("二维数组的行数/长度：" + ints.length);
        System.out.println("第一个一维数组的地址：" + ints[0]);
        System.out.println("第一个一维数组的长度数：" + ints[0].length);

        // 兼容C语言二维数组的定义方式，可读性较差
        String jianrongC[][] = new String[3][4];


        // 初始化
        // 1.静态初始化 3行3列 二维数组
        int[][] arr = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};


        // 2.动态初始化 3行2列：固定3行，每行固定2个元素
        int[][] arr1 = new int[3][2];
        // 手动赋值
        arr1[0][0] = 10;
        arr1[0][1] = 20;
        arr1[1][0] = 30;

        // 3. 不规则/锯齿二维数组 java独有
        // 先规定有几行，每一行单独定义长度，行列可以不一样长
        int[][] arr2 = new int[3][];

        arr2[0] = new int[2]; // 第0行有2个元素
        arr2[1] = new int[3]; // 第1行有3个元素
        arr2[2] = new int[4]; // 第2行有4个元素

        /**
         * 以 int[][] arr = new int[3][2]; 为例：
         * 在堆中创建外层一维数组，长度为 3；
         * 外层数组里不存数值，存的是每一行内层数组的内存地址；
         * 每个地址指向一个 长度为 2 的内层一维数组；
         *
         * 外层arr → [地址0, 地址1, 地址2]
         * 地址0 → 内层数组 [0,0]
         * 地址1 → 内层数组 [0,0]
         * 地址2 → 内层数组 [0,0]
         *
         * 所以：二维数组本质还是一维数组，只是存的是别的数组地址。
         */
    }

    // 2. 二维数组的遍历,每行列数可以不同，不能用固定列数遍历。
    public void test2() {
        int[][] arr = {{1,2,3},{4,5},{6,7,8,9}};
        // 遍历行
        for (int i = 0; i < arr.length; i++) {
            // 遍历当前行的每一列
            for (int j = 0; j < arr[i].length; j++) {
                System.out.print(arr[i][j] + " ");
            }
            System.out.println();
        }

        // 增项for循环，只读取数组元素，不修改数组元素
        for (int[] row : arr) {
            for (int num : row) {
                System.out.print(num + " ");
            }
            System.out.println();
        }
    }


}
