package com.sky.merchant.nl2sql.impl;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sky.merchant.constant.Nl2SqlLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.sky.merchant.nl2sql.INl2SqlService;
import com.sky.merchant.nl2sql.Nl2SqlResult;
import com.sky.merchant.nl2sql.Nl2SqlSupport;
import com.sky.merchant.nl2sql.SchemaMetadataProvider;
import com.sky.merchant.nl2sql.SqlValidator;
import com.sky.merchant.nl2sql.ValidationResult;

@Service
public class Nl2SqlServiceImpl implements INl2SqlService {

    private static final Logger log = LoggerFactory.getLogger(Nl2SqlServiceImpl.class);

    /**
     * system prompt 模板，%s 放 schema 文本。
     * 注意：这里只约束「行为」（输出格式、别编字段），
     * 不承担「安全」—— 安全由 SqlValidator 负责，见类注释里的立场说明。
     */
    private static final String  SYSTEM_TEMPLATE = """
            你是 MySQL 查询生成器。根据下面的表结构，把用户的中文问题翻译成一条 SELECT 语句。

            规则：
            1. 只输出一条 SQL，不要解释，不要用 markdown 代码块包裹
            2. 只能查询下面列出的表，不要编造表名或字段名
            3. 只 SELECT 业务真正需要的列，不要用 SELECT *
            4. 如果用户的问题无法用下面这些表回答（问了不存在的表或字段，
               或问题与菜品数据无关），就只输出四个大写字母：NO_QUERY
            5. 拿不准时宁可少写条件，也不要瞎猜字段

            表结构：
            %s
            """;

    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;
    private final SchemaMetadataProvider metadataProvider;
    private final SqlValidator sqlValidator;

    public Nl2SqlServiceImpl(ChatClient.Builder builder,
                             JdbcTemplate jdbcTemplate,
                             SchemaMetadataProvider metadataProvider,
                             SqlValidator sqlValidator)
    {
        // clone 是防御性的：ChatClient.Builder 是单例 bean，DishAgentController 也在用它。
        // 不 clone 的话，这里对 builder 的任何改动都会影响那边（Day8 踩过的坑）。
        // 这里不设 defaultOptions —— 直接用 application.yml 里配好的 model / temperature。
        this.chatClient = builder.clone().build();
        this.metadataProvider = metadataProvider;
        this.sqlValidator = sqlValidator;

        // 复制一个"只给 NL2SQL 用"的 JdbcTemplate，不污染全局那个
        JdbcTemplate own = new JdbcTemplate(Objects.requireNonNull(jdbcTemplate.getDataSource()));
        own.setQueryTimeout(Nl2SqlLimits.QUERY_TIMEOUT_SECONDS);  // 超过 5 秒直接掐断
        own.setMaxRows(Nl2SqlLimits.MAX_ROWS);                    // 最多只取 200 行
        this.jdbcTemplate = own;                                      // 使用复制的 JdbcTemplate
    }

    @Override
    public Nl2SqlResult query(String question, Long userId) {
        // 1. 取 schema 文本 → 拼进 system prompt
        String schema = metadataProvider.getSchemaText();
        String system = SYSTEM_TEMPLATE.formatted(schema);

        // 2. 调模型
        String raw = chatClient.prompt().system(system).user(question).call().content();
        if (raw == null){
            return Nl2SqlResult.fail("模型输出为空");
        }
        String sql = Nl2SqlSupport.stripCodeFence(raw);

        // 2.5 模型主动拒答：问题超出已有表结构能回答的范围（见 system prompt 规则 4）。
        //     这一步是「体验层」不是「安全层」—— 它靠模型自觉，可能被绕过；
        //     真正的安全边界仍然是下面的 AST 校验。
        //     忽略大小写匹配前缀（regionMatches），兼容模型多写的分号或句号。
        if (sql.regionMatches(true, 0, "NO_QUERY", 0, 8)) {
            return Nl2SqlResult.fail("这个问题超出了可查询范围").withSql(sql);
        }

        // 3. 校验（直接调已完成的那套）
        ValidationResult vr = sqlValidator.validate(sql, userId);
        if (!vr.isPassed()){
            // 带上模型写的 SQL —— 排查「为什么被拦」时全靠它
            return Nl2SqlResult.fail(vr.getReason(), vr.getMessage()).withSql(sql);
        }
        // 4. 执行 —— 用 vr.getRewrittenSql()（校验器重写后的），不是模型原始 SQL
        String finalSql = vr.getRewrittenSql();
        log.info("NL2SQL 生成 SQL：{}", finalSql);
        return Nl2SqlSupport.execute(jdbcTemplate, finalSql);
    }
}
