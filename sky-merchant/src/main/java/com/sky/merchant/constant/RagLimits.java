package com.sky.merchant.constant;

/**
 * RAG 检索链路的参数。
 * 为什么单独一个类而不是散在实现里：这几个数是从 llm-demo 的对照实验里定下来的，
 * 每个值都有实验出处（26 节对照实验报告 + Day19 笔记），改动需要理由 —— 收口到一处才好审。
 *
 * 这些值不是拍脑袋来的，每一个都对应一组实验：
 *   PARENT_SIZE     300    父块大小。放大到 800 无收益，却要多付 2.2 倍 token
 *   PARENT_OVERLAP  100    父块重叠。只补偿"标题边界被硬切"的损失，按标题切时才有正收益
 *   CHILD_SIZE      240    子块大小。80/120/180/240 四档扫描，240 与混合检索搭配时最省存储
 *   RRF_K           60     RRF 公式常数，原论文默认值，作用是压小前几名之间的差距
 *   TOP_K           5      送进上下文的父块数。3 → 5 换 +4 个答案要点，5 → 8 只再换 +2
 *   EMBED_BATCH     10     一次请求向量化多少条文本，百炼接口单批不宜过大
 *
 * 一句话：切分管平均、父子块管粒度、top-k 管召回，三者互相咬合，别单独拧一个。
 */
@SuppressWarnings("JavadocBlankLines")
public final class RagLimits
{
    /** 父块大小（字符）。喂给模型的那一层 */
    public static final int PARENT_SIZE = 300;

    /** 父块重叠（字符）。只在标题边界兜底处生效 */
    public static final int PARENT_OVERLAP = 100;

    /** 子块大小（字符）。被搜索的那一层 */
    public static final int CHILD_SIZE = 240;

    /** RRF 融合常数 k */
    public static final int RRF_K = 60;

    /** 检索后返回的父块数量 */
    public static final int TOP_K = 5;

    /** 一次向量化请求的批大小 */
    public static final int EMBED_BATCH = 10;

    /**
     * 单次入库允许的文档大小上限（字节）。
     *
     * 为什么不直接用 multipart 那 10MB：
     * 10MB 的 markdown 大约能切出上万个块，对应上千次 embedding 请求 —— 跑一次就把额度烧光，
     * 而且是同步跑的，HTTP 请求会直接超时。512KB 对一篇笔记有二十多倍余量，足够。
     */
    public static final int MAX_UPLOAD_BYTES = 512 * 1024;

    /** 允许上传的扩展名。只收纯文本：PDF/Word 读出来是乱码，入库等于污染知识库 */
    public static final java.util.Set<String> ALLOWED_EXTENSIONS =
            java.util.Set.of("md", "markdown", "txt");

    /** 块层级：父块。给模型读 */
    public static final int LEVEL_PARENT = 1;

    /** 块层级：子块。被搜索 */
    public static final int LEVEL_CHILD = 2;

    private RagLimits() { }
}
