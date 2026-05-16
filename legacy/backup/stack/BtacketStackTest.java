package stack;

/*
  {@code @Package} day.day01.stack
  {@code @Project} custom-collection-java
  {@code @Filename} BtacketStackTest
  {@code @Author} 18991/NoPr
  {@code @Date} 2025/7/2  21:46
  {@code @Description} 数学表达式求值
*/

import java.util.Scanner;

/**
 * 数学表达式求值：
 * 比如用栈实现一个简单的四则运算：3+11*2+8-15/5，用栈来实现这个算术表达式
 * 两个栈来实现：一个放数字 一个放符号。
 * --------------------------------------------------------
 * 算法流程：
 * 1. 遇到数字直接压入数字栈
 * 2. 遇到运算符，比较优先级，先计算优先级高的运算
 * 3. 输入=时，计算栈中剩余的所有运算
 * 4. 最后数字栈中的唯一数字就是结果
 */

public class BtacketStackTest {

    public void calculator() {
        Scanner scanner = new Scanner(System.in);
        
        // 数字栈
        ArrayStack<Integer> numberStack = new ArrayStack<>(2);
        // 运算符栈
        ArrayStack<Character> operatorStack = new ArrayStack<>(1);
        
        System.out.println("请输入表达式(以=结束):");
        String input = scanner.next();
        
        while (!"=".equals(input)) {
            if (isNumber(input)) {
                numberStack.push(Integer.parseInt(input));
            } else if (isOperator(input)) {
                char op = input.charAt(0);
                while (!operatorStack.isEmpty() && priority(op) <= priority(operatorStack.peek())) {
                    calculate(numberStack, operatorStack);
                }
                operatorStack.push(op);
            }
            input = scanner.next();
        }
        
        while (!operatorStack.isEmpty()) {
            calculate(numberStack, operatorStack);
        }
        System.out.println("计算结果: " + numberStack.pop());
    }

    private boolean isNumber(String s) {
        return s.matches("\\d+");
    }

    private boolean isOperator(String s) {
        return s.matches("[+\\-*/]");
    }

    private int priority(char op) {
        return switch (op) {
            case '+', '-' -> 1;
            case '*', '/' -> 2;
            default -> 0;
        };
    }

    private void calculate(ArrayStack<Integer> numStack, ArrayStack<Character> opStack) {
        int num2 = numStack.pop();
        int num1 = numStack.pop();
        char op = opStack.pop();
        
        switch (op) {
            case '+': numStack.push(num1 + num2); break;
            case '-': numStack.push(num1 - num2); break;
            case '*': numStack.push(num1 * num2); break;
            case '/': numStack.push(num1 / num2); break;
        }
    }
}
