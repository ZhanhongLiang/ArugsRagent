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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 意图树的 Redis 缓存管理器。
 *
 * <p>分类每次都查询数据库会增加延迟，因此缓存完整树；管理端修改节点后主动删除缓存，
 * 下次分类再按“缓存未命中 -> 数据库加载 -> 回填缓存”的路径获得新版本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentTreeCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 共享意图树的固定缓存键；保留技术前缀以兼容已有运行环境。 */
    private static final String INTENT_TREE_CACHE_KEY = "ragent:intent:tree";

    /** 缓存兜底过期时间，防止节点更新漏删缓存时永久读取旧树。 */
    private static final long CACHE_EXPIRE_DAYS = 7;

    /**
     * 从Redis获取意图树缓存
     *
     * @return 意图树根节点列表，如果缓存不存在则返回null
     */
    public List<IntentNode> getIntentTreeFromCache() {
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(INTENT_TREE_CACHE_KEY);
            if (cacheJson == null) {
                // 返回 null 而非空集合：调用方据此区分“缓存未命中”和“确实没有节点”。
                log.info("意图树缓存不存在，需要从数据库加载");
                return null;
            }

            // 反序列化后保留父子嵌套结构，分类器可直接展开叶子节点。
            return objectMapper.readValue(
                    cacheJson,
                    new TypeReference<>() {
                    }
            );
        } catch (Exception e) {
            log.error("从Redis读取意图树缓存失败", e);
            return null;
        }
    }

    /**
     * 将意图树保存到Redis缓存
     *
     * @param roots 意图树根节点列表
     */
    public void saveIntentTreeToCache(List<IntentNode> roots) {
        try {
            // 一次写入完整树，读取无需多次 Redis 往返再拼装父子关系。
            String cacheJson = objectMapper.writeValueAsString(roots);
            stringRedisTemplate.opsForValue().set(
                    INTENT_TREE_CACHE_KEY,
                    cacheJson,
                    CACHE_EXPIRE_DAYS,
                    TimeUnit.DAYS
            );
            log.info("意图树已保存到Redis缓存，根节点数: {}", roots.size());
        } catch (Exception e) {
            log.error("保存意图树到Redis缓存失败", e);
        }
    }

    /**
     * 清除意图树缓存
     * 在意图节点发生增删改时调用
     */
    public void clearIntentTreeCache() {
        // 删除而不是同步重建，避免管理写请求承担数据库加载和序列化延迟。
        Boolean deleted = stringRedisTemplate.delete(INTENT_TREE_CACHE_KEY);
        if (deleted) {
            log.info("意图树缓存已清除，Key: {}", INTENT_TREE_CACHE_KEY);
        } else {
            log.info("意图树缓存不存在，无需清除");
        }
    }

    /**
     * 检查缓存是否存在
     *
     * @return true表示缓存存在，false表示不存在
     */
    public boolean isCacheExists() {
        try {
            // 仅供管理或诊断使用，不应依据此结果跳过实际读取。
            return stringRedisTemplate.hasKey(INTENT_TREE_CACHE_KEY);
        } catch (Exception e) {
            log.error("检查意图树缓存是否存在失败", e);
            return false;
        }
    }
}
