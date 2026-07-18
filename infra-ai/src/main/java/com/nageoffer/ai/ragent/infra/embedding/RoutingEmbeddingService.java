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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 EmbeddingService 实现。
 *
 * <p>Embedding 和 Chat 使用同一套路由骨架：ModelSelector 选候选，ModelRoutingExecutor 遍历执行，
 * ModelHealthStore 负责熔断。但 Embedding 没有流式调用，所以不需要首包探测，
 * 单条和批量向量化都可以直接用 executeWithFallback。</p>
 */
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {

    /*
     * Embedding routing reuses the same model selection and circuit-breaker infrastructure as Chat.
     *
     * Important RAG constraint: stored document vectors and query vectors must be produced by
     * compatible embedding models. That is why the knowledge base can pin embeddingModel and call
     * embedBatch(texts, modelId).
     */

    // 根据 embedding 分组配置选择候选模型。
    private final ModelSelector selector;
    // 通用同步故障转移执行器。
    private final ModelRoutingExecutor executor;
    // provider -> EmbeddingClient 注册表，Spring 自动发现所有 EmbeddingClient 后在构造器中建立。
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients) {
        this.selector = selector;
        this.executor = executor;
        // 新增供应商只要注册 EmbeddingClient Bean，这里会自动纳入路由。
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }

    /**
     * 默认单条向量化：按配置候选列表故障转移。
     */
    @Override
    public List<Float> embed(String text) {
        // Default model route: choose available embedding candidates and fallback if the first provider fails.
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                // 候选列表已经按默认模型、优先级和熔断状态过滤排序。
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                // 实际调用供应商客户端；失败会由执行器捕获并切换下一个候选。
                (client, target) -> client.embed(text, target)
        );
    }

    /**
     * 指定模型单条向量化：候选列表只有一个目标，失败后不会切到其它模型。
     */
    @Override
    public List<Float> embed(String text, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                // 实际调用供应商客户端；失败会由执行器捕获并切换下一个候选。
                (client, target) -> client.embed(text, target)
        );
    }

    /**
     * 默认批量向量化：用于文档 Chunk 入库，返回顺序必须和输入 texts 一致。
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                // 候选列表已经按默认模型、优先级和熔断状态过滤排序。
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    /**
     * 指定模型批量向量化，适合查询向量必须和已有向量库模型保持一致的场景。
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        // Pinned model route: used when a knowledge base has already chosen an embedding model for its vector space.
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    /**
     * 根据 ModelTarget 中的 provider 找到具体供应商客户端。
     */
    private EmbeddingClient resolveClient(ModelTarget target) {
        return clientsByProvider.get(target.candidate().getProvider());
    }

    /**
     * 从当前可用候选中解析指定模型。
     */
    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Embedding 模型ID不能为空");
        }
        return selector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
    }
}
