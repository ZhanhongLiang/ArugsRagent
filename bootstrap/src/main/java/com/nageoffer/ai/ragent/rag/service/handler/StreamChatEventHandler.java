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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;

import java.util.Optional;

@Slf4j
/**
 * 流式聊天事件处理器。
 *
 * <p>它实现 StreamCallback，把模型 token、完成事件和错误事件转换成前端 SSE 事件，
 * 同时负责把完整 assistant 回答写回会话记忆。</p>
 *
 * <p>SSE 协议事件顺序：</p>
 * <ul>
 *     <li>构造阶段发送 meta，给前端 conversationId 和 taskId。</li>
 *     <li>onContent/onThinking 持续发送 message 事件。</li>
 *     <li>onComplete 落库后发送 finish + done，并关闭连接。</li>
 *     <li>用户取消时由 StreamTaskManager 发送 cancel + done。</li>
 * </ul>
 */
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    // answer/thinking 是取消时保存部分内容的来源，也是正常完成时落库的最终内容。
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long thinkingStartMs;
    private int thinkingDurationSeconds;

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务。
     *
     * <p>meta 必须尽早发送，因为前端停止生成需要 taskId。随后注册到 StreamTaskManager，
     * 让停止请求能找到本 SSE sender，并在取消时保存已累积内容。</p>
     */
    private void initialize() {
        // 发送消息给客户端
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）。
     *
     * <p>取消时不丢弃已有回答：把已经累积的 answer/thinking 写入会话记忆，
     * 再把 messageId 放进 cancel 事件，前端就能展示“生成到一半”的内容。</p>
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            try {
                String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
                ChatMessage message = ChatMessage.assistant(content, thinkingContent, resolveThinkingDuration());
                messageId = memoryService.append(conversationId, userId, message);
            } catch (Exception e) {
                log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
            }
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    /**
     * 正式回答增量入口。
     *
     * <p>底层 SSE 解析出 delta.content 后会一路回调到这里。当前线程通常就是模型流读取线程，
     * 所以“谁读流，谁推送”：这里会直接调用 SseEmitterSender 把内容发给浏览器。</p>
     */
    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            thinkingDurationSeconds = Math.max(1, Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        answer.append(chunk);
        /**
         * 模型的 `onContent()` 回调粒度不确定——可能是单个字符，也可能是一整句话。
         * 如果每收到一批服务端返回的内容都进行分发，消息一堆一堆的出现，用户感觉不到流式效果。`sendChunked()` 做了一个缓冲：
         */
        sendChunked(TYPE_RESPONSE, chunk);
    }

    /**
     * 深度思考增量入口。
     *
     * <p>供应商返回 reasoning_content 时走这里，前端用 type=think 和正式回答分开展示。
     * thinkingDurationSeconds 在第一次正式回答出现时计算，表示思考阶段耗时。</p>
     */
    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        sendChunked(TYPE_THINK, chunk);
    }

    /**
     * 正常结束入口。
     *
     * <p>模型流读到 [DONE] 或 finish_reason 后触发。这里先落库完整 assistant 消息，
     * 再发送 finish + done，最后 unregister 任务并关闭 SSE 连接。</p>
     */
    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent, resolveThinkingDuration());
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception e) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    /**
     * 异常结束入口。
     *
     * <p>如果异常发生在用户取消之后，就静默忽略；否则清理任务并通过 SseEmitterSender.fail
     * 关闭连接，避免流已经开始后再走全局异常响应造成协议冲突。</p>
     */
    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    /**
     * 按 code point 分块推送增量内容。
     *
     * <p>模型回调粒度不稳定，可能一次给一个字，也可能一次给一整句。这里按配置拆成较小片段，
     * 让前端呈现更稳定的流式效果；使用 codePointAt/appendCodePoint 是为了不把 emoji 等代理对字符切坏。</p>
     */
    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        // 增量添加信息
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
