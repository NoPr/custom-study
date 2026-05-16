package jvm;

/*
{@code @Package} day.day03.jvm
{@code @Project} custom-collection-java
{@code @Filename} JVMTest
{@code @Author} 18991/NoPr
{@code @Date} 2025/7/7  21:45
{@code @Description} jvm执行
*/


public class JVMTest {

    // 可以通过jvm二进制文件查看到将整个方法重构，将finally部分在每个部分执行完成之后执行一次
    public void testTry(){
        int x;
        try {
            x = 1;
        }catch (Exception e){
            x = 2;
        } finally {
            x = 3;
        }
        System.out.println(x);
    }
}
