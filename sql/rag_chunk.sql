-- ============================================================
-- RAG 父子块与向量存储
-- 设计要点见 com.sky.merchant.rag.VectorStore / RagChunk 的类注释
--
-- 为什么不用专门的向量库：
--   门店知识库量级是几十到几百个块，ANN 索引收益为零；
--   全量向量读进内存算余弦，几百次乘加比一次网络往返还便宜。
--   本机也没有 Docker / PostgreSQL，装环境的成本换不来任何效果。
--
-- 为什么一张表装父子两层：
--   两层结构完全一样（docName / seq / content），差别只是 level 和 parentId。
--   拆两张表会让"按父块 id 找子块""按文档重建"这两件事都要跨表写，
--   而收益仅仅是少一个字段。等真出现"父块需要独立元数据"再加表不迟。
-- ============================================================

CREATE TABLE IF NOT EXISTS rag_chunk (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_name     VARCHAR(255) NOT NULL                COMMENT '文档名，逻辑主键；同文档重建时先删后插',
    parent_id    BIGINT                DEFAULT NULL   COMMENT '父块 id；父块自身为 NULL，子块必填',
    level        TINYINT      NOT NULL                COMMENT '1=父块（喂模型） 2=子块（被搜索）',
    seq          INT          NOT NULL DEFAULT 0      COMMENT '同层内顺序，检索结果按它还原原文顺序',
    content      TEXT         NOT NULL                COMMENT '块原文',
    embedding    MEDIUMTEXT            DEFAULT NULL   COMMENT '向量 JSON 数组；只有子块有值',
    content_hash CHAR(32)              DEFAULT NULL   COMMENT 'content 的 md5，用于增量重建时跳过未变块',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_doc_level (doc_name, level, seq),
    KEY idx_parent (parent_id),
    KEY idx_hash (content_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = 'RAG 父子块与向量';

-- ============================================================
-- 使用说明
--
-- 1) 入库顺序必须是「先插父块拿到自增 id，再拿这个 id 去填子块的 parent_id」，
--    不能父子混在一批 insert 里 —— 子块写入时还不知道父块 id。
--
-- 2) embedding 存 JSON 文本（例如 [0.0123,-0.0456,...]），不用 BLOB：
--    可读性对排障价值很大（一眼能看出是不是全 0、维度对不对）。
--    1024 维约 12KB，MEDIUMTEXT 上限 16MB，够用。
--    ⚠️ 千万别用 float[].toString() 序列化 —— 那会写成 [F@1a2b3c，静默坏数据。
--
-- 3) 检索时一次性把 level=2 的行读进内存（loadAllChildren），不要逐条查：
--    N+1 查询在这里是纯亏，而关键词路的 IDF 本来就需要全库文档频率。
--
-- 4) 重建某文档：DELETE FROM rag_chunk WHERE doc_name = ?; 然后重新入库。
--    不要只 insert —— 改过切分参数后新旧块会混在一起，检索互相稀释。
-- ============================================================
