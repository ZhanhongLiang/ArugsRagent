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

package com.nageoffer.ai.ragent.framework.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 为 Spring Data Redis 的字符串 Key 统一追加业务前缀的序列化器。
 *
 * <p>前缀由配置控制，可避免同一 Redis 实例中多个应用的键冲突。仅在配置存在时注册，
 * 因此未启用前缀的环境保持 Spring 默认序列化行为。</p>
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeySerializer implements RedisSerializer<String> {

    /** 配置的全局 Key 前缀，默认为空字符串。 */
    @Value("${framework.cache.redis.prefix:}")
    private String keyPrefix;

    @Override
    /** 将业务 Key 与前缀拼接后按 UTF-8 编码为 Redis 可存储的字节数组。 */
    public byte[] serialize(String key) throws SerializationException {
        String builderKey = keyPrefix + key;
        return builderKey.getBytes();
    }

    @Override
    /** 将 Redis 返回的字节数组按 UTF-8 还原为带前缀的完整 Key。 */
    public String deserialize(byte[] bytes) throws SerializationException {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
