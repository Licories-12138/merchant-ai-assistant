package com.sky.merchant.nl2sql.impl;

import com.sky.merchant.constant.Nl2SqlLimits;
import com.sky.merchant.nl2sql.Attempt;
import com.sky.merchant.nl2sql.INl2SqlAgentService;
import com.sky.merchant.nl2sql.Nl2SqlResult;
import com.sky.merchant.nl2sql.Nl2SqlSupport;
import com.sky.merchant.nl2sql.SchemaMetadataProvider;
import com.sky.merchant.nl2sql.SqlValidator;
import com.sky.merchant.nl2sql.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 带自愈的 NL2SQL：把"一次生成"变成"生成 → 校验 → 执行 → 看报错 → 重写"的闭环。
 *
 * 与线性版（Nl2SqlServiceImpl）的差别只在失败路径：
 * 那边执行失败直接返回，这边把数据库原始报错喂回模型，让它自己改。
 *
 * ============================================================
 * 实现契约（按顺序做，别跳）
 * ============================================================
 *   1) 取 schema，拼进 system prompt
 *   2) round 从 1 循环到 MAX_RETRY + 1（共 MAX_RETRY+1 轮，第一轮不算重试）：
 *      a. 记下本轮开始时间（System.nanoTime）
 *      b. 拼 user 消息：
 *           第 1 轮就是 question 本身
 *           第 n 轮 = question + 前面每一轮失败说明（用 RETRY_HINT 逐条拼出来）
 *      c. 调模型 → Nl2SqlSupport.stripCodeFence 剥围栏
 *      d. 模型拒答（以 NO_QUERY 开头，忽略大小写）→ 记一轮失败 → 立即返回，不重试
 *      e. sqlValidator.validate(sql, userId)
 *           不通过 → 记一轮失败 → 【立即返回，不重试】
 *      f. Nl2SqlSupport.execute(jdbcTemplate, vr.getRewrittenSql())
 *           成功 → 记一轮成功 → 返回结果
 *           失败 → 记一轮失败 → 进入下一轮
 *   3) 循环走完仍没成功 → 返回最后一次的失败结果，attempts 全带上
 *
 * ============================================================
 * 三个容易写错的点
 * ============================================================
 *  ① 【e 步绝不重试】校验器拦的是越权表/非 SELECT/敏感列/SELECT *。
 *     允许重试就等于给模型 MAX_RETRY 次机会去试着绕过安全闸门 ——
 *     而它每次都可能换个写法再试一次。自愈是给"手滑"用的，不是给"越权"用的。
 *  ② 喂回模型的 SQL 必须是【模型自己写的那版】（剥过围栏的），
 *     不是 vr.getRewrittenSql()。校验器会补 LIMIT 和数据范围条件，
 *     把那版喂回去，模型会以为"要写这么复杂的东西"，下一轮越写越乱。
 *  ③ 每一轮都要往 attempts 里加一条，【包括最后失败的那一次】。
 *     少记一次，就说不清"到底试了几轮、最后卡在哪"。
 */
@SuppressWarnings("JavadocBlankLines")
@Service
public class ReActNl2SqlServiceImpl implements INl2SqlAgentService
{
    private static final Logger log = LoggerFactory.getLogger(ReActNl2SqlServiceImpl.class);

    /**
     * system prompt。和线性版基本一致，只多一句"你可以看到上一版的报错"。
     * 注意：报错不塞进 system —— system 是角色设定，塞进去会污染人设。
     * 报错要作为新的 user 消息追加，这样模型看到的是连续对话。
     */
    private static final String SYSTEM_TEMPLATE = """
            你是 MySQL 查询生成器。根据下面的表结构，把用户的中文问题翻译成一条 SELECT 语句。

            规则：
            1. 只输出一条 SQL，不要解释，不要用 markdown 代码块包裹
            2. 只能查询下面列出的表，不要编造表名或字段名
            3. 只 SELECT 业务真正需要的列，不要用 SELECT *
            4. 如果用户的问题无法用下面这些表回答（问了不存在的表或字段，
               或问题与菜品数据无关），就只输出四个大写字母：NO_QUERY
            5. 拿不准时宁可少写条件，也不要瞎猜字段
            6. 如果对话里给了你"上一次的 SQL 和执行报错"，只修正报错指向的地方，不要推翻重写

            表结构：
            %s
            """;

    /** 重试时追加给人的一段说明，%s 依次是「上一版 SQL」和「数据库原始报错」 */
    private static final String RETRY_HINT = """
            你上一次生成的 SQL 执行失败了，请修正后重新只输出一条 SQL。
            上一次的 SQL：
            %s
            数据库返回的错误：
            %s
            """;

    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;
    private final SchemaMetadataProvider metadataProvider;
    private final SqlValidator sqlValidator;

