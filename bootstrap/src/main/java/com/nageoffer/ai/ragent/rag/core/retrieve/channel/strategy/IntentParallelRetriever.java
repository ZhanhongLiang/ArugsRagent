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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final RetrieverService retrieverService;

    public record IntentTask(NodeScore nodeScore, int intentTopK) {
    }

    public IntentParallelRetriever(RetrieverService retrieverService,
                                   Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    /**
     * 执行并行检索（重载方法，支持动态 TopK 计算）
     *     /**
     *      * 根据意图列表并行检索
     *      * `retrieveByIntents` 内部是 `IntentParallelRetriever`，它对每个 KB 意图做一件事：
     *      * **取 `IntentNode.collectionName`，在对应的 Milvus Collection 中做向量检索。**
     *      *
     *      * 回忆第 5 篇讲过的 `IntentNode` 字段——每个 KB 类叶子节点绑定了一个 `collectionName`（如 `kb_1997857139737882625`），
     *      * 这个 Collection 就是这个意图对应的知识库在向量数据库中的存储位置。意图定向检索的精确性就来自这里：
     *      * 不是搜全库，而是只搜命中意图绑定的那个 Collection。
     *
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<NodeScore> targets,
                                                         int fallbackTopK,
                                                         int topKMultiplier) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier) // 计算TopK * 倍数
                ))
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, fallbackTopK);
    }
    /**
     * 创建单个检索任务（子类实现）
     * 注意：此方法内部应包含异常处理，失败时返回空列表
     *
     * @param question 查询问题
     * @param task   检索目标
     * @param ignoredTopK     TopK
     * @return 检索结果列表
     */
    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        try {
            // 进行PG向量检索
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(node.getCollectionName())
                            .query(question)
                            .topK(task.intentTopK())
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                    node.getId(), node.getName(), node.getCollectionName(), e.getMessage(), e);
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
     * 计算单个意图节点检索 TopK
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
}
