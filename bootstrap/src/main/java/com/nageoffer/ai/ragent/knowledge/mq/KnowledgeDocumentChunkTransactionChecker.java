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

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档分块 RocketMQ 事务消息的本地事务回查器。
 *
 * <p>Broker 未收到明确 commit/rollback 时可能把回查请求发送到任意应用实例，因此不能依赖发送实例内存状态；
 * 本实现以文档状态已被 CAS 更新为 RUNNING 作为“本地事务已提交”的持久化事实。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkTransactionChecker implements TransactionChecker {

    /** 查询文档持久化状态，作为回查的跨实例事实来源。 */
    private final KnowledgeDocumentMapper documentMapper;
    /** 按 topic 保存回查器注册表的委派监听器。 */
    private final DelegatingTransactionListener transactionListener;

    /** 与生产者和消费者一致的事务消息 topic。 */
    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    /** Spring 注入完成后把本回查器注册到对应 topic，供 DelegatingTransactionListener 路由。 */
    @PostConstruct
    public void init() {
        transactionListener.registerChecker(chunkTopic, this);
    }

    @Override
    /**
     * 根据事件中的 docId 查询数据库；文档存在且为 RUNNING 即允许 Broker 提交半消息。
     * 其它状态表示本地 CAS 未成功或已经回滚，返回 false 让 Broker 丢弃消息，避免无效分块任务。
     */
    public boolean check(MessageWrapper<?> message) {
        log.info("[事务回查] 文档分块，消息体：{}", JSONUtil.toJsonStr(message));

        // 生产端保证该 topic 的 body 是 KnowledgeDocumentChunkEvent，取出 docId 作为回查键。
        KnowledgeDocumentChunkEvent event = (KnowledgeDocumentChunkEvent) message.getBody();
        String docId = event.getDocId();
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);

        return documentDO != null
                && DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus());
    }
}
