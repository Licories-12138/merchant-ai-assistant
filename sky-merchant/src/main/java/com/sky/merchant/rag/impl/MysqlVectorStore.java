package com.sky.merchant.rag.impl;

import com.sky.merchant.constant.RagLimits;
import com.sky.merchant.rag.RagChunk;
import com.sky.merchant.rag.VectorStore;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * VectorStore 的 MySQL 实现。
 *
 * 一句话职责：把 Java 对象和数据库行互相翻译，不掺任何检索逻辑。
 * 检索算法（向量路 + 关键词路 + RRF + 折算父块）在 IRagService 的实现里 ——
 * 换存储时只换本类，算法一行不动。
 *
 * 七条 SQL 全部以常量形式集中在类顶部，不在方法体里现拼。
 * 唯一例外是 loadParents：它的 IN 占位符个数随入参变化，只能动态生成。
 *
 * 两个容易写错的地方：
 *   1) saveParent 必须用 KeyHolder 拿主键，不能"插完再 select order by id desc limit 1" —— 并发下会拿错
 *   2) loadAllChildren 的 embedding 列是 JSON 文本，用 fromJson 解析；解析失败不许吞异常，
 *      否则库里存了坏数据也没人知道
 */
@SuppressWarnings("JavadocBlankLines")
@Service
public class MysqlVectorStore implements VectorStore
{
    /** 单条 INSERT：父块用。parent_id 显式写 NULL，避免被误填 */
    private static final String SQL_INSERT_PARENT = """
            INSERT INTO rag_chunk (doc_name, parent_id, level, seq, content, content_hash)
            VALUES (?, NULL, ?, ?, ?, ?)
            """;

