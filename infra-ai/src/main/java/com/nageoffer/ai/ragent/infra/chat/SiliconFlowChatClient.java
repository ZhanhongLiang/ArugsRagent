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
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 硅基流动 Chat 客户端。
 *
 * <p>这个类本身几乎不写协议逻辑，是第 55 篇“模板方法”设计的直接体现：
 * OpenAI 兼容请求构建、HTTP 调用、响应解析、错误分类都放在 {@link AbstractOpenAIStyleChatClient}；
 * 子类只声明自己是谁，以及少量供应商差异。</p>
 *
 * <p>硅基流动兼容 OpenAI 协议，沿用基类默认 requiresApiKey=true 和 enable_thinking 请求体钩子。</p>
 */
@Slf4j
@Service
public class SiliconFlowChatClient extends AbstractOpenAIStyleChatClient {

    /**
     * 返回当前供应商 id。
     *
     * <p>RoutingLLMService 会用这个值把 Spring 注入的 ChatClient 列表整理成 provider -> client 的 Map，
     * 因此它必须和 YAML candidate.provider 保持一致。</p>
     */
    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    /**
     * 同步 Chat 调用入口。
     *
     * <p>这里加供应商专属的 RagTraceNode，便于链路追踪区分本次调用实际落到了哪个 provider。
     * 真实调用流程委托给基类 doChat 模板方法。</p>
     */
    @Override
    @RagTraceNode(name = "siliconflow-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    /**
     * 流式 Chat 调用入口。
     *
     * <p>和同步调用一样，供应商子类只负责暴露入口，SSE 请求构建、异步读取和取消处理由基类模板完成。</p>
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}