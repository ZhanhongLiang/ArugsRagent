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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 助手回答点赞/点踩的异步投递与最终幂等写入服务。
 *
 * <p>HTTP 请求先发布 MQ 事件以快速返回；消费者随后调用同步写入方法。
 * 唯一维度是用户和消息，因此重复点击更新同一条反馈而非不断插入新记录。</p>
 */
@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    private final MessageFeedbackMapper feedbackMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final MessageQueueProducer messageQueueProducer;

    @Value("message-feedback_topic${unique-name:}")
    private String feedbackTopic;

    @Override
    public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
        // 入口校验与同步写入保持一致，避免非法事件进入消息队列。
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .vote(vote)
                .reason(request.getReason())
                .comment(request.getComment())
                .submitTime(System.currentTimeMillis())
                .build();
        // 同一用户对同一消息使用相同业务键，便于 MQ 有序和消费幂等诊断。
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "消息反馈", event);
    }

    @Override
    public void submitFeedback(String messageId, MessageFeedbackRequest request) {
        // 消费端或同步场景都会走此路径，最终写入前必须再次校验消息归属和角色。
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));

        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(messageId, userId, message.getConversationId(),
                vote, request.getReason(), request.getComment(), System.currentTimeMillis());
    }

    @Override
    public Map<String, Integer> getUserVotes(String userId, List<String> messageIds) {
        if (StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        // 一次批量查询供消息列表回填，避免按每条助手消息查询反馈。
        List<MessageFeedbackDO> records = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
                        .in(MessageFeedbackDO::getMessageId, messageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyMap();
        }
        return records.stream()
                .collect(Collectors.toMap(
                        MessageFeedbackDO::getMessageId,
                        MessageFeedbackDO::getVote,
                        (first, second) -> first
                ));
    }

    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        // 用户 ID 条件既校验消息存在，也防止给其他用户的消息提交反馈。
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        Assert.isTrue("assistant".equalsIgnoreCase(message.getRole()), () -> new ClientException("仅支持对助手消息反馈"));
        return message;
    }

    private void doUpsertFeedback(String messageId, String userId, String conversationId,
                                  Integer vote, String reason, String comment, long submitTime) {
        MessageFeedbackDO existing = feedbackMapper.selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, messageId)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
        );

        if (existing == null) {
            // 首次评价插入一条记录。
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(messageId)
                    .conversationId(conversationId)
                    .userId(userId)
                    .vote(vote)
                    .reason(reason)
                    .comment(comment)
                    .build();
            feedbackMapper.insert(feedback);
        } else {
            // 仅当本次提交时间晚于记录最后更新时间时才覆盖，避免多节点并行消费乱序。
            feedbackMapper.update(
                    MessageFeedbackDO.builder()
                            .vote(vote)
                            .reason(reason)
                            .comment(comment)
                            .build(),
                    Wrappers.lambdaUpdate(MessageFeedbackDO.class)
                            .eq(MessageFeedbackDO::getId, existing.getId())
                            .lt(MessageFeedbackDO::getUpdateTime, new Date(submitTime))
            );
        }
    }

    @Override
    public void submitFeedbackByEvent(MessageFeedbackEvent event) {
        // 事件携带提交时刻，写入逻辑据此抵御重复投递和乱序到达。
        String messageId = event.getMessageId();
        String userId = event.getUserId();
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notBlank(userId, () -> new ClientException("用户ID不能为空"));
        Assert.notNull(event.getVote(), () -> new ClientException("反馈值不能为空"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(
                messageId,
                userId,
                message.getConversationId(),
                event.getVote(),
                event.getReason(),
                event.getComment(),
                event.getSubmitTime())
        ;
    }
}
