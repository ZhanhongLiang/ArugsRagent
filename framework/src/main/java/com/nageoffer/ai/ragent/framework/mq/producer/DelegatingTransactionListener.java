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

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用的 RocketMQ 事务消息监听器
 */
@Slf4j
@RocketMQTransactionListener
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {

    /** 半事务消息携带的本地事务注册 ID，用于定位本次发送对应的回调。 */
    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";
    /** 半事务消息携带的 Topic，用于 Broker 回查时选择正确的持久化检查器。 */
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    /**
     * 本地事务执行逻辑，per-message，仅当前实例有效
     */
    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();

    /**
     * 事务回查逻辑，per-topic，所有实例共享（Spring Bean 注册）
     */
    private final ConcurrentMap<String, TransactionChecker> checkerMap = new ConcurrentHashMap<>();

    @Autowired
    private PlatformTransactionManager transactionManager;

    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        // 发送半事务消息前登记一次性的本地事务回调，等待 executeLocalTransaction 消费。
        localTransactionMap.put(txId, localTransaction);
    }

    public void registerChecker(String topic, TransactionChecker checker) {
        // 回查器按 Topic 复用，因为 Broker 回查可能由任意生产者实例处理。
        checkerMap.put(topic, checker);
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        // 从半事务消息头取出发送时生成的事务上下文 ID。
        String txId = (String) message.getHeaders().get(HEADER_TX_ID);
        // remove 保证回调只执行一次，避免同一实例重复提交本地事务。
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        if (localTransaction == null) {
            // 找不到回调无法证明本地状态，保守回滚，Broker 不会向消费者投递。
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 用 Spring 事务模板包裹数据库更新、定时任务写入等业务动作。
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            // 本地事务提交成功后通知 Broker 提交半事务消息，消息才对消费者可见。
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            // 本地事务异常时通知 Broker 回滚半事务消息，避免出现“消息已消费但数据库未更新”。
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        // Broker 在提交结果未知或超时时发起回查，Topic 决定检查哪类业务状态。
        String topic = (String) message.getHeaders().get(HEADER_TOPIC);
        TransactionChecker checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            // 没有可信检查器时不能贸然提交，默认回滚保证不产生脏消息。
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 取回原始业务包装体，检查器据此查询数据库中的最终业务状态。
            MessageWrapper<?> wrapper = (MessageWrapper<?>) message.getPayload();
            boolean committed = checker.check(wrapper);
            // 只有持久化状态明确成功才提交；其余情况回滚。
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            // 临时数据库或网络异常时返回 UNKNOWN，让 Broker 按事务消息策略稍后重试回查。
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
