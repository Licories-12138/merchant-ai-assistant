package com.sky.merchant.config;
import com.sky.merchant.agent.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration // 配置类
public class ChatMemoryConfig {
    @Bean    // 创建一个 Bean
    @Primary // 主要的，默认的
    /*
     * 声明一个 ChatMemory bean，并且明确告诉它用哪个 repository。
     */
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)  // ← 关键：挂钩到哪个仓库
                .maxMessages(20)                   // ← 窗口大小
                .build();
    }
}
