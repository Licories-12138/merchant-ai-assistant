package com.sky.merchant.rag.splitter;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分器（LangChain RecursiveCharacterTextSplitter 思想）
 * 核心策略：按分隔符优先级「逐级降级」
 * 段落(\n\n) → 换行(\n) → 句号 → 感叹号 → 问号 → 分号 → 逗号 → 空格 → 硬切
 * 为什么比固定切分好
 * 固定切分会把「订单状态机有 9 个事件」从中间劈开，
 * 变成「订单状态机有 9 」+「个事件」，两半的 embedding 都失真。
 * 递归切分优先找语义边界，尽量让每个 chunk 是完整的一句话。
 * 设计要点
 * 1) 递归函数签名要能「带上下文」——传入用哪个分隔符 + 当前是第几层
 * 2) 递归出口：当前块已经 <= chunkSize，直接收下
 * 3) 所有分隔符都用完还超长（比如一个 3000 字的无标点长句）→ 硬切兜底
 * 4) 合并小片段：切完会有很多碎块，相邻的碎块合并到接近 chunkSize 再输出
 * 5) overlap 用在这里：上一块的尾部拼到下一块头部
 */

public class RecursiveCharacterSplitter extends TextSplitter {
    // 分隔符优先级（LangChain 经典做法）：
    private final int chunkSize;      // 目标块大小（字符）
    private final int overlap;        // 相邻块重叠
    private final List<String> separators;   // 分隔符优先级表

    public RecursiveCharacterSplitter(int chunkSize, int overlap) {
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须小于 chunkSize，否则会导致死循环");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.separators = List.of("\n\n", "\n", "。", "！", "？", "；", "，", " ", "");
    }

    @Override
    @SuppressWarnings("NullableProblems")
    protected List<String> splitText(String text) {
        // protected 意味着该方法可以被同一个包中的类、子类（即使在不同包中）以及同一个包中的类访问
        // 1. 入口:调递归方法，从 separators 的第 0 个开始
        // 框架保证非空，只需处理空串与纯空白
        if (text.isBlank()) {
            return new ArrayList<>();
            // 不能用 List.of()返回不可变空 list
            // 用 new ArrayList<>()可变，安全
        }
        return splitRecursive(text, 0);
    }

    /**
     * 对外暴露的切分入口，供同项目的其它切分器复用
     *
     * 为什么不直接调 splitText
     * splitText 由父类 TextSplitter 声明为 protected，而声明类在框架包里
     * protected 的跨包可见性规则是「只有本类及其子类能访问，
     * 且引用类型必须是被访问成员的子类」
     * MarkdownHeaderSplitter 虽然也是 TextSplitter 的子类，
     * 但它持有的是 RecursiveCharacterSplitter 实例（兄弟类），编译不过
     * 这里补一个 public 转发方法绕开这个限制
     *
     * @param text 待切分文本
     * @return 切分后的文本块
     */
    @SuppressWarnings("JavadocBlankLines")
    public List<String> splitIntoChunks(String text) {
        return splitText(text);
    }

    /**
     * 递归切分
     * @param text       待切分的文本
     * @param sepIndex   当前用 separators 里第几个分隔符
     * 递归思路：
     * 若 text.length() <= chunkSize → 直接返回
     * 否则用 SEPARATORS.get(depth) 切分、边拼边累加，超过 chunkSize 就落刀
     * 若分隔符用尽仍切不动 → 退回硬切
     */
    private List<String> splitRecursive(String text, int sepIndex) {
        // 1. 出口 1 —— text 已经够短，直接返回 List.of(text)
        if (text.length() <= chunkSize) {
            return List.of(text); // 返回不可变 list
        }
        // 2. 出口 2 —— 分隔符用尽 → 硬切（不是原样返回）
        if (sepIndex >= separators.size()) {
            return hardSplit(text);
        }
        String sep = separators.get(sepIndex);
        // 空串分隔符 = 最后一层，等价于硬切，不要走 indexOf
        if (sep.isEmpty()) {
            return hardSplit(text);
        }
        // 3. sep 把 text 切开（注意要保留分隔符！）
        // 不用String.split 默认会丢掉分隔符
        List<String> pieces = splitKeepSeparator(text, sep);
        List<String> merged = new ArrayList<>();
        for (String piece : pieces) {
            if (piece.length() <= chunkSize) {
                merged.add(piece);
            } else {
                merged.addAll(splitRecursive(piece, sepIndex + 1)); // 降级递归
            }
        }
        // 4. 合并相邻过短片段 + 应用 overlap
        return mergePieces(merged);
    }

