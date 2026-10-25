package com.sky.merchant.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sky.merchant.constant.RedisKeys;
import com.sky.merchant.domain.Dish;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 会话级「已经查到过的菜品事实」存储。
 *
 * 先说清它解决什么问题，否则很容易被误解成又一个缓存：
 * 对话记忆 ChatMemory 只搬两条消息 —— before 搬用户问句、after 搬最终答案。
 * 工具调用（assistant 带 toolCalls）和工具返回（tool 消息）在 advisor
 * 内部就被摘掉了，根本到不了 ChatMemoryRepository。
 * 结果就是：上一轮查到的是「宫保鸡丁 28.00 元」，下一轮用户问「哪个便宜」时，
 * 模型手上只剩一句「宫保鸡丁的售价：28.00元」，另一道菜多少钱已经没了。
 * 这一层把查询结果单独存一份，补上的就是记忆记不住的那部分事实。
 *
 * 存的是结构化字段，不是一句人话。原因：
 * 「哪个便宜」要能横向比较 → 必须有独立的 price 数值，不能靠模型读懂上一轮的句子；
 * 「28.00 是从哪查出来的」要可追溯 → 必须有 sourceQuery；
 * 「这条数据过期了没有」要能判断 → 必须有 fetchedAt / staleAfter。
 * 人话只是渲染结果，不是存储格式。
 *
 * 为什么用 Hash 不用 String 存整个 List：
 * 1) 一道菜一个 field，重复查同一道菜是按 dishId 覆盖（upsert），天然去重；
 * 2) String 存整个 List 时，并发写会互相覆盖，而且每次都要全量读改写。
 *
 * 为什么 field 用菜品 id 不用菜名：
 * 「宫保鸡丁」和「宫保鸡丁 」是两个不同字符串，会变成两个 field；
 * 模糊查询一次命中多道菜时，只有 id 能各存各的而不互相顶掉。
 *
 * 和 memory 的边界（重要）：
 * memory  = 存「说过什么」（USER / ASSISTANT 对话文本）
 * chat ctx = 存「查过什么数据」（本条事实的结构化字段）
 * prompt  = system + 事实块 + memory 对话 + 本轮 user
 * 不要把工具轨迹（ASSISTANT(toolCalls) / TOOL）塞进 memory：
 * 工具调用是过程，事实是结果；把过程混进记忆层会让记忆越来越重、越来越难判冲突。
 *
 * 它不替代查库：
 * 上下文只是兜底补全 —— 用户明确点到某道菜时仍然要查库拿最新值，
 * 只有「用户没说菜名、要跟上一轮比」这种情形才用这里的数据。
 * 这条边界写在 render 输出的规则里，由模型按提示执行。
 */
@SuppressWarnings("JavadocBlankLines")
@Component
@Slf4j
public class ChatContextStore
{
    /**
     * ToolContext 里放会话 id 的键名。
     * Controller 往里塞、工具从里取，两边必须用同一个字面量 ——
     * 所以收口成常量，而不是各写各的 "sessionId"（打错一个字母不会报错，
     * 表现是「跨轮复用悄悄失效」，最难查）。
     */
    public static final String SESSION_ID_KEY = "sessionId";

    /**
     * 单会话最多存多少条菜品。超了按 fetchedAt 淘汰最老的。
     * 这个数字管的是「存」；每轮注入多少条由 MAX_RENDER 管，两个数字分开 ——
     * 存多了不花 token，注入多了才花。
     */
    private static final int MAX_ENTRIES = 20;

    /**
     * 每轮最多注入多少条。超了按「与当前问题相关优先，其次最近查询优先」截断。
     * 会话聊到二十轮时，不做截断的话每轮都要带二十条历史事实，
     * 而用户这句真正用得上的往往只有两条。
     */
    private static final int MAX_RENDER = 8;

    /** 状态值按字典 dish_status：1 = 在售（启用），0 = 停售。 */
    private static final long STATUS_ON_SALE = 1L;

    private final StringRedisTemplate redisTemplate;

