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

package com.nageoffer.ai.ragent.framework.distributedid;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 分布式 Snowflake 初始化器
 * 从 Redis 获取 workerId 和 datacenterId，并注册到 Hutool 的 IdUtil 中
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdInitializer {

    /** 执行 Redis Lua 脚本，为每个服务实例分配稳定的 workerId/datacenterId。 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Spring Bean 创建完成后初始化全局 Snowflake 生成器。
     *
     * <p>多实例环境不能让所有节点使用同一个 workerId，否则高并发下可能产生重复 ID；
     * Lua 脚本负责原子分配，Hutool Singleton 保存生成器供 MyBatis-Plus 调用。</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @PostConstruct
    public void init() {
        // 加载类路径中的 Lua 脚本，脚本会在 Redis 内原子分配两个机器维度编号。
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/snowflake_init.lua")));
        script.setResultType(List.class);

        try {
            // 执行脚本并期望返回 [workerId, datacenterId] 两个 Long 值。
            List<Long> result = stringRedisTemplate.execute(script, Collections.emptyList());

            if (CollUtil.isEmpty(result) || result.size() != 2) {
                // 返回结构不符合约定时宁可启动失败，避免使用错误 ID 维度继续运行。
                throw new RuntimeException("从Redis获取WorkerId和DataCenterId失败");
            }

            Long workerId = result.get(0);
            Long datacenterId = result.get(1);

            // 将实例维度注册到 Hutool 单例；CustomIdentifierGenerator 后续从这里生成 ID。
            Snowflake snowflake = new Snowflake(workerId, datacenterId);
            Singleton.put(snowflake);

            log.info("分布式Snowflake初始化完成, workerId: {}, datacenterId: {}", workerId, datacenterId);
        } catch (Exception e) {
            // ID 发生器不可用会影响所有持久化写入，因此阻止应用带病启动。
            throw new RuntimeException("分布式Snowflake初始化失败", e);
        }
    }
}
