package com.sky.merchant.rag;

import lombok.Data;

/**
 * 一个检索块（父块或子块）。
 *
 * 父子块这一层为什么要存在：
 * 切分有个死结 —— 有的问题要「长而杂」的上下文（一行日志必须跟说明在一起），
 * 有的问题要「短而纯」（一个定义不能被别的内容稀释），而切分是一刀切。
 * 父子块的解法不是让一个块兼顾两者，而是让两层各管一头：
 * 子块负责被搜索（小、纯、匹配得准），父块负责被读（上下文完整）。
 *
 * 所以同一段原文在这里会出现两次：
 * level=1 的那条是父块（parentId 为空）
 * level=2 的那几条是子块（parentId 指向父块的 id）
 * 只有子块参与检索；命中后按 parentId 折算成父块，把父块原文喂给模型。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
public class RagChunk
{
    /** 库内主键。入库前为 null，由数据库自增回填 */
    private Long id;

    /** 所属文档名。跨文档检索时用来标注来源，也是增量重建的键 */
    private String docName;

    /** 父块 id。父块自身为 null，子块必填 */
    private Long parentId;

    /** 层级，取值见 RagLimits.LEVEL_PARENT / LEVEL_CHILD */
    private Integer level;

    /** 同层内顺序号。检索结果按它还原原文顺序，避免上下文被拼乱 */
    private Integer seq;

    /** 块原文。父块返回给模型，子块只用于匹配 */
    private String content;

    /**
     * 向量，只在子块上有值。
     * 为什么不给父块也算向量：父块的作用是被读，不参与相似度比较；
     * 算了反而要付一次 embedding 调用，而且会让"用哪个算分"这件事变得含糊。
     */
    private float[] embedding;

    /**
     * 原文 md5 十六进制串。重复入库时用来跳过没变的块。
     * 为什么不用内容本身做键：TEXT 列没法建索引，md5 只有 32 字节。
     */
    private String contentHash;
}
