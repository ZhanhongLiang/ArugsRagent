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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;

/**
 * 供应商级 Chat 客户端接口。
 *
 * <p>它是第 55 篇文档里的第二层接口，位于 {@link LLMService} 之下。
 * 与业务层接口不同，ChatClient 需要知道本次调用的 {@link ModelTarget}，因为真正发 HTTP 请求时要用到：</p>
 * <ul>
 *     <li>candidate.model：请求体里的 model 字段。</li>
 *     <li>candidate.provider：用于找到具体供应商客户端。</li>
 *     <li>provider.url / endpoints / apiKey：用于拼接 URL 和认证。</li>
 * </ul>
 *
 * <p>只要一个新供应商实现本接口并注册成 Spring Bean，{@link RoutingLLMService} 就能把它纳入路由和故障转移。</p>
 */
public interface ChatClient {

    /**
     * 返回供应商标识。
     *
     * <p>这个值会作为 {@link RoutingLLMService} 中 clientsByProvider 的 key，
     * 必须和 YAML candidate.provider 以及 {@link ModelProvider#getId()} 保持一致。</p>
     *
     * @return 供应商 id，例如 bailian、siliconflow、ollama
     */
    String provider();

    /**
     * 同步 Chat 调用。
     *
     * <p>{@link ModelRoutingExecutor} 在故障转移过程中会逐个调用候选模型对应的 ChatClient。
     * 当前客户端如果抛出异常，执行器会调用熔断器 markFailure，并继续尝试下一个候选。</p>
     *
     * @param request 业务层统一 Chat 请求
     * @param target 本次调用的模型目标，包含模型名和供应商配置
     * @return 模型完整回答文本
     */
    String chat(ChatRequest request, ModelTarget target);

    /**
     * 流式 Chat 调用。
     *
     * <p>流式调用同样由路由层选择目标模型，但真实输出通过 callback 异步推送，
     * 返回的 {@link StreamCancellationHandle} 用于取消 OkHttp 调用和后台读取任务。</p>
     *
     * @param request 业务层统一 Chat 请求
     * @param callback 流式回调
     * @param target 本次调用的模型目标
     * @return 取消句柄
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);
}