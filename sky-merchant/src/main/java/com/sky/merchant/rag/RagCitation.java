package com.sky.merchant.rag;

import lombok.Data;

/**
 * 一条引用：让用户能验证"模型这句话是从哪来的"。
 *
 * 为什么必须带引用而不是只给答案：
 * 不给出处，模型编的和文档里写的在用户眼里没有区别。
 * 有了引用，答错时用户能立刻看出是"文档里就这么写的"还是"模型自己编的"——
 * 这两种问题的修法完全不同（补文档 vs 改检索/改提示词）。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
public class RagCitation
{
    /** 父块 id。前端拿它可以跳到原文位置 */
    private Long chunkId;

    /** 文档名 */
    private String docName;

    /** 父块原文片段。截断到可读长度，全文由前端按 chunkId 再取 */
    private String snippet;

    /** 这一段在本次检索里的融合得分，用于按相关度排序展示 */
    private double score;
}