    public ReActNl2SqlServiceImpl(ChatClient.Builder builder,
                                  JdbcTemplate jdbcTemplate,
                                  SchemaMetadataProvider metadataProvider,
                                  SqlValidator sqlValidator)
    {
        // clone 是防御性的：ChatClient.Builder 是单例 bean，其他链路也在用
        this.chatClient = builder.clone().build();
        this.metadataProvider = metadataProvider;
        this.sqlValidator = sqlValidator;

        // 复制一个专用 JdbcTemplate，不污染全局那个（与线性版同样的处理）
        JdbcTemplate own = new JdbcTemplate(Objects.requireNonNull(jdbcTemplate.getDataSource()));
        own.setQueryTimeout(Nl2SqlLimits.QUERY_TIMEOUT_SECONDS);
        own.setMaxRows(Nl2SqlLimits.MAX_ROWS);
        this.jdbcTemplate = own;
    }

    @Override
    public Nl2SqlResult agentQuery(String question, Long userId)
    {
        // TODO 你来写：ReAct 自愈循环。契约见类注释，六个步骤 a~f。
        //      变量建议：List<Attempt> attempts = new ArrayList<>()；
        //               循环上限 Nl2SqlLimits.MAX_RETRY + 1
        //      返回前记得 .withAttempts(attempts)，成功和失败都要带。
        // 1. 取 schema，拼进 system prompt
        String systemPrompt = String.format(SYSTEM_TEMPLATE, metadataProvider.getSchemaText());
        List<Attempt> attempts = new ArrayList<>();

        // 2. round 从 1 循环到 MAX_RETRY + 1（共 MAX_RETRY+1 轮，第一轮不算重试）
        for (int round = 1; round <= Nl2SqlLimits.MAX_RETRY + 1; round++)
        {
            // a. 本轮起点，用于算 Attempt.elapsedMs
            long startTime = System.nanoTime();
            // b. user 消息 = 问题 + 之前每一轮的失败说明。
            //    第 1 轮 attempts 为空，循环不执行，这里自然退化成"只有问题"
            StringBuilder userMessage = new StringBuilder(question);
            for (Attempt past : attempts)
            {
                userMessage.append("\n\n")
                        .append(String.format(RETRY_HINT, past.getSql(), past.getError()));
            }
            // c. 先调模型拿原始输出，再剥围栏 —— 顺序不能反
            String raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage.toString())
                    .call()
                    .content();
            // 模型可能返回 null（网络异常、额度不足时
            if (raw == null)
            {
                attempts.add(Attempt.failed(round, null, "模型返回 null", elapsedMs(startTime)));
                return Nl2SqlResult.fail("模型输出为空").withAttempts(attempts);
            }
            String sql = Nl2SqlSupport.stripCodeFence(raw);
            // d. 模型拒答（以 NO_QUERY 开头，忽略大小写）→ 记一轮失败 → 立即返回，不重试
            if (sql.regionMatches(true, 0, "NO_QUERY", 0, 8))
            {
                attempts.add(Attempt.failed(round, sql, "NO_QUERY：问题超出可查询范围",
                        elapsedMs(startTime)));
                return Nl2SqlResult.fail("这个问题超出了可查询范围").withSql(sql).withAttempts(attempts);
            }
            // e. sqlValidator.validate(sql, userId)
            //      不通过 → 记一轮失败 → 【立即返回，不重试】
            ValidationResult vr = sqlValidator.validate(sql, userId);
            if (!vr.isPassed())
            {
                attempts.add(Attempt.failed(round, sql, vr.getReason() + "：" + vr.getMessage(),
                        elapsedMs(startTime)));
                return Nl2SqlResult.fail(vr.getReason(), vr.getMessage()).withSql(sql).withAttempts(attempts);
            }
            // f. Nl2SqlSupport.execute(jdbcTemplate, vr.getRewrittenSql())
            Nl2SqlResult executeResult = Nl2SqlSupport.execute(jdbcTemplate, vr.getRewrittenSql());
            long cost = elapsedMs(startTime);
            //      成功 → 记一轮成功 → 返回结果
            if (executeResult.getError() == null){
                attempts.add(Attempt.ok(round, sql, cost));
                log.info("NL2SQL 第 {} 轮成功，耗时 {}ms，sql={}", round, cost, sql);
                return executeResult.withAttempts(attempts);
            }
            //      失败 → 记一轮失败 → 进入下一轮
            attempts.add(Attempt.failed(round, sql, executeResult.getError(), cost));
            log.warn("NL2SQL 第 {} 轮执行失败，准备重试：{}", round, executeResult.getError());
            // 没有 return，继续下一轮
        }
        log.error("NL2SQL 自愈 {} 轮仍失败，question={}", attempts.size(), question);
        return Nl2SqlResult.fail("重试 " + Nl2SqlLimits.MAX_RETRY + " 次后仍未成功")
                .withAttempts(attempts);
    }

    /**
     * 从 startNanos 到现在经过了多少毫秒。
     *
     * 为什么用 nanoTime 而不是 currentTimeMillis：
     * nanoTime 是单调时钟，只会往前走；currentTimeMillis 会被 NTP 校时或用户手动改时间影响，
     * 那种情况下算出来的耗时可能是负数 —— 而负数出现在耗时字段里，排查时会把人带偏。
     *
     * 为什么除 1_000_000L：nanoTime 的单位是纳秒，1 毫秒 = 10^6 纳秒。
     * 用 long 字面量是为了避免 int 溢出（十进制字面量默认 int，10^6 装得下但写成 L 更明确）。
     */
    private static long elapsedMs(long startNanos)
    {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
