package com.sky.merchant.rag;

import lombok.Data;

/**
 * 入库请求体。
 *
 * 为什么参数走 JSON 请求体而不是 URL：
 * 一篇文档动辄上万字，塞进 URL 会被 Tomcat 的参数长度限制截掉（默认 8KB 量级），
 * 而且截断是静默的 —— 表现为"入库成功但后半篇搜不到"，极难查。
 *
 * 为什么单独建一个类而不是用 Map：
 * 两个字段用 Map 也能跑，但 docName 拼错（docname / doc_name）时不会有任何提示，
 * 只会得到 null 然后抛一句"docName 不能为空"。有类型才能让错误在编译期暴露。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
public class RagIndexRequest
{
    /** 文档名，逻辑主键。同一个名字重复提交 = 整篇重建（不是追加） */
    private String docName;

    /** 文档全文。Markdown 优先 —— 标题层级会参与父块切分 */
    private String text;
}
