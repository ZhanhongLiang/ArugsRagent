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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.IntentParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 意图定向检索通道
 * <p>
 * 基于意图识别结果，在特定知识库中进行定向检索
 * 这是最精确的检索方式，优先级最高
 */
@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final RetrieverService retrieverService;
    private final Executor innerRetrievalExecutor;

    public IntentDirectedSearchChannel(RetrieverService retrieverService,
                                       SearchChannelProperties properties,
                                       Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.innerRetrievalExecutor = innerRetrievalExecutor;
    }

    @Override
    public String getName() {
        return "IntentDirectedSearch";
    }

    @Override
    public int getPriority() {
        return 1;  // 最高优先级
    }

    /**
     * 判断意图定向检索通道是否激活。
     *
     * <p>只有同时满足“配置开启 + 本轮有意图结果 + 命中 KB 类型意图”时才返回 true。
     * 这样 MultiChannelRetrievalEngine 才会把该通道放入 enabledChannels 并并行调用 search(context)。</p>
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        // 第一层开关：配置文件关闭 intentDirected 时，该通道无条件跳过。
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return false;
        }

        // 第二层判断：没有任何意图结果时，定向检索没有目标知识库可搜。
        if (CollUtil.isEmpty(context.getIntents())) {
            return false;
        }

        // 第三层判断：只保留 KB 类型且分数达标的意图；命中后才真正激活定向检索。
        List<NodeScore> kbIntents = extractKbIntents(context);
        return CollUtil.isNotEmpty(kbIntents);
    }

    /**
     * 流程很清晰：提取 KB 意图 → 委托并行检索器 → 包装成 `SearchChannelResult` 返回。
     * 整个 `search()` 外面包了 try-catch——即使 `parallelRetriever`
     * 内部出了意料之外的异常，也返回空结果而不是让调用方崩溃。
     * @param context 检索上下文
     * @return
     */
    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // 提取 KB 意图
            List<NodeScore> kbIntents = extractKbIntents(context);

            if (CollUtil.isEmpty(kbIntents)) {
                log.warn("意图定向检索通道被启用，但未找到 KB 意图（不应该发生）");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.INTENT_DIRECTED)
                        .channelName(getName())
                        .chunks(List.of())
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            log.info("执行意图定向检索，识别出 {} 个 KB 意图", kbIntents.size());

            // 并行检索所有意图对应的知识库，需要并行检索意图对应的知识库！！
            int topKMultiplier = properties.getChannels().getIntentDirected().getTopKMultiplier(); // 获得TopK倍数
            List<RetrievedChunk> allChunks = retrieveByIntents(
                    context.getMainQuestion(),
                    kbIntents,
                    context.getTopK(),
                    topKMultiplier,
                    context.getAccessScope()
            );

            long latency = System.currentTimeMillis() - startTime;

            log.info("意图定向检索完成，检索到 {} 个 Chunk，耗时 {}ms",
                    allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .metadata(Map.of("intentCount", kbIntents.size()))
                    .build();

        } catch (Exception e) {
            log.error("意图定向检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED;
    }

    /**
     * 提取 KB 意图
     * 注意这里的最低分数线是 0.4，比第 6 篇意图分类阶段的 `INTENT_MIN_SCORE=0.35`
     * 高了一截。0.35 只是保留在意图列表里的底线——分数够低但不至于丢掉；0.4
     * 才是值得走一次定向检索的门槛。一次定向检索意味着一次向量数据库 IO，不能太随便。
     */
    private List<NodeScore> extractKbIntents(SearchContext context) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return NodeScoreFilters.kb(allScores, minScore).stream()
                .filter(score -> context.getAccessScope() == null
                        || context.getAccessScope().canReadCollection(score.getNode().getCollectionName()))
                .toList();
    }

    /**
     * 根据意图列表并行检索
     * `retrieveByIntents` 内部是 `IntentParallelRetriever`，它对每个 KB 意图做一件事：
     * **取 `IntentNode.collectionName`，在对应的 Milvus Collection 中做向量检索。**
     *
     * 回忆第 5 篇讲过的 `IntentNode` 字段——每个 KB 类叶子节点绑定了一个 `collectionName`（如 `kb_1997857139737882625`），
     * 这个 Collection 就是这个意图对应的知识库在向量数据库中的存储位置。意图定向检索的精确性就来自这里：
     * 不是搜全库，而是只搜命中意图绑定的那个 Collection。
     */
    private List<RetrievedChunk> retrieveByIntents(String question,
                                                   List<NodeScore> kbIntents,
                                                   int fallbackTopK,
                                                   int topKMultiplier,
                                                   KnowledgeAccessScope accessScope) {
        // 使用模板方法执行并行检索
        return new IntentParallelRetriever(
                retrieverService,
                innerRetrievalExecutor,
                accessScope,
                properties.getChannels().getIntentDirected().getSupplementalCollections(),
                properties.getChannels().getIntentDirected().getMetadataFilters()
        )
                .executeParallelRetrieval(question, kbIntents, fallbackTopK, topKMultiplier);
    }
}
