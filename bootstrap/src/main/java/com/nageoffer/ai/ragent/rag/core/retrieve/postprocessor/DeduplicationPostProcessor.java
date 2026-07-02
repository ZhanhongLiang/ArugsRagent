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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重后置处理器
 * <p>
 * 合并多个通道的结果并去重
 * 当同一个 Chunk 在多个通道中出现时，保留分数最高的
 */
@Slf4j
@Component
/**
 * 检索结果去重后处理器。
 *
 * <p>多通道召回可能命中同一文档分块，这里按 chunk/document 等稳定标识去重，
 * 避免 Prompt 中重复引用同一段知识。</p>
 */
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 1;  // 最先执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;  // 始终启用
    }

    /**
     * 注意这里有一个细节：去重处理器没有用 `chunks` 入参（打平后的列表），而是直接从 `results`（按通道分组的原始结果）入手。
     * 因为去重需要按通道优先级处理，而 `chunks` 已经丢失了通道归属信息。这就是 `process()`
     * 方法同时传 `chunks` 和 `results` 两个参数的原因——不同处理器需要不同的数据视图。
     * @param chunks  当前的 Chunk 列表（可能是上一个处理器的输出）
     * @param results 原始的多通道检索结果（用于获取元信息）
     * @param context 检索上下文
     * @return
     */
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 使用 LinkedHashMap 保持顺序并去重
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

        // 按通道优先级排序（优先级高的通道结果优先保留）
        results.stream()
                .sorted((r1, r2) -> Integer.compare(
                        getChannelPriority(r1.getChannelType()),
                        getChannelPriority(r2.getChannelType())
                ))
                .forEach(result -> {
                    for (RetrievedChunk chunk : result.getChunks()) {
                        String key = generateChunkKey(chunk);

                        if (!chunkMap.containsKey(key)) {
                            // 新 Chunk，直接添加
                            chunkMap.put(key, chunk);
                        } else {
                            // 已存在，合并分数（取最高分）
                            RetrievedChunk existing = chunkMap.get(key);
                            if (chunk.getScore() > existing.getScore()) {
                                chunkMap.put(key, chunk);
                            }
                        }
                    }
                });

        return new ArrayList<>(chunkMap.values());
    }

    /**
     * - 有 ID 用 ID**：Milvus 的 primary key 是唯一的，同一篇文档在不同通道检索出来的 ID 相同。这是最可靠的去重依据
     * - **无 ID 用内容哈希**：`text.hashCode()` 作为兜底。理论上不会走到这条路径（正常检索结果都有 ID），但防御式编程总是好习惯
     * 生成 Chunk 唯一键
     */
    private String generateChunkKey(RetrievedChunk chunk) {
        // 基于 id 或内容哈希生成唯一键
        return chunk.getId() != null
                ? chunk.getId()
                : String.valueOf(chunk.getText().hashCode());
    }

    /**
     * 获取通道优先级（数字越小优先级越高）
     */
    private int getChannelPriority(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;   // 意图检索优先级最高
            case KEYWORD_ES -> 2;        // 关键词检索次之
            case VECTOR_GLOBAL -> 3;     // 全局检索最低
            default -> 99;
        };
    }
}
