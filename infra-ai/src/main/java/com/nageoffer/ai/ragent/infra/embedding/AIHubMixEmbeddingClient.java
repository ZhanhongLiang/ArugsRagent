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

import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * AIHubMix Embedding 客户端。
 *
 * <p>AIHubMix 走 OpenAI 兼容协议，当前也按 32 条做批量分片。</p>
 * <p>协议通用部分由 AbstractOpenAIStyleEmbeddingClient 处理，子类只声明 provider 和少量钩子差异。</p>
 */
@Service
public class AIHubMixEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public AIHubMixEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    /**
     * 返回供应商 id，用于 RoutingEmbeddingService 的 provider -> client 注册表。
     */
    public String provider() {
        return ModelProvider.AI_HUB_MIX.getId();
    }

    @Override
    /**
     * 单次批量上限，基类会自动按这个大小拆片并回填结果。
     */
    protected int maxBatchSize() {
        return 32;
    }
}
