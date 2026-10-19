package com.sky.merchant.rag.splitter;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 标题层级切分器（语义切分）
 *
 * 策略
 * 按 markdown 标题行（# 到 ######）把文档切成「章节」
 * 一个章节 = 标题行 + 它下面的正文，天然就是一个完整的知识点
 * 只有章节超过 chunkSize 时，才降级交给递归字符切分器兜底
 *
 * 为什么要有这个类
 * Day19 的召回实验已经证明 chunkSize 300 与 1000 没有系统性差异
 * Hit@1 都是 22/30，翻转题 7:7，MRR 的差距全部来自单道题的极端值
 * 根因是两者都「按长度切」，切对切错是同一套方式，只是碰巧位置不同
 * 真正决定召回的是「这一块里语义是否自洽」
 * 按标题切能让每块天然落在语义边界上
 *
 * 与 RecursiveCharacterSplitter 的区别
 * 递归切分：先看长度，超长了才去找语义边界
 * 标题切分：先按语义结构切，超长了才退回按长度切
 * 两者的判断优先级正好相反
 *
 * 已知局限
 * 正则 ^#{1,6}\s 无法区分「真标题」和「代码块里的 # 注释」
 * 例如 shell 注释 # 启动哨兵 会被误判成标题
 * 需要时可加「代码围栏跟踪」修正，Day19 先不处理
 */
@SuppressWarnings("ALL")
public class MarkdownHeaderSplitter extends TextSplitter {

    /**
     * 标题行正则
     * (?m)     多行模式，让 ^ 匹配每一行的开头，而不是整个字符串的开头
     * ^        行首
     * #{1,6}   一到六个井号
     * \s+      至少一个空白，用来区分真标题和 #话题标签
     * .*$      该行剩余内容
     */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+.*$");

    /** 单块长度上限，超过它的章节要被降级切分 */
    private final int chunkSize;

    /**
     * 章节超长时的兜底切分器
     *
     * overlap 只传给它，不传给 mergeShortChunks —— 这是本类 overlap 设计的核心
     *
     * 为什么重叠只做在兜底切分里
     * 重叠的作用是补偿「语义边界被硬切造成的上下文损失」
     * 而在这个类里，「硬切」只发生在一处
     *   某个章节超过 chunkSize，被迫在章节内部按长度切
     * 章节与章节之间的边界本来就是语义边界，没有损失，不需要重叠
     *
     * 实测验证（30 题召回，同一份语料）
     *   只在兜底处重叠    Hit@1 25/30  Hit@3 28/30  MRR 0.885
     *   所有边界都重叠    Hit@1 21/30  Hit@3 26/30  MRR 0.806
     * 后者更差的两个原因
     *   相邻块互相包含对方的尾部，块与块高度相似，排序区分度下降
     *   块内约三分之一是重复文本，有效信息被稀释
     */
    private final RecursiveCharacterSplitter fallback;

