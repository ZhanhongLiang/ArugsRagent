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

package com.nageoffer.ai.ragent.framework.mq.producer;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 RocketMQ 的消息生产者
 */
@Slf4j
@RequiredArgsConstructor
public class RocketMQProducerAdapter implements MessageQueueProducer {

    /** Spring 对 RocketMQ 生产者的封装，负责普通消息和事务消息的实际发送。 */
    private final RocketMQTemplate rocketMQTemplate;
    /** 保存本地事务回调和回查器，使发送适配器与具体业务事务解耦。 */
    private final DelegatingTransactionListener transactionListener;

    @Override
    public SendResult send(String topic, String keys, String bizDesc, Object body) {
        // 调用方没有提供业务键时生成 UUID，便于 RocketMQ 查询与问题排查。
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        // 统一包装消息体，并将业务键写入 RocketMQ 标准 Header。
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();

        SendResult sendResult;
        try {
            // 同步等待 Broker 返回发送结果；失败时由异常直接交给调用方感知。
            sendResult = rocketMQTemplate.syncSend(topic, message);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 发送结果: {}, 消息ID: {}, Keys: {}", bizDesc, sendResult.getSendStatus(), sendResult.getMsgId(), keys);
        return sendResult;
    }

    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        // 事务消息同样需要稳定业务键，以便定位同一业务事件。
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        // 每次发送生成独立 txId，用于把半消息和本地事务回调一一关联。
        String txId = UUID.randomUUID().toString();

        // 先登记回调：RocketMQ 发送半消息成功后会回调监听器执行这段本地事务。
        transactionListener.registerLocalTransaction(txId, localTransaction);
        // 构造半事务消息，Broker 通过 Header 找到回调与未来回查器。
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();

        TransactionSendResult sendResult;
        try {
            // 第一步：Broker 持久化半消息但暂不投递；随后 SDK 回调 executeLocalTransaction，
            // 监听器依据本地事务结果向 Broker 返回 COMMIT 或 ROLLBACK。
            sendResult = rocketMQTemplate.sendMessageInTransaction(topic, message, null);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 事务消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 事务消息发送结果: {}, 本地事务状态: {}, 消息ID: {}, Keys: {}",
                bizDesc, sendResult.getSendStatus(), sendResult.getLocalTransactionState(), sendResult.getMsgId(), keys);
    }
}
