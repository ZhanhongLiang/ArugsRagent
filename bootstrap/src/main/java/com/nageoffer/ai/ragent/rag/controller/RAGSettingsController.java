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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.MemorySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向管理端暴露可展示的系统配置快照。
 *
 * <p>该接口只读且不会返回完整密钥；配置对象先转换为专用 VO，
 * 防止内部运行配置、连接凭据或可变对象直接泄露给前端。</p>
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    /**
     * 获取系统 RAG、AI 模型等配置信息
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        // 聚合上传、RAG、限流、记忆和模型配置，前端无需读取多个配置接口。
        SystemSettingsVO response = SystemSettingsVO.builder()
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                .ai(toAISettings(aiModelProperties))
                .build();
        return Results.success(response);
    }

    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        // 只投影管理页需要了解的默认向量空间信息。
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .build();
    }

    private MemorySettings toMemorySettings(MemoryProperties props) {
        // 记忆配置影响上下文成本和历史保留策略，单独映射为展示对象。
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    private AISettings toAISettings(AIModelProperties props) {
        // Provider 配置中 API Key 必须脱敏；其余模型路由字段可用于诊断当前可用能力。
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(maskApiKey(v.getApiKey()))
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                .selection(props.getSelection() == null
                        ? null
                        : AISettings.Selection.builder()
                          .failureThreshold(props.getSelection().getFailureThreshold())
                          .openDurationMs(props.getSelection().getOpenDurationMs())
                          .build())
                .stream(props.getStream() == null
                        ? null
                        : AISettings.Stream.builder()
                          .messageChunkSize(props.getStream().getMessageChunkSize())
                          .build())
                .build();
    }

    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return null;
        }
        // 候选模型顺序与优先级原样保留，便于前端展示路由和降级配置。
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .deepThinkingModel(group.getDeepThinkingModel())
                .candidates(group.getCandidates() == null
                        ? null
                        : group.getCandidates().stream()
                          .map(c -> AISettings.ModelCandidate.builder()
                                    .id(c.getId())
                                    .provider(c.getProvider())
                                    .model(c.getModel())
                                    .url(c.getUrl())
                                    .dimension(c.getDimension())
                                    .priority(c.getPriority())
                                    .enabled(c.getEnabled())
                                    .supportsThinking(c.getSupportsThinking())
                                    .build())
                          .collect(Collectors.toList()))
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String trimmed = apiKey.trim();
        // 短密钥不保留任何可猜测片段；长密钥仅展示首尾用于人工核对是否加载了正确配置。
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }
}
