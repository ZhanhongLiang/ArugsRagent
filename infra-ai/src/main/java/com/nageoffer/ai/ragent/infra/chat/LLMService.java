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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;

import java.util.List;

/**
 * 业务层使用的大语言模型 Chat 入口。
 *
 * <p>第 55 篇文档里把 Chat 子系统分成两层接口：</p>
 * <ul>
 *     <li>{@link LLMService}：面向业务层，只暴露 ChatRequest、prompt、流式回调等业务概念。</li>
 *     <li>{@link ChatClient}：面向供应商层，需要感知 provider、ModelTarget、HTTP 协议等基础设施概念。</li>
 * </ul>
 *
 * <p>因此，业务代码应该依赖本接口，而不是直接依赖百炼、硅基流动、Ollama 这些具体客户端。
 * 背后的模型选择、故障转移、熔断检查和 HTTP 调用都由 {@link RoutingLLMService} 组织完成。</p>
 */
public interface LLMService {

    /**
     * 同步 Chat 的便捷入口。
     *
     * <p>只传一个 prompt 时，默认把它包装成单条 user 消息的 {@link ChatRequest}，
     * 适合简单单轮问答或内部工具的轻量调用。</p>
     *
     * @param prompt 用户问题或提示词
     * @return 模型一次性返回的完整回答
     */
    default String chat(String prompt) {
        // 统一转换成 ChatRequest，让后续链路只处理一种请求对象。
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .build();
        return chat(req);
    }

    /**
     * 同步 Chat 的标准入口。
     *
     * <p>复杂场景应直接构造 ChatRequest，例如：system prompt、多轮 messages、temperature、top_p、
     * max_tokens、thinking 等参数。实现类会把这些参数映射到 OpenAI 兼容请求体。</p>
     *
     * @param request Chat 统一请求对象
     * @return 模型一次性返回的完整回答
     */
    String chat(ChatRequest request);

    /**
     * 指定模型 id 的同步 Chat 入口。
     *
     * <p>默认实现回退到普通路由，真正支持指定模型的是 {@link RoutingLLMService}。
     * 这里保留默认方法，是为了让其它简单实现不必强制实现该重载。</p>
     *
     * @param request Chat 统一请求对象
     * @param modelId 指定模型 id，通常对应 YAML candidate.id 或 provider::model 兜底 id
     * @return 模型一次性返回的完整回答
     */
    default String chat(ChatRequest request, String modelId) {
        return chat(request);
    }

    /**
     * 流式 Chat 的便捷入口。
     *
     * <p>同 {@link #chat(String)}，这里也会把 prompt 包装成单条 user 消息；
     * 模型增量输出通过 {@link StreamCallback} 推送。</p>
     *
     * @param prompt 用户问题或提示词
     * @param callback 流式回调，接收 thinking、content、complete、error 等事件
     * @return 取消句柄，可中断正在进行的流式输出
     */
    default StreamCancellationHandle streamChat(String prompt, StreamCallback callback) {
        // 统一转换成 ChatRequest，避免流式和同步两条链路各自维护 prompt 包装逻辑。
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .build();
        return streamChat(req, callback);
    }

    /**
     * 流式 Chat 的标准入口。
     *
     * <p>第 55 篇主要讲同步调用；流式调用会在后续 SSE 解析文章里展开。
     * 这里仍放在同一个业务接口中，是为了让业务层用同一套 LLMService 同时获得同步和流式能力。</p>
     *
     * @param request Chat 统一请求对象
     * @param callback 流式回调
     * @return 取消句柄，可中断正在进行的流式输出
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback);
}