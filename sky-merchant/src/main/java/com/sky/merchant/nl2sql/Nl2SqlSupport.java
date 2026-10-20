package com.sky.merchant.nl2sql;

import com.sky.merchant.constant.Nl2SqlLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * NL2SQL 两个实现（线性版 / 自愈版）共用的两段代码。
 *
 * 为什么抽出来：剥围栏和执行结果映射跟"要不要重试"完全无关，
 * 两边各抄一份的话，将来改一处忘另一处，就会出现"线性版能跑、自愈版报奇怪的错"这种问题。
 *
 * 这个类刻意保持无状态、无 Spring 依赖 —— 静态方法，谁都能调。
 */
@SuppressWarnings("JavadocBlankLines")
public final class Nl2SqlSupport
{
    private static final Logger log = LoggerFactory.getLogger(Nl2SqlSupport.class);

    private Nl2SqlSupport() { }

    /**
     * 剥掉模型可能加上的 markdown 代码围栏。
     *
     * 模型很爱输出 ```sql ... ``` 或 ``` ... ```，
     * 不断掉的话 Druid 解析会失败（解析器不认识反引号围栏）。
     *
     * @param raw 模型的原始输出，可能为 null
     * @return 纯 SQL 文本；入参为 null 时返回空串
     */
    public static String stripCodeFence(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String s = raw.trim();
        if (!s.startsWith("```"))
        {
            return stripLangTag(s);
        }
        int start = 3;
        int end = s.lastIndexOf("```");
        String body = (end <= start)
                ? s.substring(start)            // 围栏没闭合：截断，从 start 到末尾
                : s.substring(start, end);      // 正常：取中间
        return stripLangTag(body.trim());
    }

    /**
     * 去掉围栏后面紧跟的语言标识，例如 ```sql 里的 sql，以及 SQL: 这种前缀。
     */
    private static String stripLangTag(String body)
    {
        String t = body.stripLeading();
        if (t.regionMatches(true, 0, "sql", 0, 3))
        {
            t = t.substring(3).stripLeading();
            if (t.startsWith(":"))
            {
                t = t.substring(1).stripLeading();
            }
            return t.trim();
        }
        return body;
    }

    /**
     * 执行一条已通过校验的 SQL，并把 ResultSet 映射成 Nl2SqlResult。
     *
     * 注意传进来的必须是**校验器重写后**的 SQL（补过 LIMIT、补过数据范围），
     * 不是模型原始 SQL。
     *
     * 这个方法只管"执行"这一件事：成功返回带数据的结果，失败返回带 error 的结果，
     * **不抛异常**。因为调用方（尤其是自愈循环）要把 error 当数据用 —— 拿去喂回模型。
     * 如果把异常抛出去，循环里就得写 try-catch，还会分不清"SQL 错了"和"系统挂了"。
     *
     * @param jdbcTemplate 专用 JdbcTemplate（已设 queryTimeout 与 maxRows）
     * @param sql          校验器重写后的 SQL
     * @return 成功时带 columns/rows/rowCount/truncated；失败时带 error
     */
    public static Nl2SqlResult execute(JdbcTemplate jdbcTemplate, String sql)
    {
        try
        {
            return jdbcTemplate.query(sql, rs ->
            {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++)
                {
                    columns.add(meta.getColumnLabel(i));   // 用 label 拿别名，不是 name
                }

                List<List<String>> rows = new ArrayList<>();
                while (rs.next())
                {
                    List<String> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++)
                    {
                        row.add(rs.getString(i));
                    }
                    rows.add(row);
                }

                boolean truncated = rows.size() >= Nl2SqlLimits.MAX_ROWS;
                return Nl2SqlResult.success(sql, columns, rows, rows.size(), truncated);
            });
        }
        catch (Exception e)
        {
            log.warn("NL2SQL 执行失败，sql = {}，原因 = {}", sql, e.getMessage());
            return Nl2SqlResult.fail("SQL 执行失败", e.getMessage()).withSql(sql);
        }
    }
}