    public ChatContextStore(StringRedisTemplate redisTemplate)
    {
        this.redisTemplate = redisTemplate;
        // 装配日志：这一层静默失效时的表现是「跨轮复用不生效」，
        // 从接口返回上完全看不出来（模型会自己编一个像样的答案），
        // 所以开机就打一行确认它真的被装上了 —— 和会话记忆仓储同一个道理。
        log.info("会话上下文存储已装配：{}，key 前缀 {}，存活 TTL {} 秒，单条可信期 {} 秒，存 {} 条 / 注入 {} 条",
                getClass().getSimpleName(),
                RedisKeys.CHAT_CONTEXT_PREFIX,
                RedisKeys.CHAT_CONTEXT_TTL_SECONDS,
                RedisKeys.CHAT_CONTEXT_FACT_STALE_SECONDS,
                MAX_ENTRIES,
                MAX_RENDER);
    }

    /**
     * 写入一批菜品事实。DishTools 查完库之后调用。
     *
     * @param sessionId   会话 id，为空时直接跳过
     * @param dishes      刚查到的菜品，null 或空时直接跳过
     * @param sourceQuery 这次查询用的菜名关键词，存下来便于回答「这个价格是从哪查的」
     *
     * dishes 为 null 或空时直接返回，不清空已有记录：
     * 「这次没查到」不代表「之前查到的作废了」，写空会把上一轮的菜抹掉。
     *
     * 整个方法吞掉所有异常，只降级不重试：
     * 上下文是增强能力，不是主链路。Redis 抖一下不能让「已经查到的库结果」跟着丢掉，
     * 否则用户问一句菜价会变成 500。也不重试 —— 一次抖动重试三次等于把延迟放大三倍，
     * 而丢一条事实的代价只是下一轮多查一次库。
     */
    public void putAll(String sessionId, List<Dish> dishes, String sourceQuery)
    {
        if (!StringUtils.hasText(sessionId) || dishes == null || dishes.isEmpty())
        {
            return;
        }
        try
        {
            doPutAll(sessionId, dishes, sourceQuery);
        }
        catch (Exception e)
        {
            log.warn("会话上下文写入失败，本轮降级为不记上下文：session={}，原因={}",
                    sessionId, e.toString());
        }
    }

    private void doPutAll(String sessionId, List<Dish> dishes, String sourceQuery)
    {
        String key = key(sessionId);
        long now = System.currentTimeMillis();
        // 可信期到点的时间戳，存下来是为了渲染时不用再算一遍、
        // 也不会因为读的时候跟写的时候用的是不同配置而错位
        long staleAfter = now + RedisKeys.CHAT_CONTEXT_FACT_STALE_SECONDS * 1000L;

        int written = 0;
        for (Dish dish : dishes)
        {
            if (dish == null)
            {
                continue;
            }
            String field = field(dish);
            if (field == null)
            {
                continue;
            }
            JSONObject obj = new JSONObject();
            obj.put("name", dish.getName());
            // price 存字符串不存 BigDecimal 对象：
            // toPlainString 不丢精度，也避免 JSON 数值化后 28.00 变成 28，
            // 展示时想保留两位小数就只能从字符串来。
            obj.put("price", dish.getPrice() == null ? null : dish.getPrice().toPlainString());
            obj.put("status", dish.getStatus());
            obj.put("desc", dish.getDescription());
            obj.put("sourceQuery", sourceQuery);
            obj.put("fetchedAt", now);
            obj.put("staleAfter", staleAfter);
            // 同一个 field 再写一次就是 upsert：价格、状态以最新的 fetchedAt 为准，
            // 不需要额外的 version 字段 —— 单会话单写者，fetchedAt 本身就是版本
            redisTemplate.opsForHash().put(key, field, obj.toJSONString());
            written++;
        }
        if (written == 0)
        {
            return;
        }
        // 每次写完都刷 TTL = 滑动过期，和会话记忆的行为保持一致
        redisTemplate.expire(key, Duration.ofSeconds(RedisKeys.CHAT_CONTEXT_TTL_SECONDS));
        evictIfNeeded(key);
    }

