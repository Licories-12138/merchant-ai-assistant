package com.sky.merchant.rag.impl;

import com.sky.merchant.constant.RagLimits;
import com.sky.merchant.rag.RagChunk;
import com.sky.merchant.rag.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MysqlVectorStore 的集成测试（真库、真 SQL）。
 *
 * 为什么是集成测试而不是单元测试：
 * 这一层唯一的产出就是「SQL 语句和 Java 对象之间的翻译」，
 * 用 mock 的 JdbcTemplate 测等于在测 mock 自己 —— 真正会错的地方是列名拼错、
 * 类型不匹配、回滚边界，这些只有打真库才能暴露。
 *
 * 为什么必须设 webEnvironment = RANDOM_PORT（不能是 NONE）：
 * 若依的 PermitAllUrlProperties 在启动时会遍历 requestMappingHandlerMapping 去找 @Anonymous 注解，
 * 一旦关掉 Web 层就没有这个 Bean，整个上下文都起不来（报 No bean named 'requestMappingHandlerMapping'）。
 * 用 RANDOM_PORT 而不是 DEFINED_PORT，是为了不和本机正在运行的 8080 应用抢端口。
 *
 * 测试数据用固定的 docName 前缀隔离：
 * 每个用例前后都只删自己那份（deleteDocument），不调 truncate，
 * 这样跑测试不会清掉库里真实的业务数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("MysqlVectorStore 集成测试")
class MysqlVectorStoreTest
{
    /** 测试专用文档名。用双下划线包起来，一眼能看出是测试数据 */
    private static final String DOC = "__it_rag_chunk__";

    @Autowired
    private VectorStore store;

    @BeforeEach
    void cleanUpBefore()
    {
        store.deleteDocument(DOC);
    }

    @AfterEach
    void cleanUpAfter()
    {
        store.deleteDocument(DOC);
    }

    // ============================================================
    // 造数据的小工具
    // ============================================================

    /** 造一个父块。level 必须是 LEVEL_PARENT，parentId 必须为空 */
    private RagChunk parent(int seq)
    {
        RagChunk chunk = new RagChunk();
        chunk.setDocName(DOC);
        chunk.setParentId(null);
        chunk.setLevel(RagLimits.LEVEL_PARENT);
        chunk.setSeq(seq);
        chunk.setContent("父块内容 #" + seq);
        chunk.setEmbedding(null);
        chunk.setContentHash(null);
        return chunk;
    }

    /**
     * 造一个子块。
     * 向量填的是「递增的小数」而不是全 0 —— 全 0 测不出精度丢失，
     * 递增的值能验证 toJson / fromJson 往返后每个元素都对得上。
     */
    private RagChunk child(Long parentId, int seq)
    {
        RagChunk chunk = new RagChunk();
        chunk.setDocName(DOC);
        chunk.setParentId(parentId);
        chunk.setLevel(RagLimits.LEVEL_CHILD);
        chunk.setSeq(seq);
        chunk.setContent("子块内容 #" + seq);
        chunk.setEmbedding(testVector());
        chunk.setContentHash(null);
        return chunk;
    }

    /** 造一条 1024 维的测试向量，值域 0 ~ 1.023 */
    private float[] testVector()
    {
        float[] vector = new float[1024];
        for (int i = 0; i < vector.length; i++)
        {
            vector[i] = i * 0.001f;
        }
        return vector;
    }

    /** 只看本测试文档的子块（库里可能有别的数据，不能直接断言全表条数） */
    private List<RagChunk> myChildren()
    {
        List<RagChunk> mine = new ArrayList<>();
        for (RagChunk chunk : store.loadAllChildren())
        {
            if (DOC.equals(chunk.getDocName()))
            {
                mine.add(chunk);
            }
        }
        return mine;
    }

    // ============================================================
    // 1. exists
    // ============================================================

    @Test
    @DisplayName("没入过库的文档，exists 返回 false")
    void existsShouldReturnFalseForUnknownDoc()
    {
        assertFalse(store.exists(DOC), "清空后不该认为该文档存在");
        assertFalse(store.exists(""), "空串是非法 docName，应返回 false 而不是查全表");
    }

    // ============================================================
    // 2. saveParent
    // ============================================================

