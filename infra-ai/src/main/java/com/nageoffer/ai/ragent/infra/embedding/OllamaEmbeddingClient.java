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

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * Ollama OpenAI 兼容 Embedding 客户端。
 *
 * <p>本地 Ollama 不需要 API Key，并且不需要 encoding_format=float 字段。</p>
 * <p>协议通用部分由 AbstractOpenAIStyleEmbeddingClient 处理，子类只声明 provider 和少量钩子差异。</p>
 */
@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public OllamaEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    /**
     * 返回供应商 id，用于 RoutingEmbeddingService 的 provider -> client 注册表。
     */
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    /**
     * Ollama 本地服务跳过 API Key 校验和 Authorization 请求头。
     */
    protected boolean requiresApiKey() {
        return false;
    }

    @Override
    /**
     * Ollama 不追加 encoding_format，保持请求体尽量贴近本地 OpenAI 兼容端点。
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // Ollama 不需要 encoding_format 字段
    }
}
