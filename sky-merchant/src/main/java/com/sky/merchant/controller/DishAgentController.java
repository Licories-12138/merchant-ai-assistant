package com.sky.merchant.controller;

import com.sky.merchant.agent.ChatContextStore;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.sky.common.core.redis.RedisCache;
import com.sky.merchant.domain.OrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.merchant.agent.DishTools;
import com.sky.merchant.constant.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 自然语言点单 Agent 入口。
 * 用法：GET /merchant/agent/chat?message=宫保鸡丁多少钱
 * 链路：用户自然语言 -> 模型理解意图 -> 调 DishTools 查真库 -> 模型组织成人话返回。
 */
@RestController
@RequestMapping("/merchant/agent")
@Slf4j
public class DishAgentController
{
    /**
     * 基础 system 提示词。
     * 为什么必须抽成常量：ChatClient 的 system(String) 是覆盖语义不是追加 ——
     * DefaultChatClient 里直接 this.systemText = text，请求级 .system(x)
     * 会把 Builder 的 defaultSystem 整段换掉。有上下文时我们要拼
     * 「基础规则 + 事实块」，如果这里留一份字面量、那边再写一份，
     * 改规则时必定漏掉一处，而漏掉的表现是「某条规则时灵时不灵」，极难查。
     */
    private static final String BASE_SYSTEM =
            "你是外卖商家系统的点单助手，只回答菜品相关的问题，严格按下面三步执行。"
                    + "第一步：决定这一轮查不查库，只有两种情况，二选一。"
                    + "查库档，出现下面任一情况就必须调用工具，查到之前不许给任何数字："
                    + "提示词里没有「本会话此前查到的菜品事实」；"
                    + "用户这句话说出了菜名；那条事实标了 [STALE]。"
                    + "复用档，下面三条同时满足才不查，直接用提示词里的事实回答："
                    + "提示词里有这道菜的事实；那条事实没标 [STALE]；用户没说出菜名、只是在追问它。"
                    + "对话历史不能当复用理由：历史里答过什么，都不等于本轮查过。"
                    + "第二步：只用本轮信源回答。查库了就用工具返回的内容，"
                    + "复用了就用提示词里的事实块；两者都没有的数据一律不要，也不要补充外部知识。"
                    + "第三步：按顺序判断怎么答，对上一条就停。"
                    + "1) 工具返回「没有找到」——直接说没有这道菜，不要用「未记录」代答。"
                    + "2) 用户问的属性在信源里确实没有——答「菜名+属性名：菜品资料中未记录」，"
                    + "例如「干锅牛蛙的辣度：菜品资料中未记录」；"
                    + "注意简介里的口味、辣度、做法、配料算信源内容，里面写了就照它答，"
                    + "不要因为不是独立字段就说没有。"
                    + "3) 其余情况——直接答用户问的那个属性，值取自信源。"
                    + "任何情况下都要遵守："
                    + "状态是停售，回答里必须带上「当前停售」，不许只报价格或辣度；"
                    + "不许拿别的属性或别的字段凑一个答案；"
                    + "没有调用工具却说「未记录」的，只允许发生在复用档；"
                    + "不许用「一般来说」「通常是」「大约」「应该」这类推测措辞。";

    @Autowired
    private RedisCache redisCache;
    private final ChatClient chatClient;
    private final ChatClient parseClient;
    private final ChatMemory chatMemory;
    private final ChatContextStore chatContextStore;

    public DishAgentController(ChatClient.Builder builder,
                               DishTools dishTools,
                               ChatMemory chatMemory,
                               ChatContextStore chatContextStore,
                               @Value("${spring.ai.openai.chat.model:qwen3.7-flash}") String model,
                               @Value("${spring.ai.openai.chat.temperature:0.2}") Double temperature)
    {
        this.chatMemory = chatMemory;
        this.chatContextStore = chatContextStore;
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(dishTools)
                .build()
                .getToolCallbacks();

        this.chatClient = builder.clone()
                // 这里只是兜底值，实际每次请求都会被下面的 .system(...) 覆盖，
                // 所以改规则只能改 BASE_SYSTEM 一个地方
                .defaultSystem(BASE_SYSTEM)
                // OpenAiChatModel 内部会把 options 强转成它自己的 OpenAiChatOptions。
                // 用 Spring AI 默认的 DefaultToolCallingChatOptions 会直接抛 ClassCastException，
                // 结果就是工具一个都没发到模型那边，模型从头到尾不知道自己有工具可用。
                // 再有：这里的 options 会覆盖 starter 的默认配置，model 和 temperature 必须自己带上。
                .defaultOptions(  // 发给模型的参数
                        OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(temperature)
                                .toolCallbacks(List.of(toolCallbacks))
                )
                // 调用前后干什么 —— 记忆的读写
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.parseClient = builder.clone()
                .defaultSystem("你是点单信息抽取器。从用户的话里提取菜名、份数、是否要辣、是否打包，"
                        + "未提及的字段填 null，不要调用任何工具，不要编造。")
                .build();
    }

