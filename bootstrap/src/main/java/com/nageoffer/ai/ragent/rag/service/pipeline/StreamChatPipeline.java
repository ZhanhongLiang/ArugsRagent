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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.knowledge.access.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * 流式 RAG 问答流水线。
 *
 * <p>它承载“知识问答在后端经历的八个阶段”：</p>
 * <ol>
 *     <li>加载并追加多轮对话记忆。</li>
 *     <li>把用户原始问题改写为更适合检索的查询，并拆分子问题。</li>
 *     <li>基于意图树为每个子问题打分，选择知识库意图和 MCP 工具意图。</li>
 *     <li>必要时做歧义引导，让用户先补充条件。</li>
 *     <li>系统直答场景短路，不进入知识库检索。</li>
 *     <li>多通道并行检索知识库和工具数据。</li>
 *     <li>组装检索结果、MCP 数据、意图和历史记忆，构造最终 Prompt。</li>
 *     <li>调用 LLMService.streamChat，把答案通过 SSE 一个 token 一个 token 推给前端。</li>
 * </ol>
 *
 * <p>每个 handleXxx 返回 true 表示该阶段已经完成响应并短路，后续阶段不再执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final SearchChannelProperties searchProperties;
    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final KnowledgeAccessService knowledgeAccessService;

    /**
     * 执行一次流式问答。
     */
    public void execute(StreamChatContext ctx) {
        ctx.setKnowledgeAccessScope(knowledgeAccessService.currentAccessScope());
        // 阶段 1：加载历史记忆，并把当前用户问题追加为最新 user 消息。
        loadMemory(ctx);
        // 阶段 2：问题改写与子问题拆分，解决“用户说的话不等于该搜的词”。
        rewriteQuery(ctx); // 1. 基础改写 2.通过LLM改写
        // 阶段 3：意图解析，对子问题命中的意图节点打分和筛选。
        resolveIntents(ctx);

        // 阶段 4：歧义引导。如果多个业务意图都“举手”，先让用户补充条件。
        if (handleGuidance(ctx)) {
            return;
        }
        // 阶段 5：系统直答。只命中系统型意图时，不查知识库，直接用系统 Prompt 回复。
        if (handleSystemOnly(ctx)) {
            return;
        }

        // 阶段 6：多通道检索，可能同时查多个知识库和 MCP 工具。
        RetrievalContext retrievalCtx = retrieve(ctx);
        // 阶段 7：空检索兜底，避免把没有上下文的问题继续交给模型硬编。
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        // 阶段 8：构造最终 Prompt 并流式生成答案。
        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    /**
     * 阶段 1：从记忆服务加载会话历史，并追加当前问题。
     */
    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                ChatMessage.user(ctx.getQuestion())
        );
        ctx.setHistory(history);
    }

    /**
     * 阶段 2：问题改写和多子问题拆分。
     */
    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult); // 将rewrittenQuestion和subQuestions重写赋给ctx
    }

    /**
     * 阶段 3：意图树解析，为每个子问题选择知识库或 MCP 工具意图。
     */
    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(
                ctx.getRewriteResult(), ctx.getKnowledgeAccessScope());
        ctx.setSubIntents(subIntents);
    }

    /**
     * 阶段 4：歧义引导短路。
     */
    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        // 短路：已经把引导文案推给用户，不再做检索 --- 因为需要歧义引导，需要将
        StreamCallback callback = ctx.getCallback();
        // 将引导问题直接推给前端，等待用户下一轮补充， 需要将引导文案展示给前端
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    /**
     * 阶段 5：系统直答短路。
     *
     * 用一张表汇总三种意图类型的处理方式：
     *
     * | 意图类型 | 处理方式        | 关键代码入口        | 是否走向量检索 | 备注                                   |
     * | -------- | --------------- | ------------------- | -------------- | -------------------------------------- |
     * | SYSTEM   | 短路直接回复    | handleSystemOnly()  | 否             | 可配自定义 Prompt，Temperature=0.7     |
     * | MCP      | 工具调用        | executeMcpTools()   | 否             | LLM 提取参数 + 工具执行                |
     * | KB       | 定向 / 全局检索 | retrieveAndRerank() | 是             | 走多通道检索引擎，可能两个通道同时激活 |
     */
    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores())); // 必须全部是关于系统问题
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        // bindHandle 的时机在 llmService.streamChat 返回之后；如果用户已经点停止，StreamTaskManager 会立即 cancel 该句柄。
        // 因为用户有可能会没等到模型输出就点了停止，所以这个句柄是用来控制停止的，
        taskManager.bindHandle(ctx.getTaskId(), handle);
        return true;
    }

    /**
     * 阶段 6：检索知识库上下文和 MCP 工具上下文。
     */
    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(
                ctx.getSubIntents(), searchProperties.getDefaultTopK(), ctx.getKnowledgeAccessScope());
    }

    /**
     * 阶段 7：检索为空时直接回复兜底文案。
     */
    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    /**
     * 阶段 8：合并意图、构造 Prompt、启动流式模型生成。
     */
    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        // 多个子问题的意图先合并成一个全局 IntentGroup，供 Prompt 规划使用。
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());
        // RAG 标准回答：把改写结果、检索上下文、MCP 上下文、意图和历史记忆组装成结构化 Prompt。
        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback() // 这个是ctx里面定义的回调函数
        );
        // 标准 RAG 流式调用也需要绑定取消句柄，停止生成才能打断底层 OkHttp SSE 读取。
        //注意返回值——`streamChat()` 返回一个 `StreamCancellationHandle`，也就是取消句柄。
        // 拿到句柄后，`streamRagResponse()` 立即通过 `taskManager.bindHandle()` 把它绑定到当前任务。
        // 用户按下停止键之后，可以中断流，就是使用取消句柄
        // handel句柄
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================

    /**
     * 系统意图直答：只拼系统 Prompt + 历史 + 当前问题，不带知识库上下文。
     * **自定义 Prompt 优先。** SYSTEM 节点上可以配 `promptTemplate`。比如欢迎与问候节点配了一段用活泼亲切的语气和用户打招呼，
     * 介绍自己的能力范围——这比默认的通用 System Prompt 更贴合场景。`findFirst` 找到第一个非空的自定义模板就用，没配就退回到全局默认。
     *
     * **会话历史照常带入。** `streamSystemResponse` 的实现里把 `history` 完整传入了消息列表：
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    /**
     * RAG 标准回答：把改写结果、检索上下文、MCP 上下文、意图和历史记忆组装成结构化 Prompt。
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        // PromptContext 是最终 Prompt 的原料包：问题、知识库上下文、工具上下文和意图信息都在这里。
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();
        // promptContext 需要和历史、重写子问题拼凑一起，拼凑成ChatMessage
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        // MCP 场景需要模型整合工具数据，温度略放宽；纯知识库问答更强调稳定引用，温度为 0。
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        // 这是流式生成链路的最后一跳：RoutingLLMService 选模型，供应商 Client 读 SSE，callback 把 token 推给前端。
        return llmService.streamChat(chatRequest, callback);
    }
}
