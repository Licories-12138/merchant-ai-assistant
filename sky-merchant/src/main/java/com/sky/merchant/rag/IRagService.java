package com.sky.merchant.rag;

/**
 * RAG 问答服务。对外只有两个动作：把文档灌进库、拿问题换答案。
 *
 * 实现类要串起来的链路（六段，按顺序）：
 *   ① 入库切分：MarkdownHeaderSplitter(PARENT_SIZE, PARENT_OVERLAP) 切父块
 *               → RecursiveCharacterSplitter(CHILD_SIZE, 0) 切子块
 *               → 只有子块算向量 → 交 VectorStore 落库
 *   ② 检索·向量路：查询向量化 → 对全部子块算余弦 → 排序
 *   ③ 检索·关键词路：bigram + IDF + 停用词 → 命中比例打分 → 排序
 *   ④ 融合：两路 RRF（只看名次不看分数，绕开余弦 0.2~0.6 与命中率 0~1 的量纲差）
 *   ⑤ 折算：子块名次 → 父块名次（一个父块的名次 = 它名下最早出现的子块名次）
 *   ⑥ 生成：取 TOP_K 个父块拼上下文 → 模型回答 → 组装 citations
 *
 * 两个容易写错的地方：
 *   1) 相似度的绝对值不能当阈值。余弦在日常文本上挤在 0.2~0.6，与"是否相关"没有单调关系，
 *      所以判"找到没找到"只能看 top-k 排序，不能写 score > 0.7 这种判断。
 *   2) 引用要返回父块原文，不是子块 —— 子块只有 CHILD_SIZE 个字，单独拿出来常常读不懂。
 */
@SuppressWarnings("JavadocBlankLines")
public interface IRagService
{
    /**
     * 把一份文档切分、向量化并写入存储。
     *
     * 幂等语义：同一个 docName 重复调用 = 整篇重建，不会重复累积。
     * 实现里必须按这个顺序编排，且每一步都有它存在的理由：
     *   ① 父块切分 → ② 父块落库拿 id → ③ 父块切子块并记下归属 → ④ 子块向量化 → ⑤ 子块落库（带 parent_id）
     * 顺序不能调：子块落库时 parent_id 必须已经是真实父块 id；
     * 也不要只 insert 不 delete —— 改过切分参数后新旧块会混在一起，检索互相稀释。
     *
     * @param docName 文档名，重复调用同一个名字会整篇重建（不会重复累积）
     * @param text    文档全文（Markdown 优先，标题层级会参与切分）
     * @return 写入的块数（父 + 子）
     */
    int indexDocument(String docName, String text);

    /**
     * 提问。
     *
     * @param question 用户问题
     * @return 回答 + 引用。检索不到材料时 answer 为空、error 有值，不抛异常
     */
    RagAnswer ask(String question);
}
