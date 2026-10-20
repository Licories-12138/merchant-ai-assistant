package com.sky.merchant.nl2sql.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自愈循环里「模板层」的测试。纯单元，不起 Spring、不调模型、不连库。
 *
 * ============================================================
 * 为什么单独有这一个测试类
 * ============================================================
 * ReActNl2SqlServiceImpl 的端到端验证（Nl2SqlAgentTest）实测下来三个场景全是「第 1 轮就结束」：
 * 正常问题答得出来，不可答的问题模型会主动回 NO_QUERY。
 * 也就是说 **循环的第二轮、RETRY_HINT 的拼装、循环走完的兜底 return，从来没被真正执行过**。
 *
 * 造不出「能过校验器、但执行会失败」的输入 —— 校验器挡在前面，它能放行的 SQL 基本也能执行成功。
 * 要硬造就得连 ALLOWED_COLUMNS 一起改，那是拿污染产品代码换覆盖率，不划算。
 *
 * 所以退一步：把「模板本身」钉死。它挡不住循环逻辑错，但能挡住最容易犯的那类错 ——
 * 改模板时多写或少写一个 %s，调用点没有同步。
 *
 * ============================================================
 * 记住这个已知缺口，别对外声称自愈被完整验证过
 * ============================================================
 * 已覆盖：第 1 轮成功 / 模型拒答立即返回
 * 未覆盖：第 2 轮重试 / RETRY_HINT 拼装 / 兜底 return / attempts.size() >= 2
 */
@SuppressWarnings("JavadocBlankLines")
@DisplayName("ReAct 自愈 · 模板层")
class ReActNl2SqlServiceImplTest
{
    @Test
    @DisplayName("RETRY_HINT 占位符必须正好两个")
    void retryHintMustKeepExactlyTwoPlaceholders()
    {
        // split 带 -1 是为了保留尾部空串：两个 %s 会把字符串切成 3 段
        int placeholders = ReActNl2SqlServiceImpl.RETRY_HINT.split("%s", -1).length - 1;

        assertEquals(2, placeholders,
                "RETRY_HINT 必须正好两个 %s（上一版 SQL、数据库报错）。"
                        + "改成三个时，调用点 String.format(RETRY_HINT, sql, error) 会在运行期抛 "
                        + "MissingFormatArgumentException，而这个异常只在真需要重试时才出现 —— "
                        + "平时测不到。实际占位符个数：" + placeholders);
    }

    @Test
    @DisplayName("两个参数能正确填进模板")
    void retryHintShouldFormatTwoArgs()
    {
        String sql = "select bad_col from tb_dish";
        String error = "Unknown column 'bad_col' in 'field list'";

        String msg = String.format(ReActNl2SqlServiceImpl.RETRY_HINT, sql, error);

        assertTrue(msg.contains(sql), "填进去的 SQL 应该原样出现在提示里，实际：" + msg);
        assertTrue(msg.contains(error), "填进去的报错应该原样出现在提示里，实际：" + msg);
    }

    @Test
    @DisplayName("报错必须原样保留，不能只留一句「执行失败」")
    void retryHintMustCarryRawDatabaseError()
    {
        // 这条守的是一个设计约束：模型要靠原始报错判断错在哪一列/哪张表。
        // 如果把报错换成「执行失败」这种概括，模型只能瞎猜，重试就失去意义。
        String rawError = "Table 'tb_dishs' doesn't exist";

        String msg = String.format(ReActNl2SqlServiceImpl.RETRY_HINT, "select * from tb_dishs", rawError);

        assertTrue(msg.contains("doesn't exist"), "数据库原始报错必须完整带过去，实际：" + msg);
    }
}
