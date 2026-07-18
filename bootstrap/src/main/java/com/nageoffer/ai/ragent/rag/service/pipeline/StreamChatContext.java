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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 单次流式问答的流水线上下文。
 *
 * <p>不可变字段来自请求入口：原始问题、会话、任务、用户和回调。
 * 可变字段由 pipeline 各阶段逐步填充：历史记忆、改写结果、意图解析结果。
 * 这样每个阶段只读写自己的状态，避免方法参数越传越长。</p>
 */
@Getter
@Builder
public class StreamChatContext {

    // ==================== 不可变输入参数 ====================

    // 用户原始问题。
    private final String question;
    // 会话 id，用于加载/保存多轮记忆。
    private final String conversationId;
    // 流式任务 id，用于停止生成时找到取消句柄。
    private final String taskId;
    // 是否启用深度思考模型。
    private final boolean deepThinking;
    // 当前登录用户 id。
    private final String userId;
    // SSE 流式回调，负责向前端推送 token、完成和错误事件。
    private final StreamCallback callback;

    // ==================== 管道中填充的中间状态 ====================

    @Setter
    // 阶段 1 填充：历史消息 + 当前用户问题。
    private List<ChatMessage> history;

    @Setter
    // 阶段 2 填充：改写后的主问题和拆分出的子问题。
    private RewriteResult rewriteResult;

    @Setter
    // 阶段 3 填充：每个子问题对应的一组意图分数。
    private List<SubQuestionIntent> subIntents;

    /**
     * 在问答入口解析一次，后续异步意图分类与检索均使用同一份权限快照。
     */
    @Setter
    private KnowledgeAccessScope knowledgeAccessScope;
}