    @Test
    @DisplayName("保存父块能拿到自增主键，且两次递增")
    void saveParentShouldReturnIncreasingIds()
    {
        Long p1 = store.saveParent(parent(0));
        Long p2 = store.saveParent(parent(1));

        assertNotNull(p1, "必须拿到主键");
        assertNotNull(p2, "必须拿到主键");
        assertTrue(p2 > p1, "第二次的主键必须更大，否则说明拿错了主键");
        assertTrue(store.exists(DOC), "插完父块后 exists 应为 true");
    }

    @Test
    @DisplayName("saveParent 拒绝子块（防止插出 parent_id 为空的脏数据）")
    void saveParentShouldRejectChild()
    {
        RagChunk bad = child(1L, 0);
        assertThrows(IllegalArgumentException.class, () -> store.saveParent(bad));
    }

    // ============================================================
    // 3. saveChildren + 4. loadAllChildren（向量往返）
    // ============================================================

    @Test
    @DisplayName("批量保存子块，读回来条数与向量维度都正确")
    void saveChildrenShouldPersistAndRoundTripEmbedding()
    {
        Long parentId = store.saveParent(parent(0));

        List<RagChunk> kids = new ArrayList<>();
        for (int seq = 0; seq < 3; seq++)
        {
            kids.add(child(parentId, seq));
        }
        assertEquals(3, store.saveChildren(kids), "应返回实际插入条数");
        assertEquals(0, store.saveChildren(List.of()), "空列表是合法输入，应返回 0 而不是抛异常");

        List<RagChunk> loaded = myChildren();
        assertEquals(3, loaded.size(), "应读回 3 条子块");

        for (RagChunk chunk : loaded)
        {
            assertEquals(parentId, chunk.getParentId(), "parent_id 必须能原样读回");
            assertEquals(RagLimits.LEVEL_CHILD, chunk.getLevel());
            assertNotNull(chunk.getEmbedding(), "embedding 不该是 null");

            float[] vector = chunk.getEmbedding();
            assertEquals(1024, vector.length, "向量维度必须是 1024");
            // 抽查首、中、尾三个位置，确认 toJson / fromJson 往返没丢精度
            assertEquals(0f, vector[0], 1e-6f);
            assertEquals(512 * 0.001f, vector[512], 1e-6f);
            assertEquals(1023 * 0.001f, vector[1023], 1e-6f);
        }
    }

    @Test
    @DisplayName("saveChildren 拒绝 parentId 为空的子块")
    void saveChildrenShouldRejectNullParentId()
    {
        RagChunk bad = child(null, 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.saveChildren(List.of(bad)));
        assertTrue(e.getMessage().contains("parentId"), "错误信息应指出是 parentId 的问题");
    }

    // ============================================================
    // 5. loadParents
    // ============================================================

    @Test
    @DisplayName("按 id 取父块：空集合返回空列表，非空按 id 取到")
    void loadParentsShouldHandleEmptyAndNonEmpty()
    {
        assertTrue(store.loadParents(List.of()).isEmpty(),
                "空集合必须直接返回空列表，不能拼出 IN () 非法 SQL");

        Long p1 = store.saveParent(parent(0));
        Long p2 = store.saveParent(parent(1));

        List<RagChunk> loaded = store.loadParents(List.of(p1, p2));
        assertEquals(2, loaded.size());
        for (RagChunk chunk : loaded)
        {
            assertNull(chunk.getParentId(), "父块的 parent_id 必须是 null；若读成 0 说明用了 getLong 而不是 getObject");
            assertEquals(RagLimits.LEVEL_PARENT, chunk.getLevel());
        }
    }

    // ============================================================
    // 6. deleteDocument
    // ============================================================

    @Test
    @DisplayName("删文档会连带删掉它的父子块")
    void deleteDocumentShouldRemoveAllChunks()
    {
        Long parentId = store.saveParent(parent(0));
        store.saveChildren(List.of(child(parentId, 0), child(parentId, 1)));
        assertEquals(2, myChildren().size());

        int deleted = store.deleteDocument(DOC);

        assertEquals(3, deleted, "父块 1 条 + 子块 2 条 = 3 行");
        assertFalse(store.exists(DOC));
        assertEquals(0, myChildren().size(), "删完之后不该还能读到子块");
    }
}
