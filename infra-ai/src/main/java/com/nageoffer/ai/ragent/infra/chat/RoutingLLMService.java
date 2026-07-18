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
    /** 当前路由线程等待首包时被中断的错误信息。 */
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    /** 选择器未返回任何可尝试模型时的错误信息。 */
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    /** 供应商客户端未能成功创建流式任务时的错误信息。 */
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    /** 在首包等待窗口内没有收到有效事件时的错误信息。 */
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    /** 流正常结束但没有内容或思考增量时的错误信息。 */
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    /** 所有候选模型均不可用时返回给上层的统一错误信息。 */
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
        // 保存模型路由、熔断、回退和首包 Trace 所需的协作组件。
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
        // 同步场景把选择、健康检查和失败回退全部委托给通用执行器。
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
        // 流式方法很快返回句柄，真正异常发生在后台读 SSE 时，不能直接复用同步回退执行器。
        // 首包探测将异步回调的结果转换为当前路由线程可判断的成功/失败信号。
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
            // 检查模型的是否健康程度，键康检测放行！！
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            // ProbeStreamBridge 会先截获首包、错误或完成事件，用于判断该模型是否真的可用。
            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);
            // 这个是取消句柄
            // 句柄同时用于候选失败后取消旧请求，以及用户点击停止生成时打断底层读取。
            StreamCancellationHandle handle;
            try {
                // 启动具体供应商的流式调用，通常会提交后台线程读取 SSE。
                // 这个直接通用调用AbstractOpenAIStyleChatClient，AbstractOpenAIStyleChatClient实现了ChatClient接口
                // 该ChatClient接口也是自定义的
                // request是已经封装好的各类上下文
                // client调用了异步线程用来处理，
                /**
                 * awaitFirstPacket(bridge, handle, callback) 的作用是：等待当前模型流式请求的“第一个有效信号”，用来判断这个模型到底能不能用。
                 * 它等的不是完整回答，而是等下面几种情况之一：
                 * 1. 收到第一个 content      -> SUCCESS
                 * 2. 收到第一个 thinking     -> SUCCESS
                 * 3. 收到 error             -> ERROR
                 * 4. 正常结束但没内容        -> NO_CONTENT
                 * 5. 超过 60 秒没任何首包    -> TIMEOUT
                 * 为什么要等首包？
                 * 因为流式调用不是普通同步调用。
                 * 这句：
                 * handle = client.streamChat(request, bridge, target);
                 * 只是说明：
                 * 流式请求启动了
                 * 但不代表模型真的可用。
                 * 可能会出现：
                 * 1. HTTP 连接成功，但模型一直不吐 token
                 * 2. 模型服务内部报错
                 * 3. 返回空流，直接结束
                 * 4. API Key 有问题，错误在后台读流线程里才暴露
                 */
                // bridge 作为 StreamCallback 传入供应商客户端，先缓存首包前的流事件。
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
            // bridge 是缓冲区，避免尚未确认可用的模型先把内容推送给前端。
            /**
             * 它本身不直接操作 SseEmitter，但它“利用 SSE”的方式是：接收模型供应商返回的 SSE token 回调，然后决定这些 token 要不要转发给真正的前端 SSE callback。
             * 正常链路是：
             * 模型供应商 SSE
             *   -> AbstractOpenAIStyleChatClient.doStream()
             *   -> callback.onContent(...)
             *   -> StreamChatEventHandler
             *   -> SseEmitter.send()
             *   -> 浏览器
             * 加了 ProbeStreamBridge 后变成：
             * 模型供应商 SSE
             *   -> AbstractOpenAIStyleChatClient.doStream()
             *   -> bridge.onContent(...)
             *   -> 先缓存，不立刻给前端
             *   -> 首包探测成功
             *   -> bridge.commit()
             *   -> downstream.onContent(...)
             *   -> StreamChatEventHandler
             *   -> SseEmitter.send()
             *   -> 浏览器
             * 这里的 downstream 就是真正会推 SSE 给前端的 callback：
             * private final StreamCallback downstream;
             * 也就是外面传进来的：
             */
            // 这里需要阻塞等待doStream的probe信息
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
        // provider 名称必须与 ChatClient.provider() 和 YAML candidate.provider 保持一致。
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
            // 在独立 Bean 中等待，以便 RagTraceAspect 记录首包耗时节点。
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
        // 将桥接器的协议级终态转换为统一远程异常，并保留模型与提供方诊断信息。
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
        // 所有候选均失败时只向业务回调发送一次统一错误，前面的探测残片不会被 bridge 提交。
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
        // 指定模型也必须通过当前模式下的候选和熔断过滤，不能绕过健康检查强行调用。
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}