    /** 批量 INSERT：子块用。注意 embedding 在倒数第二个问号 */
    private static final String SQL_INSERT_CHILD = """
            INSERT INTO rag_chunk (doc_name, parent_id, level, seq, content, embedding, content_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_SELECT_CHILDREN = """
            SELECT id, doc_name, parent_id, level, seq, content, embedding, content_hash
            FROM rag_chunk
            WHERE level = ?
            ORDER BY doc_name, seq
            """;

    private static final String SQL_SELECT_PARENTS = """
            SELECT id, doc_name, parent_id, level, seq, content, embedding, content_hash
            FROM rag_chunk
            WHERE id IN (%s)
            ORDER BY doc_name, seq
            """;

    private final JdbcTemplate jdbcTemplate;

    public MysqlVectorStore(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入父块并返回自增主键。
     *
     * 必须用 KeyHolder 拿回主键（new GeneratedKeyHolder() + jdbcTemplate.update(conn -> ps, keyHolder)），
     * 不能"插完再 select 一次 order by id desc limit 1"—— 并发下会拿错。
     */
    @Override
    public Long saveParent(RagChunk parent)
    {
        // 入口校验：这一层不校验的话，误传子块会插进一条 level=2 且 parent_id=NULL 的脏数据，
        // 它在检索时能被捞到、却折算不出父块 —— 表现是"结果莫名少一块"，极难定位。
        if (parent == null || parent.getLevel() == null
                || parent.getLevel() != RagLimits.LEVEL_PARENT)
        {
            throw new IllegalArgumentException("saveParent 只接受父块：level 必须等于 RagLimits.LEVEL_PARENT");
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            // 创建预编译的 SQL 语句。这不仅能防 SQL 注入，还能提高数据库执行效率
            PreparedStatement ps = con.prepareStatement(SQL_INSERT_PARENT,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, parent.getDocName());
            ps.setInt(2, parent.getLevel());
            // seq 与 saveChildren 保持一致：null 按 0 处理（seq 列本身是 NOT NULL DEFAULT 0）
            ps.setInt(3, parent.getSeq() == null ? 0 : parent.getSeq());
            ps.setString(4, parent.getContent());
            ps.setString(5, parent.getContentHash());
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalArgumentException("插入父块失败，未获取到自增主键");
        }
        return key.longValue();
    }

    /**
     * 批量插入子块。
     *
     * 用 jdbcTemplate.batchUpdate(sql, BatchPreparedStatementSetter)，不要循环单条 update。
     * 每个子块的 parentId 必须非空，写之前在这里断言一次 —— 空值插进去以后
     * 表现为"子块检索得到、但折算不出父块"，往上查会查到崩溃。
     */
    @Override
    public int saveChildren(List<RagChunk> children)
    {
        // 空列表是合法输入（例如某个父块切完没有产出），返回 0 —— 抛异常会让正常调用失败
        if (children == null || children.isEmpty())
        {
            return 0;
        }
        // 整批先校验一遍，有问题就在写库之前挡住 —— 不要插到一半才发现第 30 条 parentId 是空的，
        // 那会留下半份数据，还得人工清理
        for (int i = 0; i < children.size(); i++)
        {
            RagChunk child = children.get(i);
            if (child == null || child.getParentId() == null)
            {
                throw new IllegalArgumentException(
                        "第 " + i + " 个子块的 parentId 为空：必须先调 saveParent 拿到父块 id 再填进来");
            }
            if (child.getLevel() == null || child.getLevel() != RagLimits.LEVEL_CHILD)
            {
                throw new IllegalArgumentException(
                        "第 " + i + " 个块不是子块：level 必须等于 RagLimits.LEVEL_CHILD");
            }
        }

        int[] rows = jdbcTemplate.batchUpdate(SQL_INSERT_CHILD, new BatchPreparedStatementSetter()
        {
            @Override
            // 第 i 次填什么参数
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException
            {
                RagChunk child = children.get(i);
                ps.setString(1, child.getDocName()); // 文本
                ps.setLong(2, child.getParentId());  // 数字
                ps.setInt(3, child.getLevel());
                ps.setInt(4, child.getSeq() == null ? 0 : child.getSeq());
                ps.setString(5, child.getContent());
                // 这里必须是 JSON 字符串，不能直接把 float[] 交给 setString
                ps.setString(6, toJson(child.getEmbedding())); // 向量
                ps.setString(7, child.getContentHash());
            }
            @Override
            // 要做几次
            public int getBatchSize()
            {
                return children.size();
            }
        });
        // batchUpdate 对每个 setValues 调用回一个影响行数，所以长度 = 批大小。
        // 正常情况下每个元素是 1；个别驱动配置下可能是 SUCCESS_NO_INFO(-2)，
        // 所以直接返回语句条数，不要去累加影响行数。
        return rows.length;
    }

    /**
     * 删除某文档全部块。
     */
    @Override
    public int deleteDocument(String docName)
    {
        return jdbcTemplate.update("DELETE FROM rag_chunk WHERE doc_name = ?", docName);
    }

    /**
     * 检查某文档是否存在。
     */
    @Override
    public boolean exists(String docName)
    {
        // 1. 防御性编程：如果传入的名字是空的，直接返回false，不要查库
        if (docName == null || docName.isEmpty()) {
            return false;
        }
        // 2. 优化 SQL：用 SELECT 1 + LIMIT 1 替代 COUNT(1)
        String sql = "SELECT 1 FROM rag_chunk WHERE doc_name = ? LIMIT 1";
        // 3. 使用 queryForList，查不到数据时返回空列表，不会抛异常
        List<Integer> result = jdbcTemplate.queryForList(sql, Integer.class, docName);
        // 4. 列表不为空，说明存在
        return !result.isEmpty();
    }

    /**
     * 读全部子块。
     *
     * embedding 列是 JSON 文本（例如 [0.0123,-0.0456,...]），用下面的 fromJson() 解析。
     *    解析失败不要吞异常 —— 那意味着库里存了坏数据，必须让它在首次检索时就炸出来。
     * 用 jdbcTemplate.query(sql, rowMapper)，不要 queryForList() 拿 Map 再手动转 —— 字段一改就静默错位。
     */
    @Override
    public List<RagChunk> loadAllChildren()
    {
        // level 走参数绑定而不是写死在 SQL 里：写死 2 的话，将来改 RagLimits.LEVEL_CHILD 时
        // 这条 SQL 不会跟着变，会静默查错数据
        return jdbcTemplate.query(SQL_SELECT_CHILDREN, (rs, rowNum) -> mapRow(rs), RagLimits.LEVEL_CHILD);
    }

    /**
     * 按 id 批量取父块。
     *
     * 空集合必须直接 return List.of()，否则拼出 IN () 是非法 SQL，直接报语法错。
     * 占位符要按 size 动态生成（"?,?,?"），用 String.join(",", Collections.nCopies(n, "?"))。
     */
    @Override
    public List<RagChunk> loadParents(Collection<Long> parentIds)
    {
        if (parentIds == null || parentIds.isEmpty())
        {
            return List.of();  // ← 早返回，否则下面会拼出 IN () 语法错
        }
        // 创建占位符，例如 "?,?,?"
        String placeholders = String.join(",", Collections.nCopies(parentIds.size(), "?"));
        // 塞进 SQL 模板
        String sql = String.format(SQL_SELECT_PARENTS, placeholders);

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), parentIds.toArray());
    }

    /**
     * 将 ResultSet 映射到 RagChunk 对象
     */
    private RagChunk mapRow(ResultSet rs) throws SQLException
    {
        RagChunk chunk = new RagChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocName(rs.getString("doc_name"));
        // getLong() 遇到 NULL 会返回 0，不报错
        // 父块的 parent_id 是 NULL，就会变成 0L
        // 结果父块看起来"有个 id 为 0 的父亲"，很难排查
        chunk.setParentId(rs.getObject("parent_id", Long.class));   // ← 注意这行
        chunk.setLevel(rs.getInt("level"));
        chunk.setSeq(rs.getInt("seq"));
        chunk.setContent(rs.getString("content"));
        chunk.setEmbedding(fromJson(rs.getString("embedding")));
        chunk.setContentHash(rs.getString("content_hash"));
        return chunk;
    }

    /**
     * 清空表
     */
    @Override
    public void truncate()
    {
        jdbcTemplate.update("TRUNCATE TABLE rag_chunk");
    }

    // ============================================================
    // 向量 <-> JSON
    //
    // 为什么手写而不是用 Jackson：
    //   本项目用的是 Jackson 3（tools.jackson.core:jackson-databind），
    //   它的 ObjectMapper 包名是 tools.jackson.databind，跟 Jackson 2 的
    //   com.fasterxml.jackson.databind 不是同一个类型 —— Spring Boot 4 自动注册的是前者，
    //   按后者去注入就找不到 Bean。为了一个 "float 数组转方括号" 的需求去啃 Jackson 版本差异，不值得。
    //   而且这个格式简单到不需要库：只有数字、逗号、方括号。
    //
    // 为什么不用 Arrays.toString(float[])：
    //   它产出 [0.1, 0.2, 0.3]（逗号后带空格），能解析但格式不紧凑；
    //   更要命的是 float[].toString() 会产出 [F@1a2b3c 这种垃圾，而且不报错。
    // ============================================================

    /**
     * float[] -> [0.0123,-0.0456,...]
     *
     * 用 Float.toString 的默认输出（最短可往返表示），转回去不会丢精度。
     */
    private static String toJson(float[] vector)
    {
        if (vector == null || vector.length == 0)
        {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 12);
        sb.append('[');
        for (int i = 0; i < vector.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * [0.0123,-0.0456,...] -> float[]
     *
     * 空值返回 null（表示这一行没存向量）；"[]" 返回长度为 0 的数组，两者语义不同。
     */
    private static float[] fromJson(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        String body = json.trim();
        if (body.startsWith("["))
        {
            body = body.substring(1);
        }
        if (body.endsWith("]"))
        {
            body = body.substring(0, body.length() - 1);
        }
        body = body.trim();
        if (body.isEmpty())
        {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
