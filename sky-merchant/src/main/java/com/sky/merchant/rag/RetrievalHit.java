package com.sky.merchant.rag;

import lombok.Data;

/**
 * 一次检索的命中结果，单位是「父块」——不是子块。
 *
 * 为什么要带上 childId / childRank：
 * 排障时最有用的信息是"到底是哪个子块把父块顶上来的"。
 * 出现过这种情况：父块排第一，但点开发现命中的子块只是偶然撞上了一个短词，
 * 而真正该命中的子块排在第 19 名 —— 只看父块名次看不出来，必须留痕。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
public class RetrievalHit
{
    /** 命中的父块 id */
    private Long parentId;

    /** 文档名，前端标注来源用 */
    private String docName;

    /** 父块在同文档内的顺序，用于把多个父块按原文顺序拼回上下文 */
    private Integer parentSeq;

    /** 父块原文（喂给模型的上下文就由这些组成） */
    private String snippet;

    /** 融合得分（RRF，越大越相关）。只用于排序，不要当相似度阈值用 */
    private double score;

    /** 贡献了这个父块名次的子块 id */
    private Long childId;

    /** 那个子块在子块总排名里的名次（从 1 开始）。越大说明越"侥幸" */
    private int childRank;
}
