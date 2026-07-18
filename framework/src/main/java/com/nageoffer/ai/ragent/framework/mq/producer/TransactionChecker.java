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

/**
 * RocketMQ 事务消息的本地事务回查端口，按 topic 注册到 {@link DelegatingTransactionListener}。
 * <p>
 * 回查时 Broker 可能将请求发送到任意实例，因此实现类必须基于消息内容（而非内存状态）查询 DB 判断本地事务是否已提交。
 */
public interface TransactionChecker {

    /**
     * 当 Broker 长时间未收到 commit/rollback 时，检查本地事务最终是否已提交。
     *
     * @param message 消息体，包含业务载荷，可从中提取业务参数查询 DB
     * @return true 表示本地事务已提交且消息可投递；false 表示已回滚或未提交，消息应丢弃
     */
    boolean check(MessageWrapper<?> message);
}