    public MarkdownHeaderSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须大于等于 0 且小于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.fallback = new RecursiveCharacterSplitter(chunkSize, overlap);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    protected List<String> splitText(String text) {
        // 框架保证非空，只需处理空串与纯空白
        if (text.isBlank()) {
            return new ArrayList<>();
        }
        List<String> sections = splitByHeading(text);
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                // 这一节本身就是一个语义单元，完整收下，不拆
                chunks.add(section);
            } else {
                // 超长章节降级：先按标题对齐，再在章节内部按长度切
                // 标题在 section 开头，会跟着第一块走，这正是想要的效果
                chunks.addAll(fallback.splitIntoChunks(section));
            }
        }
        return mergeShortChunks(chunks);
    }

    /**
     * 后处理：把过短的块并入相邻块
     *
     * 为什么需要
     * 按标题切分后会留下大量碎片，实测 66 块里有 19 个不足 100 字，占 29%
     * 碎片有三个来源
     *   相邻标题各自成块：## 后面紧跟 ###，父标题本身没有正文
     *   超长章节走兜底切分时，标题行被当成独立段落切了出来
     *   兜底合并到最后剩下的尾巴
     *
     * 合并判据
     * 缓冲区里的内容还不到 minSize，就继续吸下一块
     * 这样「标题 + 它下面的正文」会自然粘在一起
     *
     * 取舍（重要）
     * 吸进来的结果可能略微超过 chunkSize
     * 这里选择优先保「标题与正文不分离」这个语义要求，让块长度上限让位
     * 如果不允许超出，把条件改成「缓冲区 + 当前块 小于等于 chunkSize 时才吸」
     * 那样能守住上限，但标题块可能仍然合不进去
     *
     * overlap 为什么不在这个方法里做
     * 本类的重叠只在「章节超长、被逼着在章节内部按长度切」时才有意义
     * 那一块工作由 fallback 完成，构造时已经带上 overlap
     * 这里如果再加一次，相邻块会互相包含对方的尾部
     * 块与块高度相似导致排序区分度下降，块内还会出现约三分之一的重复文本
     * 实测这么改会让 Hit@1 从 25/30 掉到 21/30，所以不做
     *
     * @param chunks 初步切分结果
     * @return 合并后的结果
     */
    private List<String> mergeShortChunks(List<String> chunks) {
        int minSize = chunkSize / 2;

        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String chunk : chunks) {
            if (buffer.length() == 0) {
                buffer.append(chunk);
            } else if (buffer.length() < minSize) {
                buffer.append(chunk);
            } else {
                out.add(buffer.toString());     // 落刀
                buffer.setLength(0);
                buffer.append(chunk);
            }
        }

        if (buffer.length() > 0) {
            // 末尾这块太短，并回上一块的尾部
            if (out.size() > 0 && buffer.length() < minSize) {
                out.set(out.size() - 1, out.get(out.size() - 1) + buffer.toString());
            } else {
                out.add(buffer.toString());
            }
        }
        return out;
    }

    /**
     * 按标题行把整篇文本切成章节列表
     *
     * 切法
     * 找到所有标题行的起始下标，相邻两个起点之间就是一章
     * 第一个标题之前的内容（前言/引言）单独作为一章
     * 整篇没有任何标题时，整篇作为一章
     *
     * 结果要求
     * 把返回的所有章节按顺序拼起来，应当与传入的 text 完全一致
     * 所以不要 strip 首尾空白，也不要丢掉标题行前的换行符
     *
     * @param text 整篇 markdown 文本
     * @return 章节列表，顺序与原文档一致
     */
    private List<String> splitByHeading(String text) {
        List<String> sections = new ArrayList<>();
        // 步骤提示
        Matcher m = HEADING.matcher(text); // 创建 Matcher 对象
        // 用一个变量记住「上一章的起点」，初值设为 -1
        int start = -1;

        while (m.find()) {
            if (start == -1) {
                // 这是第一个标题
                // 把 text 中 [0, m.start()) 的前言收成一章，内容全是空白就丢弃
                String frontMatter = text.substring(0, m.start());
                if (!frontMatter.isBlank()) {
                    sections.add(frontMatter);
                }
            } else {
                // 把 [起点, m.start()) 收成一章
                sections.add(text.substring(start, m.start()));
            }
            // 把起点更新为 m.start()
            start = m.start();
        }
        // 循环结束后：
        //   起点不是 -1 → 把 [起点, 末尾) 收成最后一章
        //   起点还是 -1 → 整篇没有标题，把整个 text 作为一章
        if (start != -1){
            sections.add(text.substring(start));
        }
        if (start == -1){
            sections.add(text);
        }
        // 三个边界，一个都不能漏
        //   前言：第一个标题之前的内容
        //   末章：最后一个标题一直延伸到文末，收尾要写在循环外面
        //   无标题：整篇没有井号时要能兜住，否则返回空列表会让内容全丢
        //
        // 注意
        //   切片右端用 m.start()，它是下一个标题行井号的位置
        //   所以上一章末尾会自然带上「标题行前的那一个换行符」，这正是要的
        //   别用 strip()，否则「拼接等于原文」这条检查会失败
        return sections;
    }
}
