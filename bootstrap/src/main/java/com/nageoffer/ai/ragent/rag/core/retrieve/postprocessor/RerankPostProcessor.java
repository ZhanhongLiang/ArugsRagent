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
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
/**
 * 检索结果重排序后处理器。
 *
 * <p>向量召回负责扩大候选范围，rerank 负责按问题相关性重新排序，
 * 最终只把更相关的分块送入 Prompt。</p>
 * - getOrder() = 10`**：在去重（order=1）之后执行。去重先把重复清掉，精排再对无重复的候选集统一打分
 * - 2.
 *   **委托 `RerankService`**：处理器本身不关心用哪个 Rerank 模型、调哪家 API。它只负责把参数传进去，
 *   具体的模型调用交给 `RerankService`——职责分离
 * - 3.
 *   **一步完成精排 + 截断**：`context.getTopK()` 直接传给 `rerank()` 方法，
 *   Rerank 服务在打分的同时就做了 topK 截断，输出就是最终要喂给大模型的 Chunk 列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 最后执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getRerankEnabled();
    }

    /**
     * 意图定向的 0.82 和 ES 的 12.5 放在一起排序没有任何意义——一个是相似度百分比，一个是词频权重的加权和，
     * 量纲都不一样。直接按分数排序可能把一条 BM25 分数高但语义不相关的 Chunk 排到最前面。
     *
     * 需要一个统一的打分标准，让所有 Chunk 在同一个尺度上重新排序——这就是 Rerank 模型要做的事。
     * @param chunks  当前的 Chunk 列表（可能是上一个处理器的输出）
     * @param results 原始的多通道检索结果（用于获取元信息）
     * @param context 检索上下文
     * @return
     */
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }
        // 委托rerankService的rerank进行额外重排
        return rerankService.rerank(
                context.getMainQuestion(),
                chunks,
                context.getTopK()
        );
    }
}
