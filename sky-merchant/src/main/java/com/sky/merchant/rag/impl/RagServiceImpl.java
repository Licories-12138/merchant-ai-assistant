package com.sky.merchant.rag.impl;

import com.sky.merchant.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;   // ← 注意下面这条
import org.springframework.stereotype.Service;

@Service
public class RagServiceImpl implements IRagService
{
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;

    public RagServiceImpl(VectorStore vectorStore,
                          EmbeddingModel embeddingModel,
                          ChatClient.Builder builder)
    {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.chatClient = builder.clone().build();   // ← 必须 clone
    }
    @Override
    public int indexDocument(String docName, String text)
    {
        return 0;
    }

    @Override
    public RagAnswer ask(String question)
    {
        return null;
    }
}
