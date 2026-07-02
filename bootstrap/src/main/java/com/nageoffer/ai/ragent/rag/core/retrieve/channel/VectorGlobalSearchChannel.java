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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 向量全局检索通道
 */
/**
 * 全局向量检索通道。
 *
 * <p>当问题没有强绑定某个意图节点，或需要补充泛化语义结果时，
 * 该通道会直接在可访问知识库集合中做向量召回。</p>
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final CollectionParallelRetriever parallelRetriever;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     KnowledgeBaseMapper knowledgeBaseMapper,
                                     Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;  // 较低优先级
    }

    /**
     * 判断全局向量检索通道是否激活。
     *
     * <p>它不是永远执行，而是在“需要兜底或补充召回”时执行：例如定向检索关闭、没有识别出意图、
     * 意图置信度太低，或者只有一个中等置信度意图时。</p>
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        // 第一层开关：配置文件关闭 vectorGlobal 时，该通道无条件跳过。
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        // 兜底场景：如果意图定向检索整体关闭，全局检索必须接管，否则没有通道负责召回。
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return true;
        }

        // 汇总本轮所有子问题识别出来的意图分数，用于判断是否需要全局补充召回。
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        // 没有任何意图命中时，无法做定向知识库检索，直接启用全局向量检索。
        if (CollUtil.isEmpty(allScores)) {
            log.info("未识别出任何意图，启用全局检索");
            return true;
        }

        double maxScore = allScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        // 最高意图分低于阈值，说明路由不够可靠，需要全局检索扩大召回面。
        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("意图置信度过低（{}），启用全局检索", maxScore);
            return true;
        }

        // 只有一个中等置信度意图时，定向检索仍会执行，但再开全局检索做补充更稳。
        double supplementThreshold = properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
        if (allScores.size() == 1 && maxScore < supplementThreshold) {
            log.info("单一中等置信度意图（{}），启用补充全局检索", maxScore);
            return true;
        }

        // 意图足够明确时不启用全局检索，避免无意义地搜索所有知识库。
        return false;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            // 获取所有 KB 类型的 collection
            List<String> collections = getAllKBCollections();

            if (collections.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // 并行在所有 collection 中检索
            int topKMultiplier = properties.getChannels().getVectorGlobal().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveFromAllCollections(
                    context.getMainQuestion(),
                    collections,
                    context.getTopK() * topKMultiplier
            );

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 获取所有 KB 类型的 collection
     */
    private List<String> getAllKBCollections() {
        Set<String> collections = new HashSet<>();

        // 从知识库表获取全量 collection（全局检索兜底）
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        for (KnowledgeBaseDO kb : kbList) {
            String collectionName = kb.getCollectionName();
            if (collectionName != null && !collectionName.isBlank()) {
                collections.add(collectionName);
            }
        }

        return new ArrayList<>(collections);
    }

    /**
     * 并行在所有 collection 中检索
     */
    private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                            List<String> collections,
                                                            int topK) {
        // 使用模板方法执行并行检索
        return parallelRetriever.executeParallelRetrieval(question, collections, topK);
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}
