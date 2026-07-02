/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 对话记忆服务默认实现。
 *
 * <p>对应知识问答篇的多轮记忆部分：一次问答开始前加载历史消息和压缩摘要，
 * 问答结束后追加新消息，并在消息数量达到阈值时触发摘要压缩。
 * 这里把摘要和历史并行加载，是为了让 RAG 主链路在进入改写、意图和检索前尽快拿到上下文。</p>
 */
@Slf4j
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    /**
     * 原始消息存储，负责读写最近会话消息。
     */
    private final ConversationMemoryStore memoryStore;

    /**
     * 摘要服务，负责读取长期摘要并在必要时压缩旧消息。
     */
    private final ConversationMemorySummaryService summaryService;

    /**
     * 记忆加载线程池，用于并发读取摘要和最近历史。
     */
    private final Executor memoryLoadExecutor;

    public DefaultConversationMemoryService(ConversationMemoryStore memoryStore,
                                            ConversationMemorySummaryService summaryService,
                                            Executor memoryLoadExecutor) {
        this.memoryStore = memoryStore;
        this.summaryService = summaryService;
        this.memoryLoadExecutor = memoryLoadExecutor;
    }

    /**
     * 加载当前会话可用于 Prompt 的记忆上下文。
     *
     * <p>返回顺序是摘要消息在前、最近历史在后。摘要用于承接更早的上下文，
     * 最近历史用于保证当前几轮对话细节不丢失。</p>
     */
    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        // conversationId 或 userId 为空时，不进入数据库查询，直接让 RAG 退化为单轮问答。
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        try {
            // 摘要和最近历史互不依赖，异步并发加载，降低主链路等待时间。
            CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
                    () -> loadSummaryWithFallback(conversationId, userId), memoryLoadExecutor
            );
            CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
                    () -> loadHistoryWithFallback(conversationId, userId), memoryLoadExecutor
            );

            // 两路数据都回来后再合并，保证 Prompt 里能同时看到长期摘要和短期上下文。
            return CompletableFuture.allOf(summaryFuture, historyFuture)
                    .thenApply(v -> {
                        ChatMessage summary = summaryFuture.join();
                        List<ChatMessage> history = historyFuture.join();
                        log.debug("加载对话记忆 - conversationId: {}, userId: {}, 摘要: {}, 历史消息数: {}, 耗时: {}ms",
                                conversationId, userId, summary != null, history.size(), System.currentTimeMillis() - startTime);
                        return attachSummary(summary, history);
                    })
                    .join();
        } catch (Exception e) {
            log.error("加载对话记忆失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 加载摘要。摘要失败不能阻断问答，所以失败时只返回 null。
     */
    private ChatMessage loadSummaryWithFallback(String conversationId, String userId) {
        try {
            return summaryService.loadLatestSummary(conversationId, userId);
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载最近历史。历史失败时返回空列表，让主流程仍然可以继续回答当前问题。
     */
    private List<ChatMessage> loadHistoryWithFallback(String conversationId, String userId) {
        try {
            List<ChatMessage> history = memoryStore.loadHistory(conversationId, userId);
            return history != null ? history : List.of();
        } catch (Exception e) {
            log.error("加载历史记录失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 追加一条新消息，并把是否需要压缩摘要的判断放在写入之后执行。
     *
     * <p>这样 RAG 主流程只需要调用 append，不需要关心摘要触发条件。</p>
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        } // 追加当前一轮信息到表中，包括问题和回答
        String messageId = memoryStore.append(conversationId, userId, message);
        summaryService.compressIfNeeded(conversationId, userId, message);
        return messageId;
    }

    /**
     * 把摘要包装成 system 记忆消息并拼到历史前面。
     */
    private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        if (summary == null) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        result.add(summaryService.decorateIfNeeded(summary));
        result.addAll(messages);
        return result;
    }
}