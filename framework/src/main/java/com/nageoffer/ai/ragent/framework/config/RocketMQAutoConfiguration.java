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

package com.nageoffer.ai.ragent.framework.config;

import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.mq.producer.RocketMQProducerAdapter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产端的统一自动装配。
 *
 * <p>业务模块只依赖 {@link MessageQueueProducer}，不直接接触 RocketMQTemplate；
 * 普通消息和半事务消息的技术细节因此都被适配层收敛。</p>
 */
@Configuration
public class RocketMQAutoConfiguration {

    /** @return 维护业务本地事务回调与 Broker 回查逻辑的委派监听器。 */
    @Bean
    public DelegatingTransactionListener delegatingTransactionListener() {
        return new DelegatingTransactionListener();
    }

    /**
     * 将 Spring RocketMQTemplate 包装为项目自己的生产端端口。
     *
     * @param rocketMQTemplate RocketMQ Spring 集成提供的底层模板
     * @param transactionListener 半事务消息的本地事务监听器
     * @return 面向业务层的消息生产接口
     */
    @Bean
    public MessageQueueProducer messageQueueProducer(RocketMQTemplate rocketMQTemplate,
                                                     DelegatingTransactionListener transactionListener) {
        return new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);
    }
}
