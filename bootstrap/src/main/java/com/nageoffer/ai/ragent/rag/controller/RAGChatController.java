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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器。
 *
 * <p>它暴露知识问答篇的两个用户入口：</p>
 * <ul>
 *     <li>{@code /rag/v3/chat}：建立 SSE 连接，后端开始流式生成答案。</li>
 *     <li>{@code /rag/v3/stop}：用户点击停止生成，通过 taskId 取消后台流式任务。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    // RAG 问答应用服务，真正执行排队、流水线和流式生成。
    private final RAGChatService ragChatService;
    // 默认配置，主要用于 SSE 超时时间。
    private final RAGDefaultProperties ragDefaultProperties;


    /**
     * 发起 SSE 流式对话, Controller 层的 `chat()` 方法上标注了 `@IdempotentSubmit` 注解，
     * 基于用户 ID 加分布式锁。用户快速连点两次发送按钮，第二次请求会被直接拦截，返回“当前会话处理中，请稍后再发起新的对话”。
     *
     * - `produces = "text/event-stream;charset=UTF-8"` 告诉 Spring 这是一个 SSE 端点，
     * 响应头会自动带上 `Content-Type: text/event-stream`
     * - `SseEmitter` 的超时时间从配置读取，SSE 系列讲过它的核心 API，这里不再展开
     * - `@IdempotentSubmit` 注解做了幂等保护——同一用户不能同时发起多个对话
     *
     * Controller 方法的线程模型很关键：创建 `SseEmitter` → 交给 Service 处理 → 立即返回。
     * Tomcat 线程在这里就释放了，不会被长时间占用。后续的流式推送发生在别的线程上，这个后面会详细讲。
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        // 创建 SSE 连接，超时时间来自配置；后续 token 通过该 emitter 推给前端。
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        // 业务处理异步进行，本方法立即返回 emitter 给 Spring MVC 维持长连接。
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        // 根据 taskId 找到对应 StreamCancellationHandle 并取消。
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
