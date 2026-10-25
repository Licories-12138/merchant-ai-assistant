package com.sky.merchant.constant;

/**
 * sky-merchant 模块的 Redis key 常量。
 * 为什么要收口到一个类：同一个 key 会被 Service / Agent 工具 / Controller 几处引用，
 * 分散硬编码时任何一处打错一个字都不会报错 ——
 * 表现是缓存各写各的：写进去了删不掉，删掉了还能读到。
 *
 * @author ruoyi
 */
public class RedisKeys
{
    /** 菜品详情缓存前缀，后面直接拼菜品 id。例：merchant:dish:detail:80 */
    public static final String DISH_DETAIL_PREFIX = "merchant:dish:detail:";

    /** 在售菜品列表（全量、不带分页），Agent 和客户端用 */
    public static final String DISH_ONSALE_KEY = "merchant:dish:onsale:list";

    /** 热搜菜名 ZSet：member 是菜名，score 是被问次数。不设过期，长期累积 */
    public static final String DISH_HOT_KEY = "merchant:dish:hot";

    /** 菜品对象与在售列表的 TTL（秒）：30 分钟 */
    public static final int DISH_TTL_SECONDS = 30 * 60;

    /** 详情缓存 TTL 的随机偏移上限（秒）：5 分钟。实际 TTL = DISH_TTL_SECONDS + random(0, 300) */
    public static final int DISH_TTL_JITTER_SECONDS = 5 * 60;

    /** 逻辑过期时间的随机偏移上限（秒）：5 分钟。让批量写入的 key 在 30~35 分钟内陆续过期 */
    public static final int DISH_LOGICAL_TTL_JITTER_SECONDS = 5 * 60;

    /** 问答答案缓存前缀，后面拼 md5(问题)。例：merchant:agent:chat:3fbca053... */
    public static final String CHAT_CACHE_PREFIX = "merchant:agent:chat:";

    /** 问答答案缓存的 TTL（秒）：30 分钟 */
    public static final int CHAT_CACHE_TTL_SECONDS = 30 * 60;

    /** 空值标记的 TTL（秒）：1 分钟。故意比正常缓存短得多 —— 见下方说明 */
    public static final int DISH_EMPTY_TTL_SECONDS = 60;

    /** 菜品 id 的布隆过滤器位图 */
    public static final String DISH_BLOOM_KEY = "merchant:dish:bloom";

    /** 菜品详情重建锁前缀 */
    public static final String DISH_LOCK_PREFIX = "merchant:dish:lock:";

    /** 重建锁的过期时间（秒）：必须明显大于一次查库的耗时，否则锁会提前失效 */
    public static final int DISH_LOCK_TTL_SECONDS = 10;

    /** 逻辑过期版详情缓存的重建锁的过期时间（秒）：必须明显大于一次查库的耗时，否则锁会提前失效 */
    public static final int DISH_LOGICAL_LOCK_TTL_SECONDS = 10;

    /**
     * 抢重建锁最多等多久（毫秒）。交给 Redisson 的 tryLock，必须 ≤ 锁 TTL×1000。
     * （改 Redisson 之前这里是"轮询预算"，配套还有个 50ms 的轮询间隔常量；
     *   tryLock 改用 pub/sub 唤醒后不再轮询，那个间隔常量已删除。）
     */
    public static final long DISH_LOCK_WAIT_MILLIS = 2000L;

    /**
     * 逻辑过期版详情缓存前缀。例：merchant:dish:detail:logical:80
     * 刻意拼在 DISH_DETAIL_PREFIX 之内 —— clearCache 按 DISH_DETAIL_PREFIX + "*" 扫描，
     * 这样它天然会被清掉；若另起一个 merchant:dish:logical: 前缀，这个 key 就永远清不掉了。
     */
    public static final String DISH_LOGICAL_PREFIX = DISH_DETAIL_PREFIX + "logical:";

    /**
     * 逻辑过期路径的重建锁前缀。例：merchant:dish:lock:logical:80
     * 为什么不和 Step 3 共用同一把锁：两条路径的产物不同（一条写裸 Dish，一条写 RedisData），
     * 共锁会让 Step 3 的等待者白等 2 秒后抛异常 —— 各锁各的，互不干扰。
     */
    public static final String DISH_LOGICAL_LOCK_PREFIX = DISH_LOCK_PREFIX + "logical:";

    /** 逻辑过期时长（秒）：30 分钟。到点后不删 key，由业务判断过期并异步重建 */
    public static final int DISH_LOGICAL_EXPIRE_SECONDS = 30 * 60;

    /**
     * 逻辑过期缓存的物理 TTL（秒）：24 小时。
     * 只作兜底 —— 正常情况下靠逻辑过期时间控制；万一重建一直失败，
     * 也不至于让一条脏数据永久驻留。
     */
    public static final int DISH_PHYSICAL_TTL_SECONDS = 24 * 60 * 60;

