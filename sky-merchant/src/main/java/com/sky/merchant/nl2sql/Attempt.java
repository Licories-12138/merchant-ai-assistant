package com.sky.merchant.nl2sql;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReAct 自愈循环里的一轮轨迹。
 *
 * 为什么要把每一轮都留下来，而不是只返回最终结果：
 * 自愈的价值全在"中间发生了什么"。只有最终结果的话，你无法回答三个问题：
 *   1) 它是不是一轮就成功了？（失败率有多高）
 *   2) 重试的那一轮，报错到底指向哪里？（是列名错还是表名错）
 *   3) 重试到底是修好了，还是碰运气碰上的？
 *
 * 另外轨迹也是排障入口：线上某个问题总是要重试才成，看轨迹立刻能定位是
 * schema 提示不够（模型老写错同一个列），还是校验器白名单漏了表。
 */
@SuppressWarnings("JavadocBlankLines")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attempt
{
    /** 第几轮，从 1 开始 */
    private int round;

    /** 这一轮模型生成的原始 SQL（已剥掉 markdown 围栏，但未经过校验器重写） */
    private String sql;

    /** 这一轮的失败原因；成功时为 null */
    private String error;

    /** 这一轮从"调模型前"到"拿到结果"的耗时（毫秒） */
    private long elapsedMs;

    /** 成功的一轮 */
    public static Attempt ok(int round, String sql, long elapsedMs)
    {
        return new Attempt(round, sql, null, elapsedMs);
    }

    /** 失败的一轮 */
    public static Attempt failed(int round, String sql, String error, long elapsedMs)
    {
        return new Attempt(round, sql, error, elapsedMs);
    }
}
