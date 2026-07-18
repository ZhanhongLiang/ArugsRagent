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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * MQ 消费幂等记录的轻量状态机。
 * CONSUMING 防止并发消费者同时执行，CONSUMED 表示业务副作用已经完成。
 */
@RequiredArgsConstructor
public enum IdempotentConsumeStatusEnum {

    /** 已抢到幂等键、业务方法正在执行；重入时需要拒绝或稍后重试。 */
    CONSUMING("0"),

    /** 业务方法已成功完成；重复消息可直接跳过。 */
    CONSUMED("1");

    @Getter
    private final String code;

    /**
     * 判断当前状态是否仍在消费中。
     *
     * @param consumeStatus 消费状态
     * @return true 表示其他线程或实例尚在执行该消息，当前调用不能重复处理
     */
    public static boolean isError(String consumeStatus) {
        return Objects.equals(CONSUMING.code, consumeStatus);
    }
}
