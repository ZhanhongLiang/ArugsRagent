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
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 按向量集合并发检索的全局召回策略。
 *
 * <p>它不依赖意图节点，而是对已筛出的所有知识库集合分别检索；
 * 因此适合意图缺失、置信度不足或需要扩大召回范围的兜底场景。</p>
 */
@Slf4j
public class CollectionParallelRetriever extends AbstractParallelRetriever<String> {

    private final RetrieverService retrieverService;
    private final KnowledgeAccessScope accessScope;

    public CollectionParallelRetriever(RetrieverService retrieverService,
                                       Executor executor,
                                       KnowledgeAccessScope accessScope) {
        super(executor);
        this.retrieverService = retrieverService;
        this.accessScope = accessScope;
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, String collectionName, int topK) {
        try {
            // 每个集合独立失败可降级为空结果，父类仍会合并其他可用集合的结果。
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(collectionName)
                            .query(question)
                            .topK(topK)
                            .accessScope(accessScope)
                            .build()
            );
        } catch (Exception e) {
            log.error("在 collection {} 中检索失败，错误: {}", collectionName, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(String collectionName) {
        // 仅用于失败日志定位，不参与向量库查询。
        return "Collection: " + collectionName;
    }

    @Override
    protected String getStatisticsName() {
        return "全局检索";
    }
}
