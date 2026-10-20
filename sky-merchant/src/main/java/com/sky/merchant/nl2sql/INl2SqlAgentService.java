package com.sky.merchant.nl2sql;

/**
 * 带自愈能力的 NL2SQL：生成 → 校验 → 执行 → 看报错 → 重写。
 *
 * 和 {@link INl2SqlService} 的区别只有一条：
 * 线性版执行失败就返回失败；这个版本会把**数据库原始报错**喂回模型，让它自己改。
 *
 * 为什么单独一个接口而不是给原来那个加个重载：
 *  1) 两个流程的语义不同（一个"问一次答一次"，一个"循环到成功或放弃"），混在一个方法里靠参数区分最难看
 *  2) 保留线性版才能做对照 —— 自愈多花了几轮、多烧几次 token、换来多少成功率，
 *     有了对照组才说得清。这也正是"手写循环 vs 现成框架"那段讨论的素材。
 */
@SuppressWarnings("JavadocBlankLines")
public interface INl2SqlAgentService
{
    /**
     * 提问并按需自愈重试。
     *
     * 与线性版的关键差异在失败路径：
     *   线性版：执行失败 → 直接返回 error
     *   自愈版：执行失败 → 把【上一版 SQL + 数据库报错原文】追加进对话 → 回到生成
     *
     * 但有一条**绝对不能自愈**：
     *   校验器拦下的 SQL（越权表、非 SELECT、敏感列、SELECT *）必须直接返回，不重试。
     *   允许重试等于给模型 N 次机会去试着绕过安全闸门 —— 自愈是给"手滑"用的，不是给"越权"用的。
     *
     * @param question 用户的中文问题
     * @param userId   当前登录用户，用于数据范围重写；为 null 时按「只读公共数据」处理
     * @return 最终结果。无论成功失败，attempts 里都有完整的逐轮轨迹
     */
    Nl2SqlResult agentQuery(String question, Long userId);
}
