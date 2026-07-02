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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 流式问答服务实现。
 *
 * <p>它位于 Controller 和真正问答流水线之间，负责处理一次问答请求的入口级编排：</p>
 * <ol>
 *     <li>确定 conversationId，没有传入时创建新会话。</li>
 *     <li>创建 taskId，后续用于用户点击停止生成时定位取消句柄。</li>
 *     <li>通过 StreamCallbackFactory 把 SSE emitter 包装成模型流式回调。</li>
 *     <li>进入 ChatQueueLimiter 排队/抢许可，控制并发坑位。</li>
 *     <li>进入 StreamChatTraceRunner，建立整次 RAG 流式链路 trace。</li>
 *     <li>构造 StreamChatContext，交给 StreamChatPipeline 执行八阶段问答主流程。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    /**
     * RAG 问答主流水线：记忆、改写、意图、检索、Prompt、流式生成都在里面完成。
     */
    private final StreamChatPipeline chatPipeline;

    /**
     * 分布式排队限流器，解决“10 个人同时问，只有 3 个坑位”的并发控制问题。
     */
    private final ChatQueueLimiter chatQueueLimiter;

    /**
     * 把 SseEmitter 包装成 StreamCallback，负责把模型 token 转成前端 SSE 事件。
     */
    private final StreamCallbackFactory callbackFactory;

    /**
     * 流式链路 trace 入口，保证后续节点耗时都挂在同一次 run 下。
     */
    private final StreamChatTraceRunner traceRunner;

    /**
     * 任务句柄管理器，保存 taskId -> StreamCancellationHandle 的映射，用于停止生成。
     */
    private final StreamTaskManager taskManager;

    /**
     * 发起一次流式 RAG 问答。
     */
    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // conversationId 为空代表新会话；否则继续已有会话并加载历史记忆。
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;

        // 每次生成都有独立 taskId，停止接口只需要拿 taskId 就能取消当前流式任务。
        String taskId = IdUtil.getSnowflakeNextIdStr();

        // SSE emitter -> StreamCallback。之后 pipeline 只关心 onContent/onComplete/onError，不直接操作 emitter。
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 先进入分布式队列限流；拿到许可后才真正执行 trace + pipeline。这里传入的是可延迟执行的业务 lambda。
        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                // onAcquire 回调真正运行时，说明已经拿到全局并发 permit，并进入 chatEntryExecutor 专用线程池。
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    // 构造一次问答的上下文对象。后续阶段会把 history、rewriteResult、subIntents 填进去。
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinking(Boolean.TRUE.equals(deepThinking))
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .build();
                    chatPipeline.execute(ctx);
                }));
    }

    /**
     * 停止指定流式任务。
     */
    @Override
    public void stopTask(String taskId) {
        // StreamTaskManager 内部会找到对应取消句柄并调用 cancel，同时清理任务状态。
        taskManager.cancel(taskId);
    }
}