    /**
     * 扣库存的 Redisson 分布式锁 key 前缀，后面拼菜品 id。
     * 例：merchant:dish:stock:lock:80
     * 为什么不复用 DISH_LOCK_PREFIX：两把锁保护的临界区不同、粒度也不同 ——
     * DISH_LOCK_PREFIX 保护"重建缓存"（毫秒级，10 秒 TTL 够），
     * 这把保护"扣库存"（业务级，需要看门狗续期）。混用会互相干扰。
     */
    public static final String DISH_STOCK_LOCK_PREFIX = "merchant:dish:stock:lock:";

    /**
     * 【Day12】手写 SET NX PX 锁 demo 的 key 前缀，后面拼业务标识。
     * 例：merchant:lock:demo:A
     * 单独起前缀而不是复用 DISH_LOCK_PREFIX：这是演示用的锁，
     * 和生产路径的缓存重建锁混在一起会干扰排查。
     */
    public static final String LOCK_DEMO_PREFIX = "merchant:lock:demo:";

    /**
     * 【Day13】NL2SQL 元数据（白名单表的 schema 文本）的缓存 key。
     * 为什么不加表名：本期白名单只有 3 张表，合并成一段文本喂给模型反而更好 ——
     * 分 key 存会让每次请求要读 3 次 Redis、还要在应用层拼装。
     * 表数涨到几十张时必须改成按表分 key + 召回（见 Day13 文档「5.3 表多了怎么办」）。
     */
    public static final String NL2SQL_SCHEMA_KEY = "merchant:nl2sql:schema";

    /** NL2SQL schema 文本的 TTL（秒）：30 分钟。DDL 变更后靠等它过期或手动删 */
    public static final int NL2SQL_SCHEMA_TTL_SECONDS = 30 * 60;

    /** 多轮会话记忆前缀 */
    public static final String CHAT_MEMORY_PREFIX = "merchant:chat:memory:";

    /** 会话记忆 TTL（秒）：30 分钟，每次写入续期 = 滑动过期 */
    public static final int CHAT_MEMORY_TTL_SECONDS = 30 * 60;

    /** 会话索引：member = sessionId，score = 最后活跃时间戳（毫秒） */
    public static final String CHAT_MEMORY_INDEX = "merchant:chat:memory:index";

    /**
     * 会话上下文前缀，后面直接拼 sessionId。
     * 例：merchant:chat:ctx:s1
     *
     * 和 CHAT_MEMORY_PREFIX 是两个东西，不要合并：
     * 记忆存的是「说过的话」（USER 问句 + ASSISTANT 最终答案两条文本），
     * 上下文存的是「查到的数据」（菜名 / 价格 / 状态 / 简介这些结构化字段）。
     * 工具轨迹进不了记忆（advisor 只搬两条），所以「上一轮查到过哪些菜」
     * 这个事实只能由这一层承载。混进记忆会让每次请求多带一坨无关数据，
     * 而且模型会把「执行过程」当成「对话内容」，容易答非所问。
     */
    public static final String CHAT_CONTEXT_PREFIX = "merchant:chat:ctx:";

    /**
     * 会话上下文 TTL（秒）：30 分钟，和 CHAT_MEMORY_TTL_SECONDS 取同一个值。
     * 为什么必须一致：如果上下文活得比记忆久，就会出现「模型不记得聊过什么、
     * 却配着一堆上一轮的数据」的错位状态，比两边都没有更难解释。
     *
     * 续期时机（踩过坑）：只在 putAll 里续是不够的 —— putAll 只有查库时才调用，
     * 而一个会话完全可能连续追问七八轮都不查库，那样 ctx 会先于 memory 过期。
     * 所以 doRender 读到非空内容时也续一次，两者才同生共死。
     */
    public static final int CHAT_CONTEXT_TTL_SECONDS = 30 * 60;

    /**
     * 会话上下文里「一条事实多久之后算旧」（秒）：5 分钟。
     *
     * 和 CHAT_CONTEXT_TTL_SECONDS 是两个概念，最容易混的一个点：
     * TTL 管的是「这条数据还能存在多久」，它管的是「这条数据还能不能被直接引用」。
     * 价格和库存随时会改，所以 30 分钟内不删它（「用户在说哪道菜」这种指代判断还要靠它），
     * 但超过 5 分钟就不该被当成答案的数值来源了。
     *
     * 为什么过期不直接删：删了模型会从对话记忆里捡回上一轮 assistant 说过的旧价格，
     * 反而更难发现。所以是渲染时打 [STALE] 标记，并把「能不能引用」写成提示词里的分档规则：
     * 没标 [STALE] 的可信期内可以直接引用，标了的一律先重查再答。
     */
    public static final int CHAT_CONTEXT_FACT_STALE_SECONDS = 5 * 60;

    private RedisKeys()
    {
    }
}
