package com.sky.merchant.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话记忆的隔离验证入口：只有 ChatMemory 一个 advisor，不带工具、不带上下文。
 *
 * 为什么要单独留一个：DishAgentController 里记忆、工具、上下文三层叠在一起，
 * 「模型记住了上一句」和「上下文块里本来就有这句」在出参上分不出来。
 * 这个入口把另外两层摘掉，只留记忆，用来验证 saveAll 覆盖语义、窗口裁剪、
 * Redis 重启后是否还在。
 *
 * 它不参与线上链路：路径独立（/merchant/agent/memory-test/chat），
 * 正式环境应该关掉（加 @Profile 或直接删除）。
 */
@RestController
@RequestMapping("/merchant/agent/memory-test")
public class MemoryTestController
{
    private final ChatClient chatClient;

    public MemoryTestController(ChatClient.Builder builder
            , ChatMemory chatMemory)
    {
        this.chatClient = builder.clone()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message, @RequestParam String sessionId)
    {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
