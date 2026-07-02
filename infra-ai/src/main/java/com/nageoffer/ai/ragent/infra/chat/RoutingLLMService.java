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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLMService 实现。
 *
 * <p>它是业务层 {@link LLMService} 和供应商层 {@link ChatClient} 之间的桥梁。
 * 第 55 篇文档中的同步调用链路可以概括为：</p>
 * <ol>
 *     <li>业务层调用 {@link #chat(ChatRequest)}。</li>
 *     <li>通过 {@link ModelSelector} 选出按优先级排序的 Chat 候选模型。</li>
 *     <li>通过 {@link ModelRoutingExecutor} 遍历候选列表并执行故障转移。</li>
 *     <li>每个候选根据 provider 找到对应 {@link ChatClient}。</li>
 *     <li>最终调用 {@code client.chat(request, target)}，进入供应商 HTTP 模板方法。</li>
 * </ol>
 *
 * <p>这个类本身不拼 HTTP 请求、不解析 JSON，也不关心百炼和 Ollama 的协议细节；
 * 它只负责“选谁、找谁、失败后怎么换下一个”。</p>
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    /**
     * 流式调用等待首包的最长时间。同步调用不需要这个探测逻辑。
     */
    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    /**
     * 候选模型选择器：负责根据 deepThinking、默认模型、优先级和熔断预过滤构建候选列表。
     */
    private final ModelSelector selector;

    /**
     * 三态熔断器：流式调用需要在路由层手动 allowCall、markSuccess、markFailure。
     * 同步调用的熔断联动由 ModelRoutingExecutor 统一处理。
     */
    private final ModelHealthStore healthStore;

    /**
     * 同步调用的故障转移执行器：遍历候选、调用客户端、失败后切换下一个模型。
     */
    private final ModelRoutingExecutor executor;

    /**
     * 流式调用首包探测器：用于判断异步 stream 是否真的开始返回内容。
     */
    private final LlmFirstPacketProbe firstPacketProbe;

    /**
     * provider -> ChatClient 的索引。
     *
     * <p>Spring 会把所有 ChatClient Bean 注入进来，这里按 provider id 建 Map，
     * 执行器遍历 ModelTarget 时就能 O(1) 找到对应供应商客户端。</p>
     */
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            LlmFirstPacketProbe firstPacketProbe,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.firstPacketProbe = firstPacketProbe;

        // 将 Spring 自动发现的供应商客户端转成 provider 索引，provider() 必须和 YAML candidate.provider 对齐。
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    /**
     * 同步 Chat 路由入口。
     *
     * <p>这是第 55 篇文档里“一行代码串起整条链路”的地方：
     * 选择候选 -> 找供应商客户端 -> 调用 client.chat -> 执行器负责失败切换。</p>
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                // 告诉执行器当前能力是 Chat，用于日志和错误消息展示。
                ModelCapability.CHAT,

                // 根据是否开启 thinking 选择普通 Chat 候选或深度思考候选。
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking())),

                // 对每个 ModelTarget，根据 provider 找到对应 ChatClient。
                target -> clientsByProvider.get(target.candidate().getProvider()),

                // 真正的供应商调用；异常会被 executeWithFallback 捕获并触发 markFailure + fallback。
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 指定模型 id 的同步 Chat。
     *
     * <p>如果 modelId 为空，完全走默认路由；如果指定了 modelId，就先从当前候选列表里找出该模型，
     * 再交给同一个故障转移执行器执行。这里候选列表只有一个目标，所以不会切到其它模型。</p>
     */
    @Override
    public String chat(ChatRequest request, String modelId) {
        // 未指定 modelId 时，保持普通路由行为。
        if (!StringUtils.hasText(modelId)) {
            return chat(request);
        }

        return executor.executeWithFallback(
                ModelCapability.CHAT,
                // 指定模型场景只允许目标模型参与本次调用。
                List.of(resolveTarget(modelId, Boolean.TRUE.equals(request.getThinking()))),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 流式 Chat 路由入口。
     *
     * <p>第 55 篇主要讲同步调用；这里先保留流式主线注释：流式调用返回句柄很快，
     * 真正错误可能发生在后台读取 SSE 时，所以需要首包探测来决定是否标记成功。</p>
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        // 选择 Chat 候选列表；ModelSelector 已做过选择阶段的熔断预过滤。
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        // 流式调用不能直接复用同步 executeWithFallback，因为是否成功要等首包或错误回调。
        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }

            // 调用阶段的最终熔断检查，可能把 OPEN 冷却到期的模型推进到 HALF_OPEN。
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            // ProbeStreamBridge 会先截获首包/错误/完成事件，用于判断该模型是否真的可用。
            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);

            StreamCancellationHandle handle;
            try {
                // 启动具体供应商的流式调用，通常会提交后台线程读取 SSE。
                handle = client.streamChat(request, bridge, target);
            } catch (Exception e) {
                // 启动阶段直接失败，标记模型失败并尝试下一个候选。
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                // 没有取消句柄意味着无法管理后台请求生命周期，视为启动失败。
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            // 等待首包、错误、完成或超时，用真实输出结果判断该模型是否可用。
            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback);

            if (result.isSuccess()) {
                // 首包成功，说明该模型本次可用；HALF_OPEN 探测成功时会恢复 CLOSED。
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 首包失败、超时或无内容完成：标记失败，取消当前供应商请求，再尝试下一个候选。
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败，通知上层回调并抛出统一远程异常。
        throw notifyAllFailed(callback, lastError);
    }

    /**
     * 根据目标模型的 provider 找到对应 ChatClient。
     */
    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    /**
     * 等待流式调用首包，并处理等待线程被中断的情况。
     */
    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                           StreamCancellationHandle handle,
                                                           StreamCallback callback) {
        try {
            return firstPacketProbe.awaitFirstPacket(bridge, FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 当前线程被中断时，要恢复中断标记、取消供应商请求，并把错误通知给业务回调。
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    /**
     * 把首包探测失败结果转换成 lastError，并记录可排查的供应商日志。
     */
    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    /**
     * 所有候选模型都失败后的统一收尾。
     */
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    /**
     * 从当前模式的 Chat 候选列表中解析指定模型。
     *
     * <p>如果指定模型已经被熔断预过滤，或不支持当前 deepThinking 模式，这里会抛出不可用异常。</p>
     */
    private ModelTarget resolveTarget(String modelId, boolean deepThinking) {
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}