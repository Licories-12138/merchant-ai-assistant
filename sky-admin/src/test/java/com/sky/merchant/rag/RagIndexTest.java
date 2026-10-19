package com.sky.merchant.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库链路验收：把一份真实文档灌进库，检查父子块的数量与形态。
 *
 * 这个测试的价值不在于"断言写得漂亮"，而在于它把验收标准变成了可执行的东西：
 * 手工去 DataGrip 里敲 GROUP BY 也能查，但每次改切分参数都要重来一遍，
 * 而且很容易只看条数、忘了检查"子块有没有向量""parentId 是不是都填了"。
 *
 * 需要真实调用 embedding 接口（会消耗少量额度），
 * 所以用 assumeTrue 保护：语料文件不存在时直接跳过，不让 CI 红。
 *
 * 注意：@SpringBootTest 的 webEnvironment 必须是 RANDOM_PORT —— 若依的
 * PermitAllUrlProperties 启动时要遍历 HandlerMapping，关掉 Web 层整个上下文起不来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("RAG 入库链路验收")
class RagIndexTest
{
    /** 测试专用文档名，跑完就删，不污染真实数据 */
    private static final String DOC = "__it_rag_index__";

    /** 语料：Day15 的 Redis 笔记。数字（55 / 95）就是基于这份文件的历史实测值 */
    private static final Path CORPUS =
            Path.of("D:/WorkBuddy-Workspace/后端架构师/笔记/Day15-持久化与主从复制.md");

    @Autowired
    private IRagService ragService;

    @Autowired
    private VectorStore vectorStore;

    @BeforeEach
    void cleanBefore()
    {
        vectorStore.deleteDocument(DOC);
    }

    @AfterEach
    void cleanAfter()
    {
        vectorStore.deleteDocument(DOC);
    }

    @Test
    @DisplayName("入库后父子块数量与向量形态符合预期")
    void indexDocumentShouldProduceExpectedShape() throws IOException
    {
        Assumptions.assumeTrue(Files.isReadable(CORPUS),
                "语料文件不存在，跳过：" + CORPUS);
        String text = Files.readString(CORPUS, StandardCharsets.UTF_8);

        int written = ragService.indexDocument(DOC, text);
        assertTrue(written > 0, "应写入至少一个块");

        // ---------- 从库里读回来，按 level 分开数 ----------
        List<RagChunk> parents = new ArrayList<>();
        List<RagChunk> children = new ArrayList<>();
        for (RagChunk chunk : vectorStore.loadAllChildren())
        {
            // loadAllChildren 只返回子块，父块要单独取，这里先把子块收好
            if (DOC.equals(chunk.getDocName()))
            {
                children.add(chunk);
            }
        }
        assertFalse(children.isEmpty(), "子块不该为空");

        // 父块：拿子块的 parentId 去重即可，不必再查一次库
        for (RagChunk child : children)
        {
            if (child.getParentId() == null)
            {
                throw new AssertionError("子块的 parentId 不该为 null：" + child.getContent());
            }
        }
        parents.addAll(vectorStore.loadParents(
                children.stream().map(RagChunk::getParentId).distinct().toList()));

        System.out.println();
        System.out.println("---------- 入库验收结果 ----------");
        System.out.println("文档字符数：" + text.length());
        System.out.println("父块：" + parents.size() + " 个");
        System.out.println("子块：" + children.size() + " 个");
        System.out.println("比例：" + String.format("%.2f", (double) children.size() / parents.size())
                + "（历史实测约 1.7）");
        System.out.println("indexDocument 返回值：" + written);
        System.out.println("----------------------------------");

        // ---------- 数量断言：用区间而不是精确值 ----------
        // 切分器在边界上的行为可能有细微波动，卡死数字会让测试变脆
        assertTrue(parents.size() >= 40 && parents.size() <= 70,
                "父块数量异常（期望 40~70，历史值 55）：实际 " + parents.size());
        assertTrue(children.size() >= 60 && children.size() <= 150,
                "子块数量异常（期望 60~150，历史值 95）：实际 " + children.size());
        assertTrue(children.size() > parents.size(),
                "子块数应明显多于父块数，否则说明父子切分搞混了");

        // ---------- 形态断言：这几条才是真正容易错的地方 ----------
        for (RagChunk child : children)
        {
            assertNotNull(child.getEmbedding(), "子块必须有向量");
            assertEquals(1024, child.getEmbedding().length, "向量维度必须是 1024");
            assertNotNull(child.getContent(), "子块内容不能为空");
            assertFalse(child.getContent().isBlank(), "子块内容不能是空白");
            assertEquals(1, countOccurrences(children, child.getDocName(), child.getSeq()),
                    "同一个文档内 seq 不能重复（重复说明 seq 生成逻辑有误）：" + child.getSeq());
        }

        // 每个父块至少被一个子块指向，否则它永远检索不到
        List<Long> referenced = children.stream().map(RagChunk::getParentId).distinct().toList();
        assertEquals(parents.size(), referenced.size(),
                "每个父块都应该至少有一个子块；数量不等说明有父块切不出子块");

        // ---------- 幂等：同一个 docName 再跑一次，数量不能翻倍 ----------
        int secondWrite = ragService.indexDocument(DOC, text);
        assertEquals(written, secondWrite, "重复入库应整篇重建，块数不变");

        List<RagChunk> childrenAgain = vectorStore.loadAllChildren().stream()
                .filter(c -> DOC.equals(c.getDocName()))
                .toList();
        assertEquals(children.size(), childrenAgain.size(),
                "重复入库后子块数不能翻倍，否则说明 deleteDocument 没生效");
    }

    private int countOccurrences(List<RagChunk> chunks, String docName, Integer seq)
    {
        int n = 0;
        for (RagChunk chunk : chunks)
        {
            if (docName.equals(chunk.getDocName()) && seq.equals(chunk.getSeq()))
            {
                n++;
            }
        }
        return n;
    }
}