    @GetMapping("/chat")
    public AjaxResult chat(@RequestParam String message, @RequestParam String sessionId)
    {
        // 首轮判定：这个会话还没有任何历史 = 这是第一句。
        // 为什么它能当缓存的前提 —— 多轮里模型的输入是「历史 + message」，历史每次都变，
        // 缓存天然失效；只有历史为空时，输入才真的只是 message，缓存才名正言顺。
        boolean firstTurn = chatMemory.get(sessionId).isEmpty();

        // 本会话此前查到过的菜品事实，没有记录时是空串。
        // 必须在查缓存之前算出来，因为它是下面两个缓存判断的开关之一：
        // 带了上下文说明这轮不是干净的问答，此时若命中首轮缓存直接 return，
        // 模型根本没被调用、工具没重查，用户会拿到一个「看着答了但这轮没查库」的答案。
        String contextText = chatContextStore.render(sessionId, message);

        // 为什么用 md5 不用原文 —— 中文长句当 Redis key 又长又占内存；
        // 为什么必须是确定性哈希 —— 同一个问题每次得算出同一个 key，所以不能带随机盐（加密哈希、UUID 都不行）。
        String key = RedisKeys.CHAT_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex(message.getBytes(StandardCharsets.UTF_8));

        // 只有首轮、且没有上下文时才查缓存
        if (firstTurn && contextText.isEmpty())
        {
            long cacheStart = System.nanoTime();
            String cached = redisCache.getCacheObject(key);
            long cacheCostMs = (System.nanoTime() - cacheStart) / 1_000_000;

            if (cached != null && !cached.isBlank())
            {
                // 命中缓存 = 没走 ChatClient = Advisor 没执行 = saveAll 没被调用，
                // 这一轮在记忆里是空的。必须手工补上，否则下一轮模型看不到这句。
                chatMemory.add(sessionId, List.of(
                        new UserMessage(message),
                        new AssistantMessage(cached)));
                log.info("首轮命中缓存: session={} <- {}", sessionId, message);
                return buildReply(cached, true, sessionId, cacheCostMs);
            }
        }
        // 把 ctxChars 打进日志：这是判断上下文到底有没有注入的唯一可靠信号，
        // 0 就是没注入（首次提问正常），非 0 说明这一轮确实带上了上一轮查到的事实
        log.info("调用模型: session={} firstTurn={} ctxChars={} <- {}",
                sessionId, firstTurn, contextText.length(), message);
        if (!contextText.isEmpty())
        {
            log.info("注入的上下文:\n{}", contextText);
        }

        // costMs 测的是「start 到 costMs 这两行之间」的时间
        long start = System.nanoTime();
        String reply = chatClient.prompt()
                // system 是覆盖不是追加：必须把 BASE_SYSTEM 原样带回来，
                // 否则 defaultSystem 那套三步执行规则会被整段顶掉
                .system(contextText.isEmpty() ? BASE_SYSTEM : BASE_SYSTEM + "\n\n" + contextText)
                .user(message)                                                              //  ← 说什么
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))   //  ← 翻哪一本本子
                // 把会话 id 递给工具层：工具查完库要按会话存事实。
                // 只能走请求级 toolContext，绝不能用 Builder.defaultToolContext ——
                // 那个是全局共享的，所有会话会串成同一本账。
                .toolContext(Map.of(ChatContextStore.SESSION_ID_KEY, sessionId))
                .call()                                                                     //  ← 真正发请求 ← 必须是最后一个
                .content();                                                                 //  ← 取回答文字
        long costMs = (System.nanoTime() - start) / 1_000_000;

        // 只有首轮才写缓存 —— 漏了这个条件，多轮的答案会覆盖首轮缓存，
        // 下次别人首轮问同一句，会拿到一个带着别人上下文的答案。
        if (firstTurn && contextText.isEmpty() && reply != null && !reply.isBlank())
        {
            redisCache.setCacheObject(key, reply, RedisKeys.CHAT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return buildReply(reply, false, sessionId, costMs);
    }

    /**
     * 统一出参：缓存命中与未命中走同一个出口，字段集合永远一致。
     * 少一个字段的代价是调用方要写两套解析，而 costMs 恰恰是判断
     * 「这次走没走模型」的唯一可靠信号，不能在某一支上缺席。
     */
    private AjaxResult buildReply(String text, boolean fromCache, String sessionId, long costMs)
    {
        // 坑：success(String) 命中的是 msg 重载，这里必须写两个参数
        AjaxResult result = AjaxResult.success("操作成功", text);
        result.put("fromCache", fromCache);
        result.put("sessionId", sessionId);
        result.put("costMs", costMs);
        return result;
    }

    @GetMapping("/parse")
    public AjaxResult parse(@RequestParam String message)
    {
        OrderRequest request = parseClient.prompt()
                .user(message)
                .call()
                .entity(OrderRequest.class);
        return AjaxResult.success(request);
    }
}
