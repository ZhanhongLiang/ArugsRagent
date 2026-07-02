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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容 Chat 协议的模板方法基类。
 *
 * <p>第 55 篇文档的核心观点是：百炼、硅基流动、Ollama、AIHubMix 这类供应商都兼容
 * OpenAI Chat Completions API，因此 90% 的逻辑可以复用：</p>
 * <ul>
 *     <li>把 {@link ChatRequest} 转成 OpenAI 格式 JSON 请求体。</li>
 *     <li>解析 provider/candidate 配置，构造 URL 和 Authorization。</li>
 *     <li>通过 OkHttp 发送同步或流式 HTTP 请求。</li>
 *     <li>把响应 JSON 中的 choices[0].message.content 提取成业务层字符串。</li>
 *     <li>把 HTTP 错误、网络错误、响应格式错误包装成 {@link ModelClientException}，交给路由层触发故障转移。</li>
 * </ul>
 *
 * <p>供应商子类只需要实现 {@link #provider()}，并按需覆写钩子方法
 * {@link #requiresApiKey()}、{@link #customizeRequestBody(JsonObject, ChatRequest)}、
 * {@link #isReasoningEnabledForStream(ChatRequest)}。</p>
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    /**
     * 同步 HTTP 客户端，用于 chat() 一次性请求响应。
     */
    @Autowired
    private OkHttpClient syncHttpClient;

    /**
     * 流式 HTTP 客户端，用于 streamChat() 长连接 SSE 读取。
     */
    @Autowired
    private OkHttpClient streamingHttpClient;

    /**
     * 流式读取线程池。OkHttp 的 SSE 读取是阻塞式的，需要放到后台线程执行。
     */
    @Autowired
    private Executor modelStreamExecutor;

    /**
     * 流式链路追踪辅助器，用于记录 provider stream 节点的端到端耗时。
     */
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

    /**
     * JSON 序列化与 SSE delta 解析共用的 Gson 实例。
     */
    protected Gson gson = new Gson();

    // ==================== 子类钩子方法 ====================

    /**
     * 流式调用是否解析 reasoning_content。
     *
     * <p>默认跟随 ChatRequest.thinking。支持思考过程输出的供应商会在 SSE delta 中返回 reasoning_content，
     * 解析后通过 {@link StreamCallback#onThinking(String)} 推给上层。</p>
     */
    protected boolean isReasoningEnabledForStream(ChatRequest request) {
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 请求体定制钩子。
     *
     * <p>基类已经负责 model、messages、temperature、top_p、top_k、max_tokens 等通用字段。
     * 子类如果有供应商私有字段，可以覆写本方法追加。默认实现用于深度思考：thinking=true 时添加 enable_thinking。</p>
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        if (Boolean.TRUE.equals(request.getThinking())) {
            body.addProperty("enable_thinking", true);
        }
    }

    /**
     * 当前供应商是否需要 API Key。
     *
     * <p>默认返回 true，适合百炼、硅基流动、AIHubMix 等云厂商。
     * Ollama 是本地服务，没有 Bearer Token 认证，因此子类会覆写为 false。</p>
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    /**
     * 同步 Chat 模板方法。
     *
     * <p>它定义了 OpenAI 兼容同步调用的完整骨架：校验配置 -> 构造请求体 -> 构造 HTTP 请求 ->
     * 发送请求 -> 处理 HTTP 错误 -> 解析 JSON -> 提取 content。子类不重写这条主流程，只通过钩子表达差异。</p>
     */
    protected String doChat(ChatRequest request, ModelTarget target) {
        // 1. 校验供应商配置：ModelTarget 必须携带 ProviderConfig，否则无法拿到 URL/API Key。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        // 2. 校验 API Key：云供应商默认需要；Ollama 覆写 requiresApiKey=false 后会跳过。
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 3. 构建 OpenAI Chat Completions 请求体；stream=false 表示同步调用。
        JsonObject reqBody = buildRequestBody(request, target, false);

        // 4. 构建 HTTP POST 请求：解析 URL，按需添加 Authorization，并写入 JSON body。
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        JsonObject respJson;
        try (Response response = syncHttpClient.newCall(requestHttp).execute()) {
            // 5. 非 2xx 响应视为模型调用失败，转换成结构化异常，交给 ModelRoutingExecutor 触发 fallback。
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " 同步请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }

            // 6. 正常响应解析为 JsonObject；响应为空或 JSON 异常会抛 INVALID_RESPONSE。
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            // 7. 网络超时、连接中断等 IOException 统一包装为 NETWORK_ERROR。
            throw new ModelClientException(
                    provider() + " 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 8. 从 choices[0].message.content 中提取业务层最终需要的回答文本。
        return extractChatContent(respJson);
    }

    // ==================== 模板方法：流式调用 ====================

    /**
     * 流式 Chat 模板方法。
     *
     * <p>第 55 篇只点到这里，后续 SSE 文章会展开。它和 doChat 共用请求体构建、URL 解析、认证逻辑，
     * 但请求体会加 stream=true，并把阻塞式响应读取提交到 modelStreamExecutor。</p>
     */
    protected StreamCancellationHandle doStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 流式调用同样先校验 provider 和 API Key。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // stream=true 会在请求体中添加 "stream": true。
        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        Call call = streamingHttpClient.newCall(streamRequest);
        boolean reasoningEnabled = isReasoningEnabledForStream(request);

        // 在调用线程开启 stream span，使后续 first-packet 子节点能正确归属父节点；
        // 该 span 由 SSE 终态或 cancel 收尾，记录真实端到端耗时。
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            // 包装 callback，让 complete/error/cancel 能同步结束 trace span。
            wrappedCallback = new StreamSpanCallback(callback, span);

            // 把阻塞式 SSE 读取提交到专用线程池；返回的句柄用于取消 OkHttp Call 和后台任务。
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled)
            );
            return () -> {
                try {
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            // 同步启动部分结束：把节点从当前线程的 NODE_STACK 弹出，避免污染兄弟节点的父节点链。
            span.detach();
        }
    }

    /**
     * 后台线程中的 SSE 读取主循环。
     *
     * <p>谁读流，谁触发回调：modelStreamExecutor 中的线程阻塞读取 OkHttp 响应行，解析出 token 后
     * 直接调用 callback.onContent/onThinking，最终一路推到 SseEmitter。这里没有额外消息队列。</p>
     */
    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            // HTTP 非成功状态同样转换成 ModelClientException，让上层首包探测感知失败。
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        provider() + " 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException(provider() + " 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }

            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                // OpenAI 兼容 SSE 是按行读取，核心数据通常在 data: 前缀行里。
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    // 解析 data 行，提取 delta.content、delta.reasoning_content 或 [DONE]。
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    // 单行解析失败时记录日志并继续读后续行，避免供应商偶发非 data 行中断整条流。
                    log.warn("{} 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            if (cancelled.get()) {
                log.info("{} 流式响应已被取消", provider());
                return;
            }
            if (!completed) {
                throw new ModelClientException(provider() + " 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    // ==================== 公共构建方法 ====================

    /**
     * 构建 OpenAI Chat Completions 请求体。
     *
     * <p>ChatRequest 是项目内部统一对象；这里把它映射成供应商 HTTP API 接收的 JSON。</p>
     */
    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject body = new JsonObject();

        // model 来自 YAML candidate.model，而不是 ChatRequest。
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        // 流式调用需要显式告诉供应商返回 SSE。
        if (stream) {
            body.addProperty("stream", true);
        }

        // messages 映射成 OpenAI 格式：[ {role, content}, ... ]。
        body.add("messages", buildMessages(request));

        // 生成参数只在非 null 时写入请求体；null 时交给供应商使用默认值。
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

        // 供应商差异点：默认添加 enable_thinking，子类也可以覆写追加私有参数。
        customizeRequestBody(body, request);
        return body;
    }

    /**
     * 将项目内部 ChatMessage 列表转换为 OpenAI messages 数组。
     */
    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }
        return arr;
    }

    /**
     * 把内部枚举角色映射成 OpenAI 协议字符串。
     */
    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * 构建带 URL 和认证头的 OkHttp Request.Builder。
     */
    private Request.Builder newAuthorizedRequest(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        Request.Builder builder = new Request.Builder()
                // URL 解析遵循两级优先级：candidate.url 优先，否则 provider.url + endpoints.chat。
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        if (requiresApiKey()) {
            // OpenAI 兼容协议常用 Bearer Token；Ollama 覆写 requiresApiKey=false 后不会添加。
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    /**
     * 从 OpenAI 兼容响应中提取回答文本。
     *
     * <p>标准路径是 choices[0].message.content。这里逐层校验，是为了把供应商异常响应转换成明确的
     * INVALID_RESPONSE，而不是让空指针或类型转换异常泄漏到业务层。</p>
     */
    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException(provider() + " 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(provider() + " 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }
}