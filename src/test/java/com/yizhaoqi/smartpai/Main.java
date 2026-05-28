package com.yizhaoqi.smartpai;

import java.util.*;


class ListNode{
    int val;
    long expireTime;
    ListNode next;
    ListNode(){}
    ListNode(int val){
        this.val=val;
    }
    ListNode(int val,ListNode next){
        this.val = val;
        this.next = next;
    }
}
public class Main {

    static class Solution{

        public int generateZeroOrOne() {
            // 疯狂 new 一个新对象，利用它分配到的内存地址求奇偶
            // 注意：hashCode 可能是负数，所以依然使用 & 1 是最稳妥的
            return new Object().hashCode() & 1;
        }

        // 合并两个有序链表



    }
    public static void main(String[] args){
            Solution solution=new Solution();

            System.out.print(solution.generateZeroOrOne());


    }
}