    /**
     * 把本会话查到过的事实渲染成一段可以拼进 system prompt 的文本。
     * 没有任何记录时返回空串，调用方用 isBlank 判断一下即可。
     *
     * @param currentMessage 本轮用户原话。只用来做相关性排序 ——
     *                       这句话里点到的菜排前面，用户问「宫保鸡丁呢」时
     *                       不会被七条无关事实挤掉。
     *
     * 渲染同样整段吞异常：拼 prompt 时 Redis 挂了，宁可这轮不带上下文，
     * 也不能让整个问答失败。
     */
    public String render(String sessionId, String currentMessage)
    {
        if (!StringUtils.hasText(sessionId))
        {
            return "";
        }
        try
        {
            return doRender(sessionId, currentMessage);
        }
        catch (Exception e)
        {
            log.warn("会话上下文读取失败，本轮降级为不带上下文：session={}，原因={}",
                    sessionId, e.toString());
            return "";
        }
    }

    private String doRender(String sessionId, String currentMessage)
    {
        String key = key(sessionId);
        Map<String, String> entries = redisTemplate.<String, String>opsForHash().entries(key(sessionId));
        if (entries == null || entries.isEmpty())
        {
            return "";
        }
        // 读到就续期，和 memory 对齐。
        // ctx 只在查库时才被写，而这个会话完全可能连续追问七八轮都不查库 ——
        // 不续的话 ctx 会先于 memory 过期，出现「还记得聊过什么、数据却没了」的错位，
        // 比两边都过期更难查。memory 每轮 add 必续，ctx 每轮 render 也续，才同生共死。
        redisTemplate.expire(key, Duration.ofSeconds(RedisKeys.CHAT_CONTEXT_TTL_SECONDS));
        List<JSONObject> facts = new ArrayList<>();
        for (String raw : entries.values())
        {
            JSONObject obj = parse(raw);
            if (obj != null)
            {
                facts.add(obj);
            }
        }
        if (facts.isEmpty())
        {
            return "";
        }

        long now = System.currentTimeMillis();
        // 「最近查询」要在截断之前算，不然可能被相关性排序挤出去
        String latestName = facts.stream()
                .max(Comparator.comparingLong(f -> f.getLongValue("fetchedAt")))
                .map(f -> f.getString("name"))
                .orElse(null);

        // 排序：本轮点到的菜优先，其次越新越优先
        Comparator<JSONObject> order = Comparator
                .comparingInt((JSONObject fact) -> relevance(fact, currentMessage))
                .reversed()
                .thenComparing(Comparator
                        .comparingLong((JSONObject fact) -> fact.getLongValue("fetchedAt"))
                        .reversed());
        facts.sort(order);
        if (facts.size() > MAX_RENDER)
        {
            facts = new ArrayList<>(facts.subList(0, MAX_RENDER));
        }
        // 截断之后再按时间升序，读起来就是对话发生的先后顺序
        facts.sort(Comparator.comparingLong(fact -> fact.getLongValue("fetchedAt")));

        StringBuilder sb = new StringBuilder();
        sb.append("【本会话此前查到的菜品事实】\n");
        sb.append("来源：本会话中真实查库的结果。用途：补全用户追问里省略的菜名、做价格对比。\n");
        for (JSONObject fact : facts)
        {
            String line = toLine(fact, now);
            if (line != null)
            {
                sb.append("- ").append(line).append('\n');
            }
        }
        if (StringUtils.hasText(latestName))
        {
            sb.append("最近一次查询的菜品：").append(latestName).append('\n');
        }
        sb.append("规则：\n");
        sb.append("1) 每条事实后面括号里写的是查询距今多久；标了 [STALE] 表示已超出可信期。\n");
        sb.append("2) 只要状态是停售，回答里必须同时说明「当前停售」，不许只报价格或辣度。\n");
        sb.append("3) 上面没出现的菜品一律不许推测，也不许用常识补充。\n");
        return sb.toString();
    }

    /**
     * 一条事实转成人话。字段缺失时宁可写「未记录」，也不许省略 ——
     * 省略会让模型以为「这道菜没有这个属性」，写出来它才知道是数据缺失。
     */
    private String toLine(JSONObject fact, long now)
    {
        String name = fact.getString("name");
        if (!StringUtils.hasText(name))
        {
            return null;
        }
        long fetchedAt = fact.getLongValue("fetchedAt");
        StringBuilder sb = new StringBuilder();
        if (isStale(fact, now))
        {
            sb.append("[STALE] ");
        }
        sb.append(name).append("：售价 ");
        String price = fact.getString("price");
        sb.append(price == null ? "未记录" : price + " 元");
        Long status = fact.getLong("status");
        sb.append("，状态").append(status != null && status == STATUS_ON_SALE ? "在售" : "停售");
        String desc = fact.getString("desc");
        if (StringUtils.hasText(desc))
        {
            sb.append("，简介：").append(desc);
        }
        sb.append("（").append(ageText(now - fetchedAt));
        String sourceQuery = fact.getString("sourceQuery");
        if (StringUtils.hasText(sourceQuery))
        {
            sb.append("查「").append(sourceQuery).append("」");
        }
        sb.append("）");
        return sb.toString();
    }

