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
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 RerankService 实现。
 *
 * <p>Rerank 和 Chat/Embedding 一样复用 ModelSelector + ModelRoutingExecutor + ModelHealthStore：
 * 先选出 rerank 候选，再按 provider 找到 RerankClient，失败后自动 fallback。NoopRerankClient 通常作为最低优先级兜底。</p>
 */
@Service
@Primary
public class RoutingRerankService implements RerankService {

    /**
     * Rerank 虽是检索后处理能力，但路由方式与 Chat/Embedding 一致。
     * 真实精排模型用于改善召回 Chunk 的顺序；NOOP 候选则在供应商不可用时仅截断原始结果，保证 RAG 仍可回答。
     */

    // 选择 rerank 分组候选，包含真实 Rerank 模型和 noop 兜底候选。
    private final ModelSelector selector;
    // 通用同步故障转移执行器。
    private final ModelRoutingExecutor executor;
    // provider -> RerankClient 注册表。
    private final Map<String, RerankClient> clientsByProvider;

    public RoutingRerankService(ModelSelector selector, ModelRoutingExecutor executor, List<RerankClient> clients) {
        this.selector = selector;
        this.executor = executor;
        // Spring 自动注入所有 RerankClient，这里按 provider 建索引。
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    /**
     * 对检索召回候选做精排。
     *
     * <p>真实客户端失败时，执行器会标记失败并尝试下一个候选；如果配置了 noop，最终会退化为简单截断。</p>
     * - 1.
     *   **`ModelSelector.selectRerankCandidates()`**：从配置中选出可用的 Rerank 模型列表，按优先级排序。比如首选百炼（阿里云），备选 Noop（无操作兜底）
     * - 2.
     *   **`ModelRoutingExecutor.executeWithFallback()`**：按优先级依次尝试。首选模型调用成功就返回结果；如果首选模型超时或报错，自动切换到下一个候选
     * - 3.
     *   **`clientsByProvider`**：按供应商名称（`provider()`）索引到具体的 `RerankClient` 实现。构造函数通过 Spring 列表注入收集所有 `RerankClient` Bean，然后建立 provider → client 的映射
     *
     * 这个设计和第 10 篇多通道检索的容错策略一脉相承：单点故障不影响全局，能降级就降级。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        // 执行器负责供应商失败、三态熔断与 fallback；调用方只拿到当前可用的最佳排序结果。
        return executor.executeWithFallback(
                ModelCapability.RERANK,
                // 候选顺序由 priority 控制，真实 Rerank 通常优先，noop 放最后兜底。
                selector.selectRerankCandidates(),
                // 根据 provider 找到供应商客户端。
                target -> clientsByProvider.get(target.candidate().getProvider()),
                // 实际执行精排；异常会触发 fallback。
                (client, target) -> client.rerank(query, candidates, topN, target)
        );
    }
}
