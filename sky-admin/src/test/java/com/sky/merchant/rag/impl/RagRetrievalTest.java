package com.sky.merchant.rag.impl;

import com.sky.merchant.rag.RagChunk;
import com.sky.merchant.rag.RetrievalHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索层的纯算法测试：不打库、不调模型、不起 Spring。
 *
 * 为什么单独测这一层：
 * ask 整条链路上，唯一"算错了也不报错、只表现为结果莫名其妙变差"的就是打分和折算。
 * 把它隔离出来用手工构造的向量断言，比跑一遍真模型便宜一万倍，而且是确定性的 ——
 * 真模型那条链路的输出每次都不一样，用来看"逻辑对不对"根本不可靠。
 *
 * 向量取 3 维，余弦可以手算（查询向量取 (1,0,0)）：
 *   (1,0,0) → 1/1 = 1.0
 *   (4,3,0) → 4/5 = 0.8
 *   (3,4,0) → 3/5 = 0.6
 *   (0,1,0) → 0/1 = 0.0
 */
@SuppressWarnings("JavadocBlankLines")
class RagRetrievalTest
{
    private static final float[] QUERY = {1f, 0f, 0f};

    /**
     * 造一个子块。向量用可变参数传，方便在用例里直接看出预期余弦。
     */
    private static RagChunk child(long id, long parentId, int seq, float... vec)
    {
        RagChunk chunk = new RagChunk();
        chunk.setId(id);
        chunk.setDocName("doc");
        chunk.setParentId(parentId);
        chunk.setLevel(2);
        chunk.setSeq(seq);
        chunk.setContent("子块 " + id);
        chunk.setEmbedding(vec);
        return chunk;
    }

    @Test
    @DisplayName("折算：同一个父块的多个子块只贡献一条，取其中最高分")
    void foldTakesBestChildOfEachParent()
    {
        List<RagChunk> children = new ArrayList<>();
        children.add(child(101, 1, 0, 4f, 3f, 0f));   // cos 0.8
        children.add(child(102, 1, 1, 1f, 0f, 0f));   // cos 1.0 —— 父块 1 的最优
        children.add(child(201, 2, 2, 3f, 4f, 0f));   // cos 0.6

        List<RetrievalHit> hits = RagServiceImpl.rankByCosine(QUERY, children);

        assertEquals(2, hits.size(), "三个子块只属于两个父块，结果只能有两条");

        RetrievalHit first = hits.get(0);
        assertEquals(1L, first.getParentId().longValue());
        assertEquals(1.0, first.getScore(), 1e-9, "父块的分数必须是名下最高的那个子块分");
        assertEquals(102L, first.getChildId().longValue(), "贡献名次的必须是那个 1.0 的子块");
        assertEquals(1, first.getChildRank(), "余弦 1.0 在子块全序里排第 1");

        RetrievalHit second = hits.get(1);
        assertEquals(2L, second.getParentId().longValue());
        assertEquals(0.6, second.getScore(), 1e-9);
        assertEquals(201L, second.getChildId().longValue());
    }

    @Test
    @DisplayName("排序：分数降序，同分按 parentId 升序兜底")
    void sortedByScoreThenParentId()
    {
        List<RagChunk> children = new ArrayList<>();
        children.add(child(301, 3, 0, 3f, 4f, 0f));   // 0.6
        children.add(child(201, 2, 1, 3f, 4f, 0f));   // 0.6，与上一条同分
        children.add(child(101, 1, 2, 4f, 3f, 0f));   // 0.8

        List<RetrievalHit> hits = RagServiceImpl.rankByCosine(QUERY, children);

        assertEquals(3, hits.size());
        assertEquals(1L, hits.get(0).getParentId().longValue(), "0.8 最大，排第一");
        assertEquals(2L, hits.get(1).getParentId().longValue(), "两个 0.6 同分，parentId 小的在前");
        assertEquals(3L, hits.get(2).getParentId().longValue());
    }

    @Test
    @DisplayName("脏数据：向量为 null 或维度不符的子块被跳过，不抛异常")
    void skipsBrokenEmbeddings()
    {
        RagChunk noVector = child(401, 4, 0);
        noVector.setEmbedding(null);

        List<RagChunk> children = new ArrayList<>();
        children.add(noVector);
        children.add(child(501, 5, 1, 1f, 0f));       // 2 维，与 3 维查询长度不符
        children.add(child(101, 1, 2, 1f, 0f, 0f));   // 唯一有效的

        List<RetrievalHit> hits = RagServiceImpl.rankByCosine(QUERY, children);

        assertEquals(1, hits.size(), "坏的被跳过，只剩一条有效的");
        assertEquals(1L, hits.get(0).getParentId().longValue());
    }

    @Test
    @DisplayName("空输入返回空列表，不抛异常")
    void emptyInput()
    {
        assertTrue(RagServiceImpl.rankByCosine(QUERY, List.of()).isEmpty(), "没有子块就该是空");
        assertTrue(RagServiceImpl.rankByCosine(null, List.of(child(101, 1, 0, 1f, 0f, 0f))).isEmpty(),
                "查询向量为 null 时不该硬算");
    }

    @Test
    @DisplayName("名次是子块全序名次，不是父块名次")
    void childRankIsGlobalAmongChildren()
    {
        List<RagChunk> children = new ArrayList<>();
        children.add(child(101, 1, 0, 4f, 3f, 0f));   // 0.8 → 全序第 1
        children.add(child(201, 2, 1, 3f, 4f, 0f));   // 0.6 → 全序第 2
        children.add(child(202, 2, 2, 0f, 1f, 0f));   // 0.0 → 全序第 3

        List<RetrievalHit> hits = RagServiceImpl.rankByCosine(QUERY, children);

        assertEquals(1, hits.get(0).getChildRank());
        assertEquals(2, hits.get(1).getChildRank(), "父块 2 的名次来自它名下最优子块，全序第 2");
        assertEquals(201L, hits.get(1).getChildId().longValue(), "父块 2 取的是 0.6 那个，不是 0.0");
    }
}
