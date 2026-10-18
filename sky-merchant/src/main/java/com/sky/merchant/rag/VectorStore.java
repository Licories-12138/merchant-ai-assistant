package com.sky.merchant.rag;

import java.util.Collection;
import java.util.List;

/**
 * 向量存储接口：只负责「块和向量怎么存取」，不负责「怎么检索」。
 *
 * 为什么把检索算法摘出去：
 * 检索（向量路 + 关键词路 + RRF 融合 + 折算父块）是一套纯计算，输入是几组 float 数组，
 * 跟存在 MySQL 还是 PostgreSQL 完全无关。放在这里会导致换库时算法跟着重写。
 * 现在的位置是：本接口管存取，{@link IRagService} 的实现管算法 —— 换存储不用碰算法。
 *
 * 为什么写入拆成 saveParent / saveChildren 两个原子操作：
 * 一开始设计成一个 replaceDocument(docName, 所有块)，调用方写起来省事，
 * 但「父块要先插拿到自增 id，再拿这个 id 去填子块的 parent_id」这件事就被藏进实现里了。
 * 那正是整条链路上最容易写错的一步，藏在别人看不见的地方不合适。
 * 拆开之后，「先父后子」这个顺序在调用方代码里是显式可见的。
 *
 * 为什么现在用 MySQL 而不是向量库：
 * 本机没有 Docker、没有 PostgreSQL（原方案的 PGVector 跑不了）；
 * 而门店知识库的量级是「几十到几百个块」，ANN 索引带来的收益为零 ——
 * 全量向量读进内存算余弦，一次也就几百次乘加，比一次网络往返还便宜。
 * 真到万级以上再补一个 PgVectorStore 实现即可，上层不动。
 */
@SuppressWarnings("JavadocBlankLines")
public interface VectorStore
{
    // ============================================================
    // 写入
    // ============================================================

    /**
     * 插入一个父块，返回数据库生成的自增 id。
     *
     * 为什么父块逐条插、不像子块那样批量：
     * 父块数量少（一份文档几十个），而且每个都要拿回 id 才能继续 —— 逐条插最清楚。
     * 为了省这几十次往返去折腾"批量插入返回主键"，是拿可读性换几乎看不见的性能。
     *
     * @param parent 父块。level 必须是 LEVEL_PARENT，parentId 必须为 null
     * @return 数据库生成的主键
     */
    Long saveParent(RagChunk parent);

    /**
     * 批量插入子块。调用前必须已把每个子块的 parentId 填成真实的父块 id。
     *
     * 为什么子块必须批量：一份文档几百个块，逐条插就是几百次网络往返；
     * 而子块不需要回填 id，正好适合 batchUpdate。
     *
     * @param children 子块列表。level 必须是 LEVEL_CHILD，parentId 不能为空
     * @return 实际插入行数
     */
    int saveChildren(List<RagChunk> children);

    /**
     * 删除某个文档的全部块。
     *
     * 重建索引前必须先调这个 —— 只 insert 不 delete 的话，
     * 改过切分参数后新旧两套块会混在同一份文档里，检索时互相稀释。
     * 这种 bug 不报错，只表现为"检索质量莫名其妙变差"，极难定位。
     *
     * @return 删除行数
     */
    int deleteDocument(String docName);

    // ============================================================
    // 读取
    // ============================================================

    /**
     * 该文档是否已经入过库。
     * 用途：启动时判断要不要重建索引，避免每次重启都烧一遍 embedding。
     */
    boolean exists(String docName);

    /**
     * 取出全部子块（含 content 与 embedding），建关键词索引和算余弦都靠它。
     *
     * 为什么允许一次全量读：量级小（几百块），而且关键词路的 IDF 本来就需要全库的文档频率；
     * 分页读反而会让 IDF 算错 —— 那属于"看起来正常、结果就是不对"的一类问题。
     *
     * @return 全部 level=LEVEL_CHILD 的块，按 docName + seq 排序
     */
    List<RagChunk> loadAllChildren();

    /**
     * 按 id 批量取父块原文，用于拼上下文和引用。
     *
     * 为什么要单独一个方法：命中是子块，但返回给用户/模型的必须是父块原文 ——
     * 240 字的子块单独拿出来常常读不懂（少了上文）。
     *
     * @param parentIds 父块 id 集合。传空集合必须直接返回空列表，不要拼出 IN () 这种非法 SQL
     * @return 父块列表，顺序不保证；调用方按 id 自己映射
     */
    List<RagChunk> loadParents(Collection<Long> parentIds);

    // ============================================================
    // 维护
    // ============================================================

    /**
     * 清空整张表。只给测试和"重建全部索引"用。
     */
    void truncate();
}
