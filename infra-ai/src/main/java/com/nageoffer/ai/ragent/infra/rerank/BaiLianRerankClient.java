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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 百炼 Rerank 客户端。
 *
 * <p>Rerank 用于在向量检索召回 TopK 后做精排，把更相关的 Chunk 排到前面。
 * 本实现包含第 59 篇提到的两个防御性策略：进入 API 前按 chunk id 去重，API 返回不足 topN 时从原始候选回填。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    @Qualifier("syncHttpClient")
    // Rerank 是同步 HTTP 调用，失败会抛 ModelClientException 给路由执行器触发 fallback。
    private final OkHttpClient httpClient;

    @Override
    /**
     * 返回百炼 provider id，用于 RoutingRerankService 注册表查找。
     */
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 没有候选就没有必要调用 Rerank API。
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 混合检索可能让同一个 Chunk 同时被向量和 BM25 命中，这里按 id 精确去重，避免浪费 Rerank token。
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            // 候选数量不超过目标数量时，精排不会减少结果，直接返回去重后的候选。
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    /**
     * 调用百炼 Rerank API，并把 output.results[index] 映射回原始 RetrievedChunk。
     * **`relevance_score` 覆写原始分数**：Rerank API 返回的 `relevance_score` 是 Cross-Encoder 对 query-chunk 对的统一相关性打分（通常在 0~1 之间）。
     * 不管这条 Chunk 原来的分数是余弦相似度 0.82 还是 BM25 分数 12.5，
     * 经过 Rerank 后都变成了同一标准下的相关性评分。这就解决了前面提到的分数不可比问题。
     *
     * **边解析边截断**：`reranked.size() >= topN` 时直接 `break`，
     * 不需要把 API 返回的所有结果都解析完再截断。Rerank API 本身返回的结果已经按相关性从高到低排好了，
     * 前 `topN` 条就是最相关的。
     *
     * **不足 topN 时用原始候选补齐**：如果 Rerank API 返回的结果不够 `topN`（比如 API 只返回了 3 条但 `topN` 是 5），
     * 用原始候选列表中未被选中的 Chunk 按原始顺序补齐。这个补齐策略保证输出数量尽量达标，
     * 不会因为 API 少返回了几条就让下游拿到不够数的结果。
     */
    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 1. 校验 provider 配置，百炼 Rerank 需要 URL 和 API Key。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        JsonObject reqBody = new JsonObject();
        // 2. 请求体顶层 model 是百炼 Rerank 模型名，如 gte-rerank。
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        JsonObject input = new JsonObject();
        // input.query 是用户问题。
        input.addProperty("query", query);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            // Rerank API 只接收纯文本数组；null 文本转空串，避免 JSON null 触发供应商参数错误。
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        JsonObject parameters = new JsonObject();
        // parameters.top_n 控制返回条数。
        parameters.addProperty("top_n", topN);
        // 返回 document 便于排查，但真正映射仍以 index 为准。
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            // HTTP 非 2xx 转换成结构化异常，由 ModelRoutingExecutor 标记失败并切换候选。
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 3. 校验百炼响应必须包含 output.results。
        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 新的reranked数组
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        // 4. 遍历已按 relevance_score 排序的 results，把 index 映射回 candidates。
        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            // index 指向请求 documents 数组中的位置，也是映射回原始 Chunk id/text 的关键。
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            // relevance_score 是精排分数；没有分数时保留原始候选分数。
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            RetrievedChunk hit = score != null ? new RetrievedChunk(src.getId(), src.getText(), score) : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            if (reranked.size() >= topN) {
                break;
            }
        }

        // 5. 如果供应商返回不足 topN，从原始候选按顺序回填，保证上下文数量尽量满足调用方预期。
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    /**
     * 校验百炼 Rerank 响应结构。
     */
    private JsonObject requireOutput(JsonObject respJson) {
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }
}
