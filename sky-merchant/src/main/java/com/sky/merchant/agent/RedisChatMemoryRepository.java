package com.sky.merchant.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sky.merchant.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Component
@NullMarked
public class RedisChatMemoryRepository implements ChatMemoryRepository {
    private final StringRedisTemplate redisTemplate;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("会话记忆仓储已装配：{}，key 前缀 {}，TTL {} 秒",
                getClass().getSimpleName(),
                RedisKeys.CHAT_MEMORY_PREFIX,
                RedisKeys.CHAT_MEMORY_TTL_SECONDS);
    }

    /**
     * 未实现，故意抛异常而不是返回空列表。
     * 返回 List.of() 会让调用方以为「当前没有任何会话」，
     * 真相是「这个能力根本没做」—— 静默的错误比响亮的失败更难查。
     * 根本原因：key 设计里没有维护会话索引，想枚举只能拿 KEYS 做全库扫描，
     * 而 KEYS 是 O(N) 阻塞命令，不能拿来兜底。
     * @return 永远不返回
     * @throws UnsupportedOperationException 固定抛出
     */
    @Override
    public List<String> findConversationIds() {
        throw new UnsupportedOperationException(
                "RedisChatMemoryRepository 暂不支持枚举会话：需要先加 ZSet 会话索引");
    }

    /**
     * 唯一读入口
     * @param conversationId 会话ID
     * @return 会话中的消息列表
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            // 1. 一次 GET 拿到 JSON 文本
            String json = redisTemplate.opsForValue().get(key(conversationId));

            // 2. 没有 / 空串 → 空列表，绝不返 null
            if (!StringUtils.hasText(json)) {
                return List.of();
            }
            JSONArray array = JSONArray.parseArray(json);
            List<Message> result = new ArrayList<>();

            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String type = obj.getString("type");
                String text = obj.getString("text");
                if (type == null){
                    log.warn("第 {} 条消息缺少 type 字段，跳过", i);
                    continue;
                }
                // Enum.valueOf 遇到不认识的 name 会直接抛 IllegalArgumentException，根本轮不到 switch 的 default
                // 先用try兜住
                MessageType messageType;
                try {
                    messageType = MessageType.valueOf(type);
                } catch (IllegalArgumentException e) {
                    // 走到这里说明 JSON 里的 type 不是任何已知枚举名：
                    // 要么数据损坏，要么 Spring AI 升级后新增了枚举值。
                    // 和「类型认识但本类没实现」不是一回事，文案必须区分开
                    log.warn("消息类型 [{}] 无法识别，跳过第 {} 条", type, i);
                    continue;
                }
                switch (messageType)
                {
                    case USER ->
                    {if (StringUtils.hasText(text)) { result.add(new UserMessage(text)); }}
                    case SYSTEM ->
                    {if (StringUtils.hasText(text)) { result.add(new SystemMessage(text)); }}
                    case ASSISTANT ->
                    {
                        // 带 toolCalls 的 assistant 文本可能为 null，
                        // 所以不能用 hasText 判它该不该活下来
                        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                        JSONArray callArray = obj.getJSONArray("toolCalls");
                        if (callArray != null)
                        {
                            for (int k = 0; k < callArray.size(); k++)
                            {
                                JSONObject c = callArray.getJSONObject(k);
                                calls.add(new AssistantMessage.ToolCall(
                                        c.getString("id"),
                                        c.getString("type"),
                                        c.getString("name"),
                                        c.getString("arguments")));
                            }
                        }
                        if (StringUtils.hasText(text) || !calls.isEmpty())
                        {
                            result.add(AssistantMessage.builder()
                                    .content(text)      // 允许 null，已核实构造器不拦
                                    .toolCalls(calls)
                                    .build());
                        }
                    }
                    case TOOL ->
                    {
                        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                        JSONArray responseArray = obj.getJSONArray("toolResponses");
                        if (responseArray != null)
                        {
                            for (int k = 0; k < responseArray.size(); k++)
                            {
                                JSONObject r = responseArray.getJSONObject(k);
                                responses.add(new ToolResponseMessage.ToolResponse(
                                        r.getString("id"),
                                        r.getString("name"),
                                        r.getString("data")));
                            }
                        }
                        if (!responses.isEmpty())
                        {
                            result.add(ToolResponseMessage.builder().responses(responses).build());
                        }
                    }
                    // 这里故意不写 default：
                    // MessageType 只有 USER / ASSISTANT / SYSTEM / TOOL 四个值，上面已全部覆盖，
                    // default 永远进不去。原来这里那条 log.warn 和上面 catch 里那条一字不差，
                    // 看着像「同一条日志打了两遍」，实际是死代码。
                    // 将来 Spring AI 新增枚举值时，编译器不会提醒，必须自己回来补 case。
                }
            }
            return result;
        } catch (Exception e) {
            log.error("解析失败: {}", conversationId, e);
            return List.of();
        }
    }

    /**
     * 唯一写入口
     * @param conversationId 会话ID
     * @param messages 消息列表
     */
    @Override
    @SuppressWarnings("ConstantConditions")
    public void saveAll(String conversationId, List<Message> messages) {
        // 1. 入参非空校验（会话ID不能为空）
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        // 2. 把 List<Message> 手工转成 JSON 数组字符串
        //    存三样：type（还原时靠它选构造器）、text、工具轨迹（仅当这条消息自己带）
        JSONArray array = new JSONArray();
        for (Message m : messages) {
            if (m == null) {
                throw new IllegalArgumentException("消息列表中不能包含 null 元素");
            }
            JSONObject obj = new JSONObject();
            obj.put("type", m.getMessageType().name());
            obj.put("text", m.getText());
            // 工具调用：text 往往为 null，真正的内容在 toolCalls 里
            // 生效边界（2026-10-25 实测，别误解成「已经在存工具轨迹了」）：
            //   走 MessageChatMemoryAdvisor 时这两段都进不来。该 advisor 的
            //   before() 只 add 最后一条用户消息、after() 只 add 最终答案一条，
            //   工具轨迹在到达本类之前就被摘掉了 —— 实测一次真实查库请求
            //   （costMs 2531ms，答案取自库里的 28.00）落库只有 2 条：
            //   USER + ASSISTANT，没有任何 toolCalls / toolResponses。
            //   保留这段的理由：本类的职责是「存什么读什么」，不该假设调用方送什么。
            //   任何人手工 chatMemory.add(带工具轨迹的消息) 时，这里必须无损往返。
            //   所以它的验收方式是单测，不是跑接口。
            if (m instanceof AssistantMessage assistant && assistant.hasToolCalls())
            {
                JSONArray calls = new JSONArray();
                for (AssistantMessage.ToolCall call : assistant.getToolCalls())
                {
                    JSONObject c = new JSONObject();
                    c.put("id", call.id());        // 与 tool 响应配对的唯一凭据，不能丢
                    c.put("type", call.type());
                    c.put("name", call.name());
                    c.put("arguments", call.arguments());
                    calls.add(c);
                }
                obj.put("toolCalls", calls);
            }

            // 工具返回：text 恒为空串（构造器写死），内容全在 responses 里
            // 生效边界同上一段：advisor 不会送 ToolResponseMessage 进来，只能靠手工 add 触发
            if (m instanceof ToolResponseMessage toolResponse)
            {
                JSONArray responses = new JSONArray();
                for (ToolResponseMessage.ToolResponse r : toolResponse.getResponses())
                {
                    JSONObject o = new JSONObject();
                    o.put("id", r.id());
                    o.put("name", r.name());
                    o.put("data", r.responseData());
                    responses.add(o);
                }
                obj.put("toolResponses", responses);
            }
            array.add(obj);
        }
        String json = array.toString();
        // 3. 存入 Redis
        redisTemplate.opsForValue().set(key(conversationId), json,
                Duration.ofSeconds(RedisKeys.CHAT_MEMORY_TTL_SECONDS));
    }

    /**
     * 清空会话
     * @param conversationId 会话ID
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return RedisKeys.CHAT_MEMORY_PREFIX + conversationId;
    }
}
