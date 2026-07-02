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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议 EmbeddingClient 抽象基类。
 *
 * <p>第 58 篇的核心就是这个模板方法：Ollama、SiliconFlow、AIHubMix 都走 OpenAI 风格
 * {@code /v1/embeddings} 协议，请求体结构是 model + input + dimensions，响应体是 data[].embedding。
 * 基类封装 HTTP 调用、错误处理、响应解析和批量分片，子类只通过钩子表达差异。</p>
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    // Embedding 只有同步调用，不需要流式客户端；所有请求都走这个 OkHttpClient。
    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 是否要求 API Key。
     *
     * <p>云厂商默认需要 Authorization: Bearer；Ollama 本地服务覆写为 false。</p>
     */
    protected boolean requiresApiKey() {
        return true;
    }

    /**
     * 请求体定制钩子。
     *
     * <p>默认添加 {@code encoding_format=float}，让兼容 OpenAI 的云厂商直接返回浮点数组而不是 base64。
     * Ollama 不需要这个字段，所以子类会覆写为空实现。</p>
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        body.addProperty("encoding_format", "float");
    }

    /**
     * 单次请求最大文本条数。
     *
     * <p>0 表示不分片；SiliconFlow/AIHubMix 这类有批量上限的供应商覆写为 32，
     * 基类会自动拆分并按原始顺序回填结果。</p>
     */
    protected int maxBatchSize() {
        return 0;
    }

    // ==================== 接口实现 ====================

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        // 单条向量化复用批量请求路径，保证请求构建和响应解析只有一套实现。
        List<List<Float>> result = doEmbed(List.of(text), target);
        return result.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        // 空批次直接返回空列表，避免向供应商发送无意义请求。
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }
        int batch = maxBatchSize();
        if (batch <= 0 || texts.size() <= batch) {
            // 没有批量上限或未超过上限时，一次 HTTP 请求完成。
            return doEmbed(texts, target);
        }

        // 预分配占位列表，分片结果按全局下标 set 回去，确保输出顺序与输入 texts 完全一致。
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += batch) {
            int end = Math.min(i + batch, n);
            // 当前分片，例如 70 条按 32 拆成 32 + 32 + 6。
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = doEmbed(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }
        return results;
    }

    // ==================== 模板方法：核心请求逻辑 ====================

    /**
     * Embedding 模板方法：构建请求、发送 HTTP、解析 OpenAI 格式响应。
     */
    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        // 1. 校验 provider 配置，获取 baseUrl、endpoint、apiKey 等运行时信息。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 2. 解析 embedding endpoint：candidate.url 优先，否则 provider.url + endpoints.embedding。
        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        JsonObject body = new JsonObject();
        // 3. model 来自 YAML candidate.model。
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);
        // 4. dimension 是模型级配置，经 ModelCandidate 传到请求体，控制向量维度。
        body.addProperty("dimensions", target.candidate().getDimension());
        // 5. 供应商差异字段由钩子追加，例如 encoding_format=float。
        customizeRequestBody(body, target);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON));
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request request = requestBuilder.build();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            // 6. HTTP 非 2xx 转换成 ModelClientException，交给路由执行器触发 fallback。
            if (!response.isSuccessful()) {
                String errBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} embedding 请求失败: status={}, body={}", provider(), response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            json = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " embedding 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 7. 某些供应商会 HTTP 200 但响应体含 error，必须额外识别业务错误。
        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " embedding 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        // 8. OpenAI embedding 响应主体是 data 数组，每个元素包含 embedding 浮点数组。
        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(
                    provider() + " embedding 响应中缺少 data 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<List<Float>> results = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null || emb.isEmpty()) {
                throw new ModelClientException(
                        provider() + " embedding 响应中缺少 embedding 字段",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(emb.size());
            for (JsonElement v : emb) {
                // Json 数字逐个转成 Float，组成向量库可写入的向量。
                vector.add(v.getAsFloat());
            }
            results.add(vector);
        }

        return results;
    }
}
