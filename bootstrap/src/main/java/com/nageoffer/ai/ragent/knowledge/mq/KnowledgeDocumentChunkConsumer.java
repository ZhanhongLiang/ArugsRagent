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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 文档分块事务消息的消费者。
 *
 * <p>HTTP 请求只完成状态 CAS 和事务消息提交；真正耗时的提取、切片、Embedding 与写库在此消费者线程执行，
 * 避免占住 Web 线程并允许 MQ 按至少一次语义重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-chunk_topic${unique-name:}",
        consumerGroup = "knowledge-document-chunk_cg${unique-name:}"
)
public class KnowledgeDocumentChunkConsumer implements RocketMQListener<MessageWrapper<KnowledgeDocumentChunkEvent>> {

    /** 执行文档重建的业务服务。 */
    private final KnowledgeDocumentService documentService;

    @Override
    /**
     * 消费一条已提交的分块事件。
     * MQ 线程没有 Web 请求上下文，因此先从事件恢复操作人到 UserContext，供审计字段和日志使用，最后必须清理避免线程复用污染。
     */
    public void onMessage(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        // MQ 是分块的异步边界：HTTP 线程仅切状态并发事件，消费者承担重任务。
        KnowledgeDocumentChunkEvent event = message.getBody();

        log.info("[消费者] 开始消费文档分块任务，docId={}, keys={}", event.getDocId(), message.getKeys());

        // MQ 消费运行在独立线程，没有 Web UserContext，必须从事件载荷重建操作人。
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