    /**
     * 这条事实的数值现在还能不能直接引用。
     * staleAfter 缺失（旧格式数据）时按 fetchedAt + 当前配置现算，
     * 保证配置改了之后旧数据也按新口径判，而不是永远按写进去时那一份。
     */
    private boolean isStale(JSONObject fact, long now)
    {
        long staleAfter = fact.getLongValue("staleAfter");
        if (staleAfter <= 0L)
        {
            staleAfter = fact.getLongValue("fetchedAt")
                    + RedisKeys.CHAT_CONTEXT_FACT_STALE_SECONDS * 1000L;
        }
        return now > staleAfter;
    }

    /** 本轮原话里点到了这道菜就算相关。没有任何相关时全部退化成按时间排序。 */
    private int relevance(JSONObject fact, String currentMessage)
    {
        if (!StringUtils.hasText(currentMessage))
        {
            return 0;
        }
        String name = fact.getString("name");
        return StringUtils.hasText(name) && currentMessage.contains(name) ? 1 : 0;
    }

    /**
     * 超过上限就按 fetchedAt 淘汰最老的几条。
     *
     * 这里的读-改-写不是原子的：同一会话并发两次请求可能各淘汰各的。
     * 后果只是多删一条或少删一条，语义不坏，因此不值得为它加锁 ——
     * 加锁会把「写几条缓存」变成「抢一把分布式锁」，代价远大于收益。
     */
    private void evictIfNeeded(String key)
    {
        Map<String, String> entries = redisTemplate.<String, String>opsForHash().entries(key);
        if (entries == null || entries.size() <= MAX_ENTRIES)
        {
            return;
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong(entry -> fetchedAtOf(entry.getValue())));
        int removeCount = sorted.size() - MAX_ENTRIES;
        for (int i = 0; i < removeCount; i++)
        {
            redisTemplate.opsForHash().delete(key, sorted.get(i).getKey());
        }
    }

    /** 取不到 fetchedAt 时当成 0，这样脏数据会排在最前面被优先淘汰。 */
    private long fetchedAtOf(String raw)
    {
        JSONObject obj = parse(raw);
        return obj == null ? 0L : obj.getLongValue("fetchedAt");
    }

    /** 单条解析失败只丢这一条，不能让整批渲染挂掉。 */
    private JSONObject parse(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            return null;
        }
        try
        {
            return JSON.parseObject(raw);
        }
        catch (Exception e)
        {
            log.warn("会话上下文单条解析失败，已跳过：{}", raw);
            return null;
        }
    }

    /**
     * Hash field。优先用菜品 id；id 为空说明是脏数据或手工构造的对象，
     * 退回菜名保证还能记下来，前缀加 name: 是为了不和 id: 撞车。
     */
    private String field(Dish dish)
    {
        if (dish.getId() != null)
        {
            return "id:" + dish.getId();
        }
        return StringUtils.hasText(dish.getName()) ? "name:" + dish.getName().trim() : null;
    }

    private String key(String sessionId)
    {
        return RedisKeys.CHAT_CONTEXT_PREFIX + sessionId;
    }

    private String ageText(long millis)
    {
        // 秒级不显示「刚刚」：可信期是按秒配的（10 秒试验、300 秒正式），
        // 「刚刚查的」和「[STALE] 已过期」并排会让模型读着自相矛盾
        if (millis < 60_000L)
        {
            return Math.max(1L, millis / 1000L) + " 秒前";
        }
        long minutes = millis / 60_000L;
        if (minutes < 60)
        {
            return minutes + " 分钟前";
        }
        return (minutes / 60) + " 小时前";
    }
}
