package com.yizhaoqi.smartpai;

import lombok.val;
import org.apache.poi.ss.formula.functions.T;



public class test {

    // 将你的核心代码封装成一个内部类（或者单独放在外面也可以）
    static class Solution {

        public static ListNode mergeKLists(ListNode[] lists) {
            if (lists == null || lists.length == 0) {
                return null;
            }
            return mergeRange(lists, 0, lists.length - 1);
        }
        // 分治合并 [l, r] 范围内的链表
        private static ListNode mergeRange(ListNode[] lists, int l, int r) {
            if (l == r) return lists[l];
            int mid = l + (r - l) / 2;
            ListNode left = mergeRange(lists, l, mid);
            ListNode right = mergeRange(lists, mid + 1, r);
            return merge(left, right);
        }
        // 合并两个有序链表
        private static ListNode merge(ListNode list1, ListNode list2) {
            ListNode dummy = new ListNode(0);
            ListNode cur = dummy;
            while (list1 != null && list2 != null) {
                if (list1.val < list2.val) {
                    cur.next = list1;
                    list1 = list1.next;
                } else {
                    cur.next = list2;
                    list2 = list2.next;
                }
                cur = cur.next;
            }
            cur.next = list1 != null ? list1 : list2;
            return dummy.next;
        }
    }

    public static void main(String[] args) {

    }
}