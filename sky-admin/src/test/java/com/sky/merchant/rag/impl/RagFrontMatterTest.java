package com.sky.merchant.rag.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剥前言这一步的测试：纯算法，不起 Spring。
 *
 * 为什么这个函数必须钉住：
 * 它是整个链路里**唯一会主动丢内容**的地方。丢错了不会报错，
 * 只表现为"文档里明明有，怎么都搜不出来"—— 和切分参数改坏的症状一模一样，
 * 排查时很难想到是它。
 *
 * 重点测的是"不该剥"的三条分支，而不是"该剥"的那条。
 * 该剥没剥只是效果差一点，不该剥却剥了是直接丢数据。
 */
@SuppressWarnings("JavadocBlankLines")
class RagFrontMatterTest
{
    /** 够长的正文，避免踩到"正文不足 500 字就不剥"那条保险 */
    private static final String BODY = "## 一、正文开始\n\n" + "这里是真正的知识内容。".repeat(60);

    @Test
    @DisplayName("标准笔记结构：剥掉标题与元信息，只留正文")
    void stripsTitleAndMeta()
    {
        String text = "# Day 15 · 持久化\n\n日期：2026-10-13\n\n> 承接 Day9~14。\n\n" + BODY;

        String result = RagServiceImpl.stripFrontMatter(text, "doc");

        assertTrue(result.startsWith("## 一、正文开始"), "结果必须从第一个二级标题开始，实际开头：" + result.substring(0, 20));
        assertTrue(result.length() < text.length(), "剥完必须变短");
    }

    @Test
    @DisplayName("不以一级标题开头：不动。第一段就是正文，动了就丢内容")
    void keepsTextWithoutH1()
    {
        String text = BODY;
        assertEquals(text, RagServiceImpl.stripFrontMatter(text, "doc"), "没有一级标题就不该剥");
    }

    @Test
    @DisplayName("没有二级标题：不动。整篇就是一段，剥了等于清空")
    void keepsTextWithoutH2()
    {
        String text = "# 只有一级标题\n\n" + "正文内容。".repeat(200);
        assertEquals(text, RagServiceImpl.stripFrontMatter(text, "doc"), "找不到二级标题就不该剥");
    }

    @Test
    @DisplayName("头部占比超过 40%：不动。说明\"头部\"本身就是正文")
    void keepsTextWhenHeadTooLong()
    {
        String head = "# 标题\n\n" + "这段占了全文很大比例。".repeat(60);
        String text = head + BODY;
        assertEquals(text, RagServiceImpl.stripFrontMatter(text, "doc"), "头部过大说明它是正文，不该剥");
    }

    @Test
    @DisplayName("剥完正文不足 500 字：不动，避免把文档掏空")
    void keepsTextWhenBodyTooShort()
    {
        String text = "# 标题\n\n" + "很短的元信息。".repeat(30) + "\n## 一、\n\n只有几个字。";
        assertEquals(text, RagServiceImpl.stripFrontMatter(text, "doc"), "剥完没剩什么就不该剥");
    }

    @Test
    @DisplayName("带 UTF-8 BOM：先剥 BOM，剥前言才认得出一级标题")
    void stripsBomBeforeFrontMatter()
    {
        String text = "# Day 15 · 持久化\n\n日期：2026-10-13\n\n" + BODY;
        String withBom = "\uFEFF" + text;

        // 先证明 BOM 真的会挡住判断：带 BOM 时 startsWith("# ") 为 false，前言剥不掉
        assertEquals(withBom, RagServiceImpl.stripFrontMatter(withBom, "doc"),
                "BOM 挡在最前面，剥前言会失效 —— 这正是必须先剥 BOM 的原因");

        // 剥掉 BOM 之后才恢复正常
        String result = RagServiceImpl.stripFrontMatter(RagServiceImpl.stripBom(withBom), "doc");
        assertTrue(result.startsWith("## 一、正文开始"), "剥掉 BOM 后应正常剥前言");

        // 没有 BOM 时不能动
        assertEquals(text, RagServiceImpl.stripBom(text), "没有 BOM 就不该截字符");
    }
}
