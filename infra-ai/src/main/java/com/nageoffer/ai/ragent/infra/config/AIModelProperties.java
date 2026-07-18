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

package com.nageoffer.ai.ragent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型路由的全部外部化配置。
 *
 * <p>ProviderConfig 描述“怎么连接供应商”，ModelCandidate 描述“选哪个模型”，ModelGroup 描述“同一能力的
 * 候选与优先顺序”。三者拆开后，切换供应商或加入备用模型不需要改业务代码。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIModelProperties {

    /** 提供商标识到连接配置的映射；候选模型的 provider 字段通过该 Map 找到 URL、Key 与端点。 */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /** 对话生成能力的候选模型组。 */
    private ModelGroup chat = new ModelGroup();

    /** 向量化能力的候选模型组；同一向量集合应稳定使用同一维度与模型。 */
    private ModelGroup embedding = new ModelGroup();

    /** Rerank 精排能力的候选模型组，可配置 NOOP 作为最终降级。 */
    private ModelGroup rerank = new ModelGroup();

    /** 故障转移与三态熔断器的阈值配置。 */
    private Selection selection = new Selection();

    /** 流式输出的传输粒度配置。 */
    private Stream stream = new Stream();

    /** 一种模型能力的候选集合，默认模型只决定首选顺序，不取消后续 fallback。 */
    @Data
    public static class ModelGroup {
        /** 普通请求的首选模型 id。 */
        private String defaultModel;

        /** thinking=true 时优先选择的模型 id，候选仍必须声明 supportsThinking=true。 */
        private String deepThinkingModel;

        /** 按路由规则排序后可依次尝试的候选配置。 */
        private List<ModelCandidate> candidates = new ArrayList<>();
    }

    /** 一个可参与路由和故障转移的模型候选。 */
    @Data
    public static class ModelCandidate {

        /** 稳定模型标识，也是熔断器健康状态的 key。 */
        private String id;

        /** 供应商名称，用来找到对应客户端 Bean 和 ProviderConfig。 */
        private String provider;

        /** 发给供应商 API 的模型名称。 */
        private String model;

        /** 候选专属完整 URL；配置后优先于 provider.url 与 endpoints 拼接。 */
        private String url;

        /** Embedding 向量维度，必须和目标向量库 collection 的维度一致。 */
        private Integer dimension;

        /** 除首选模型外的故障转移顺序，数值越小越先尝试。 */
        private Integer priority = 100;

        /** false 时直接在选择阶段过滤，无须删掉配置。 */
        private Boolean enabled = true;

        /** 是否可用于深度思考请求，防止普通模型被错误路由到 reasoning 场景。 */
        private Boolean supportsThinking = false;
    }

    /** 与某个模型供应商通信的连接配置。 */
    @Data
    public static class ProviderConfig {

        /** 供应商 API 基础地址；候选未配置完整 URL 时与端点路径拼接。 */
        private String url;

        /** 供应商认证密钥，应由本地私有配置或环境变量注入。 */
        private String apiKey;

        /** 能力名小写到 API 路径的映射，例如 chat、embedding、rerank。 */
        private Map<String, String> endpoints = new HashMap<>();
    }

    /** 三态熔断器的阈值与冷却配置。 */
    @Data
    public static class Selection {

        /** CLOSED 状态下连续失败达到该值后转为 OPEN。 */
        private Integer failureThreshold = 2;

        /** OPEN 状态的冷却时长，结束后才允许一个 HALF_OPEN 探测请求。 */
        private Long openDurationMs = 30000L;
    }

    /** LLM 流式文本的输出缓冲设置。 */
    @Data
    public static class Stream {

        /** 向上层回调前累积的最小字符数量，用于平衡首字延迟与 SSE 事件数量。 */
        private Integer messageChunkSize = 5;
    }
}
