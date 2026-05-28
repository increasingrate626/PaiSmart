package com.yizhaoqi.smartpai.controller;

import java.util.*;

// 1. 手动定义 TreeNode 类
class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;

    TreeNode() {}
    TreeNode(int val) { this.val = val; }
    TreeNode(int val, TreeNode left, TreeNode right) {
        this.val = val;
        this.left = left;
        this.right = right;
    }
}

public class test {

    // 2. 将你的核心代码封装在一个内部类中，或者将核心逻辑提取出来
    // 推荐封装为 Solution 类，这样每次 new 一个新对象，避免 res 脏数据污染
    static class Solution {
        int res = 0;

        public int diameterOfBinaryTree(TreeNode root) {
            check(root);
            return res;
        }

        public int check(TreeNode root) {
            if (root == null) return 0;
            int l = check(root.left);
            int r = check(root.right);
            res = Math.max(res, l + r);
            return Math.max(l, r) + 1;
        }
    }

    public static void main(String[] args) {
        Scanner sc= new Scanner(System.in);
        while (sc.hasNextLine()){
            String s = sc.nextLine().trim();
            if (s.isEmpty()) continue;
            String[] ss = s.split("\\s+");
            if (ss.length == 0 || ss[0].equals(null)) continue;
            Queue<TreeNode> q = new LinkedList<>();
            TreeNode root = new TreeNode(Integer.parseInt(ss[0]));
            q.offer(root);
            int i=1;
            while (i<ss.length&&!q.isEmpty()){
                TreeNode cur = q.poll();
                if (!ss[i].equals(null)){
                    cur.left = new TreeNode(Integer.parseInt(ss[i]));
                    q.offer(cur.left);
                }
                i++;
                if (i<ss.length&&!ss[i].equals(null)){
                    cur.right=new TreeNode(Integer.parseInt(ss[i]));
                    q.offer(cur.right);
                }
                i++;
            }
            Solution solution = new Solution();
            System.out.println(solution.diameterOfBinaryTree(root));

        }
        sc.close();

    }


}