    /**
     * 硬切兜底
     * 所有分隔符都失效时（例如一段没有任何标点的超长文本）
     * 按 chunkSize 直接截断，不做任何语义判断
     * 与 FixedSizeSplitter 的区别
     * 不借助 overlap
     * 重叠统一由 mergePieces 处理，这里只负责把长文本按长度切开
     * 不构造 Document，只处理纯字符串
     *
     * @param text 超长文本（长度必然大于 chunkSize）
     * @return 按 chunkSize 切好的片段列表
     */
    private List<String> hardSplit(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            parts.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return parts;
    }

    /**
     * 按分隔符切分，并把分隔符保留在片段尾部
     * 与 String.split 的区别
     * String.split 会丢弃分隔符
     * 本方法把 分隔符 拼回前一个片段的尾部
     * 例： "a。b。" + 分隔符 "。"  →  ["a。", "b。"]
     *
     * @param text 待切分文本
     * @param sep  分隔符（不能为空串，调用方保证）
     * @return 片段列表，每个片段（最后一个除外）以 sep 结尾
     */
    private List<String> splitKeepSeparator(String text, String sep) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        int idx;
        while ((idx = text.indexOf(sep, from)) != -1) {
            parts.add(text.substring(from, idx + sep.length()));  // ← 长度算上 sep，分隔符被包含进来
            from = idx + sep.length();
        }
        // 末尾没有分隔符的残余部分
        if (from < text.length()) {
            parts.add(text.substring(from));
        }
        return parts;
    }

    /**
     * 把片段序列按 chunkSize 合并成块（边累加边落刀）
     * 与「先切完再回头合并」的区别
     * 前者从左到右一次遍历，不存在「中间的碎块错过合并」的问题
     * 后续片段只要塞得进当前 buffer 就塞，塞不下就把 buffer 落成一块，从新片段重新起头
     * overlap 为什么要带前置条件
     * 块的硬上限是 chunkSize，重叠只是优化项，不能为了重叠把块撑大
     * 落刀后 buffer 里是借来的尾巴（overlap 个字符），再 append 下一块
     * 若下一块本身就很大，尾巴 + 它就会突破 chunkSize
     * 实测 chunkSize=300 overlap=100 时最大块到过 398
     * 因此只在「尾巴 + 这一块仍装得下」时才借尾巴
     * 下一块太大就放弃这次重叠，优先保住块的尺寸上限
     * overlap 为 0 时该条件恒不成立，行为与不带重叠一致
     *
     * @param pieces 待合并的片段（顺序与原文本一致）
     * @return 合并后的块列表，每块长度不超过 chunkSize
     */
    private List<String> mergePieces(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String piece : pieces) {
            if (!buffer.isEmpty() && buffer.length() + piece.length() > chunkSize) {
                String done = buffer.toString();
                chunks.add(done);    // 落刀
                buffer.setLength(0);
                // 借重叠尾巴：只有「尾巴 + 这一块」仍装得下时才借
                // 宁可放弃重叠，也不能让块突破 chunkSize 的硬上限
                if (overlap > 0
                        && done.length() > overlap
                        && piece.length() + overlap <= chunkSize) { // ← 只有装得下才借
                    buffer.append(done.substring(done.length() - overlap));
                }
            }
            buffer.append(piece);
        }
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString());       // 收尾：别漏了最后没落刀的那块
        }
        return chunks;
    }
}
