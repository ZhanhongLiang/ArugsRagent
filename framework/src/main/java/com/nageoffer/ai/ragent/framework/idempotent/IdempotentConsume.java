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

package com.nageoffer.ai.ragent.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 MQ 消费方法的幂等性。
 *
 * <p>Broker 至少一次投递和网络重试都会让同一消息多次到达；消费切面使用 key 与状态机保证
 * 已成功处理的消息不重复执行业务副作用。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsume {

    /** 防重 Redis Key 前缀，用于隔离不同消费者的命名空间。 */
    String keyPrefix() default "";

    /** 用 SpEL 从消息载荷中提取的唯一业务键。 */
    String key();

    /** 防重状态过期时间（秒）；应覆盖消息可能重复投递的时间窗口。 */
    long keyTimeout() default 3600L;
}
