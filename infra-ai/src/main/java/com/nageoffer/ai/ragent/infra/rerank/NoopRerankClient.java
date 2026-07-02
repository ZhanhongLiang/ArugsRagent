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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Noop Rerank 客户端，空对象模式的安全兜底。
 *
 * <p>当真实 Rerank 服务未配置、限流或熔断时，路由层可以 fallback 到 noop。
 * 它不发 HTTP 请求，只按原始召回顺序截取 topN，让 RAG 功能可用但排序质量降级。</p>
 */
@Service
public class NoopRerankClient implements RerankClient {

    @Override
    /**
     * 返回特殊 provider=noop。ModelSelector 对 noop 允许没有 ProviderConfig，因为它不需要 URL/API Key。
     */
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    /**
     * 不做精排，直接按原始顺序截断前 `topN` 条。效果当然不如 Cross-Encoder 精排——排在前面的不一定是最相关的。
     * 但至少保证了流水线不中断，有结果总比没结果好。
     * @param query      用户查询文本
     * @param candidates 待排序的候选文档片段列表
     * @param topN       返回前N个最相关的结果
     * @param target     目标模型配置信息
     * @return
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 没有候选直接返回空列表。
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            // 不需要裁剪时保持原始召回顺序。
            return candidates;
        }
        // 简单截断前 topN 条，不改变分数，不做模型精排。
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}
