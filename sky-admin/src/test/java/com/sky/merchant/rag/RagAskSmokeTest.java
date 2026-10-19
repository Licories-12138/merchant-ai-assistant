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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ask 链路的冒烟测试：只为验证"整条路能走通"，不验证答案质量。
 *
 * 为什么质量不在这里断言：
 * 模型输出每次都不一样，拿它写 assertEquals 等于给自己埋一颗随机红的雷。
 * 质量用两个信号人工看一次就够了 —— 上下文长度（成本）和 citations 的 chunkId（命中位置）。
 * 真正要机器守的东西（打分、折算、排序）已经在 RagRetrievalTest 里测过了，
 * 那一层是确定性的，这一层不是。
 *
 * 会真调 embedding 和 chat 两次接口，所以语料不在就 assumeTrue 跳过，别让 CI 红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("RAG 问答冒烟")
class RagAskSmokeTest
{
    /** 测试专用文档名，跑完就删，不污染真实数据 */
    private static final String DOC = "__it_rag_ask__";

    /** 语料：Day15 的 Redis 笔记，标题结构清晰，适合看命中落在哪一节 */
    private static final Path CORPUS =
            Path.of("D:/WorkBuddy-Workspace/后端架构师/笔记/Day15-持久化与主从复制.md");

    /**
     * 术语型提问。故意不用口语化的说法：
     * 历史实验里纯向量路在术语型上最强（Hit@1 27/30），
     * 冒烟阶段要先把"最好情况"跑通，口语型的差距等加了关键词路再看。
     */
    private static final String QUESTION = "断点续传依靠哪三个要素？";

    @Autowired
    private IRagService ragService;

    @Autowired
    private VectorStore vectorStore;

    @BeforeEach
    void setUp()
    {
        vectorStore.deleteDocument(DOC);
    }

    @AfterEach
    void tearDown()
    {
        vectorStore.deleteDocument(DOC);
    }

    @Test
    @DisplayName("入库后能问出答案并带回引用")
    void askShouldReturnAnswerWithCitations() throws IOException
    {
        Assumptions.assumeTrue(Files.isReadable(CORPUS), "语料文件不存在，跳过：" + CORPUS);
        String text = Files.readString(CORPUS, StandardCharsets.UTF_8);

        int written = ragService.indexDocument(DOC, text);
        assertTrue(written > 0, "入库应写入至少一个块");

        RagAnswer answer = ragService.ask(QUESTION);

        // ---------- 打印，这一段才是这次要人看的东西 ----------
        System.out.println();
        System.out.println("================ ask 冒烟 ================");
        System.out.println("问题：" + QUESTION);
        System.out.println("入库块数：" + written);
        System.out.println("命中父块数：" + answer.getContextChunks());
        System.out.println("上下文长度：" + answer.getContextChars() + " 字符");
        System.out.println("---------- 引用（按相关度降序）----------");
        if (answer.getCitations() != null)
        {
            int i = 1;
            for (RagCitation citation : answer.getCitations())
            {
                System.out.println(i++ + ". chunkId=" + citation.getChunkId()
                        + "  score=" + String.format("%.4f", citation.getScore()));
                System.out.println("   " + citation.getSnippet());
            }
        }
        System.out.println("---------- 回答 ----------");
        System.out.println(answer.getAnswer());
        System.out.println("------------------------------------------");
        if (answer.getError() != null)
        {
            System.out.println("error：" + answer.getError());
        }
        System.out.println("==========================================");

        // ---------- 只断言"路通了"，不断言"答得对不对" ----------
        assertNotNull(answer, "ask 不该返回 null");
        assertNull(answer.getError(), "不该走失败分支：" + answer.getError());
        assertNotNull(answer.getAnswer(), "answer 字段不该为 null");
        assertFalse(answer.getAnswer().isBlank(), "回答不该是空的");
        assertNotNull(answer.getCitations(), "引用列表不该为 null");
        assertFalse(answer.getCitations().isEmpty(), "至少该带回一条引用");
        assertTrue(answer.getContextChunks() > 0, "命中父块数应大于 0");
        assertTrue(answer.getContextChars() > 0, "上下文长度应大于 0");
    }
}
