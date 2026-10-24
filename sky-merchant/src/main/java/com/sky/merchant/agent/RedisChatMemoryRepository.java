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
    }

    /**
     * 框架内部零调用点
     * SPI 完整性 + 上层要做「会话列表/统计」时能枚举
     * @return 会话列表
     */
    @Override
    public List<String> findConversationIds() {
        return List.of();
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
                    log.warn("未知消息类型 {}，跳过第 {} 条", type, i);
                    continue;
                }
                switch (messageType) {
                    case USER      -> { if (StringUtils.hasText(text)) result.add(new UserMessage(text)); }
                    case ASSISTANT -> { if (StringUtils.hasText(text)) result.add(new AssistantMessage(text)); }
                    case SYSTEM    -> { if (StringUtils.hasText(text)) result.add(new SystemMessage(text)); }
                    default        -> log.warn("暂不支持的消息类型 {}，跳过第 {} 条", type, i);
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
        // 2. 把 List<Message> 手工转成 JSON 数组字符串（只存 type + text）
        JSONArray array = new JSONArray();
        for (Message m : messages) {
            if (m == null) {
                throw new IllegalArgumentException("消息列表中不能包含 null 元素");
            }
            JSONObject obj = new JSONObject();
            obj.put("type", m.getMessageType().name());
            obj.put("text", m.getText());
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
