package com.sky.merchant.rag;

import lombok.Data;

import java.util.List;

/**
 * RAG 一次问答的返回体。
 *
 * 为什么 error 是字段而不是抛异常：
 * "资料里没有"是一种正常结果，不是系统故障。抛异常会让上层分不清
 * "检索挂了"和"确实没找到"，前端也没法区分该提示用户换个问法还是该重试。
 * 所以失败也走正常返回，用 error 说明原因 —— 这与 Nl2SqlResult 的处理方式保持一致。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
public class RagAnswer
{
    /** 模型基于检索结果给出的回答。未检索到材料时为空串，同时 error 有值 */
    private String answer;

    /** 引用列表，按相关度降序。可能为空（模型说"资料未覆盖"时） */
    private List<RagCitation> citations;

    /** 检索命中的父块数与送进上下文的字符数，用于成本观测 */
    private int contextChunks;

    /** 送进模型的上下文字符数。这是最直接的成本指标 */
    private int contextChars;

    /** 失败原因。非 null 表示这次没有正常回答 */
    private String error;
}
