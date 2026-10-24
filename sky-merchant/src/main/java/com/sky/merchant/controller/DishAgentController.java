package com.sky.merchant.controller;

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
    @Autowired
    private RedisCache redisCache;
    private final ChatClient chatClient;
    private final ChatClient parseClient;
    private final ChatMemory chatMemory;

    public DishAgentController(ChatClient.Builder builder,
                               DishTools dishTools,
                               ChatMemory chatMemory,
                               @Value("${spring.ai.openai.chat.model:qwen3.7-flash}") String model,
                               @Value("${spring.ai.openai.chat.temperature:0.2}") Double temperature)
    {
        this.chatMemory = chatMemory;
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(dishTools)
                .build()
                .getToolCallbacks();

        this.chatClient = builder.clone()
                .defaultSystem("你是外卖商家系统的点单助手，只负责回答菜品相关的问题。"
                        + "回答涉及菜品的任何问题时，必须严格按下面三步执行，一步都不许跳过："
                        + "第一步：调用工具查询该菜品的最新数据。不要先判断需不需要查，直接查；"
                        + "不管你从上下文里是否已经知道菜名，都要重新查。"
                        + "第二步：只读取工具返回的内容，不要补充任何外部知识。"
                        + "第三步：返回内容里有这项信息，就据此回答；"
                        + "返回内容里确实没有，才可以说「未记录」，而且要带上菜名，"
                        + "例如「宫保鸡丁的辣度：菜品资料中未记录」。"
                        + "禁止：没有调用工具就直接使用「未记录」这个说法。"
                        + "禁止使用「一般来说」「通常是」「大约」「应该」这类推测措辞。")
                // 坑二：OpenAiChatModel 内部会把 options 强转成它自己的 OpenAiChatOptions。
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

        // 为什么用 md5 不用原文 —— 中文长句当 Redis key 又长又占内存；
        // 为什么必须是确定性哈希 —— 同一个问题每次得算出同一个 key，所以不能带随机盐（加密哈希、UUID 都不行）。
        String key = RedisKeys.CHAT_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex(message.getBytes(StandardCharsets.UTF_8));

        // 只有首轮才查缓存
        if (firstTurn)
        {
            String cached = redisCache.getCacheObject(key);
            if (cached != null && !cached.isBlank())
            {
                // 命中缓存 = 没走 ChatClient = Advisor 没执行 = saveAll 没被调用，
                // 这一轮在记忆里是空的。必须手工补上，否则下一轮模型看不到这句。
                chatMemory.add(sessionId, List.of(
                        new UserMessage(message),
                        new AssistantMessage(cached)));
                log.info("首轮命中缓存: session={} <- {}", sessionId, message);
                return AjaxResult.success("操作成功", cached).put("fromCache", true);
            }
        }
        log.info("调用模型: session={} firstTurn={} <- {}", sessionId, firstTurn, message);

        // costMs 测的是「start 到 costMs 这两行之间」的时间
        long start = System.nanoTime();
        String reply = chatClient.prompt()
                .user(message)                                                              //  ← 说什么
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))   //  ← 翻哪一本本子
                .call()                                                                     //  ← 真正发请求 ← 必须是最后一个
                .content();                                                                 //  ← 取回答文字
        long costMs = (System.nanoTime() - start) / 1_000_000;

        // 只有首轮才写缓存 —— 漏了这个条件，多轮的答案会覆盖首轮缓存，
        // 下次别人首轮问同一句，会拿到一个带着别人上下文的答案。
        if (firstTurn && reply != null && !reply.isBlank())
        {
            redisCache.setCacheObject(key, reply, RedisKeys.CHAT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        AjaxResult miss = AjaxResult.success("操作成功", reply);
        miss.put("fromCache", false);
        miss.put("sessionId", sessionId);
        miss.put("costMs", costMs);
        return miss;
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
