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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.service.ratelimit.FairDistributedRateLimiter.AcquireRequest;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SSE 全局并发限流入口
 */
/**
 * 聊天排队限流器。
 *
 * <p>在流式问答入口控制并发坑位：拿到许可的请求立即执行，未拿到许可的请求排队等待，
 * 防止大模型和检索链路被瞬时流量打满。</p>
 *
 * <p>它不直接实现 Redis 排队算法，只负责 SSE 业务编排：关闭限流时走直通线程池，
 * 开启限流时把业务 lambda 交给 FairDistributedRateLimiter；等待超时或线程池拒绝时，
 * 负责写入会话记忆并推送 reject/finish/done 事件。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";

    private final FairDistributedRateLimiter chatRateLimiter;
    private final Executor chatEntryExecutor;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;

    /**
     * 把一次流式问答放入全局排队限流器。
     *
     * <p>RAG 是长连接，真正要限制的是“同时正在跑的生成任务数”，不是每秒请求数。
     * onAcquire 只有拿到分布式 permit 后才会执行，因此业务主链路天然受全局坑位控制。</p>
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) { // 如果没有采用全局限流，就直接执行了
            try {
                // 限流关闭时仍然不在当前线程跑业务，保持 Tomcat/SSE 入口线程快速返回。
                // 如果没有限流就直接线程执行Runnable任务，该任务没有返回值的
                chatEntryExecutor.execute(onAcquire); // 放到线程里面执行
            } catch (RejectedExecutionException ex) {
                // execute执行异常了，那么就跑出一场，sse也需要发送reject事件请求
                log.warn("直通分支线程池拒绝任务，转 reject 流程", ex);
                handleReject(question, conversationId, emitter);
            }
            return;
        }
        // 如果开启了全局限流，那么就需要进入限流入口
        chatRateLimiter.acquire(AcquireRequest.builder()
                .maxWaitMillis(TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds()))
                .onAcquired(onAcquire)
                .onTimeout(() -> handleReject(question, conversationId, emitter))
                .onAcquiredExecutor(chatEntryExecutor)
                // cancelBinder 把 SSE 连接生命周期绑定到排队 Ticket：前端断连、超时或发送异常都会取消排队请求。
                .cancelBinder(cancel -> {
                    emitter.onCompletion(cancel);
                    emitter.onTimeout(cancel);
                    emitter.onError(e -> cancel.run());
                })
                .build());
    }

    // ==================== Reject 业务 ====================

    /**
     * 排队超时或系统过载后的拒绝路径。
     *
     * <p>拒绝路径也要走完整 SSE 协议：尽量把用户问题和“系统繁忙”回复写入会话记忆，
     * 再推 reject/finish/done，前端不会只看到一个突然断开的 loading。</p>
     */
    private void handleReject(String question, String conversationId, SseEmitter emitter) {
        RejectedContext context = null;
        try {
            context = recordRejectedConversation(question, conversationId, resolveUserId());
        } catch (Exception ex) {
            // 记录失败不能阻塞 emitter，否则前端永远收不到 DONE
            log.warn("记录 reject 会话失败，仍向前端发送 DONE", ex);
        }
        sendRejectEvents(emitter, context);
    }

    /**
     * 记录被拒绝的对话。
     *
     * <p>即使没拿到执行许可，也把 user 问题和 assistant 的繁忙提示写入记忆，
     * 这样用户下一轮追问时不会丢失“刚才问了什么”的上下文。</p>
     */
    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question) || StrUtil.isBlank(userId)) {
            return null;
        }

        String actualConversationId;
        boolean isNewConversation;
        if (StrUtil.isBlank(conversationId)) {
            // 入参未带 conversationId：刚生成的雪花 ID 不可能命中已有会话，跳过 existence 查询
            actualConversationId = IdUtil.getSnowflakeNextIdStr();
            isNewConversation = true;
        } else {
            actualConversationId = conversationId;
            isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;
        }

        memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        String messageId = memoryService.append(actualConversationId, userId, ChatMessage.assistant(REJECT_MESSAGE));

        String title = Strings.EMPTY;
        if (isNewConversation) {
            // append(USER) 内部会触发 conversationService.createOrUpdate（含 LLM 生成标题），此处回查拿到生成结果
            var conversation = conversationGroupService.findConversation(actualConversationId, userId);
            title = conversation != null ? conversation.getTitle() : Strings.EMPTY;
            if (StrUtil.isBlank(title)) {
                title = buildFallbackTitle(question);
            }
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return Strings.EMPTY;
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    /**
     * 发送 reject 协议事件。
     *
     * <p>如果会话落库成功，会补齐 meta/finish；无论落库是否成功，最后一定发送 done 并关闭连接。</p>
     */
    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            sender.sendEvent(SSEEventType.FINISH.value(),
                    new CompletionPayload(String.valueOf(rejectedContext.messageId), rejectedContext.title));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }
}
