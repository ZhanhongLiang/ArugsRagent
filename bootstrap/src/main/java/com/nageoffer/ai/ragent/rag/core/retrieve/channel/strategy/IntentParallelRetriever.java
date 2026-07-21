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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 按意图节点并发执行向量检索的策略。
 *
 * <p>意图节点绑定的 collectionName 是路由结果，不能用一个全局集合替代；
 * 本类把每个命中的知识库意图转换为独立任务，再由父类统一并发、容错和汇总。</p>
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final RetrieverService retrieverService;
    private final KnowledgeAccessScope accessScope;

    /**
     * 单个意图的检索任务。
     * intentTopK 在提交前已计算完成，避免异步线程读取变化中的配置或重复计算。
     */
    public record IntentTask(NodeScore nodeScore, String collectionName, int intentTopK,
                             Map<String, Object> metadataFilters) {
    }

    public IntentParallelRetriever(RetrieverService retrieverService,
                                   Executor executor,
                                   KnowledgeAccessScope accessScope,
                                   Map<String, List<String>> supplementalCollections,
                                   Map<String, Map<String, Object>> metadataFilters) {
        super(executor);
        this.retrieverService = retrieverService;
        this.accessScope = accessScope;
        this.supplementalCollections = supplementalCollections == null ? Map.of() : supplementalCollections;
        this.metadataFilters = metadataFilters == null ? Map.of() : metadataFilters;
    }

    private final Map<String, List<String>> supplementalCollections;
    private final Map<String, Map<String, Object>> metadataFilters;

    /**
     * 为每个意图计算召回数量后并发检索。
     *
     * <p>节点自身配置了 TopK 时优先使用节点值；否则回退到请求级 TopK。
     * 倍率用于先多召回候选，留给后置去重和重排序阶段筛选，而不是最终直接输出这么多片段。</p>
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<NodeScore> targets,
                                                         int fallbackTopK,
                                                         int topKMultiplier) {
        List<IntentTask> intentTasks = targets.stream()
                .flatMap(nodeScore -> resolveCollections(nodeScore).stream()
                        .filter(this::canReadCollection)
                        .map(collectionName -> new IntentTask(
                                nodeScore,
                                collectionName,
                                resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier),
                                resolveMetadataFilters(nodeScore)
                        )))
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, fallbackTopK);
    }
    /**
     * 在一个意图绑定的集合中执行实际检索。
     *
     * <p>这里捕获异常并返回空列表，是为了将单个集合不可用降级为局部召回缺失，
     * 而不是让所有并行子任务和整次问答一起失败。</p>
     *
     * @param question 用户问题或重写后的检索语句
     * @param task 已确定集合和召回数量的意图任务
     * @param ignoredTopK 父类模板参数；实际数量以 task.intentTopK 为准
     * @return 当前意图集合召回到的文档分块
     */
    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        try {
            // collectionName 将语义意图映射到实际向量集合，形成定向检索边界。
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(task.collectionName())
                            .query(question)
                            .topK(task.intentTopK())
                            .metadataFilters(task.metadataFilters())
                            .accessScope(accessScope)
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                    node.getId(), node.getName(), task.collectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(IntentTask task) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        return String.format("意图ID: %s, 意图名称: %s", node.getId(), node.getName());
    }

    @Override
    protected String getStatisticsName() {
        return "意图检索";
    }

    /**
     * 计算单个意图的候选召回数。
     * 节点级配置用于让信息密集或容易混淆的知识库获得更多候选；非法倍率回退，避免返回零条结果。
     */
    private int resolveIntentTopK(NodeScore nodeScore, int fallbackTopK, int topKMultiplier) {
        int baseTopK = fallbackTopK;
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                baseTopK = nodeTopK;
            }
        }

        if (topKMultiplier <= 0) {
            log.warn("意图定向通道倍率配置异常: {}, 使用基础 TopK: {}", topKMultiplier, baseTopK);
            return baseTopK;
        }

        return baseTopK * topKMultiplier;
    }

    private Collection<String> resolveCollections(NodeScore nodeScore) {
        if (nodeScore == null || nodeScore.getNode() == null) {
            return List.of();
        }
        IntentNode node = nodeScore.getNode();
        Set<String> collections = new LinkedHashSet<>();
        addIfPresent(collections, node.getCollectionName());
        List<String> supplements = supplementalCollections.get(node.getId());
        if (supplements != null) {
            supplements.forEach(collectionName -> addIfPresent(collections, collectionName));
        }
        return new ArrayList<>(collections);
    }

    private boolean canReadCollection(String collectionName) {
        return accessScope == null || accessScope.canReadCollection(collectionName);
    }

    private Map<String, Object> resolveMetadataFilters(NodeScore nodeScore) {
        if (nodeScore == null || nodeScore.getNode() == null) {
            return Map.of();
        }
        Map<String, Object> filters = metadataFilters.get(nodeScore.getNode().getId());
        return filters == null ? Map.of() : new java.util.LinkedHashMap<>(filters);
    }

    private void addIfPresent(Set<String> collections, String collectionName) {
        if (collectionName != null && !collectionName.isBlank()) {
            collections.add(collectionName);
        }
    }
}
