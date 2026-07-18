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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 主链路在未指定局部配置时使用的默认参数。
 *
 * <p>
 * 向量集合名、嵌入维度和度量方式必须作为一组保持兼容；SSE 超时则是连接泄漏的最终兜底。
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   default:
 *     collection-name: default_collection
 *     dimension: 768
 *     metric-type: COSINE
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.default")
public class RAGDefaultProperties {

    /**
     * 默认逻辑向量集合名。意图节点未绑定知识库或摄取未指定空间时会回退到此值。
     */
    private String collectionName;

    /**
     * 嵌入向量维度，必须与 Embedding 模型输出及向量库 schema 一致。
     */
    private Integer dimension;

    /**
     * 向量相似度度量类型
     * <p>
     * 用于计算向量之间相似度的度量方法，常见取值：
     * <ul>
     *   <li>{@code COSINE}：余弦相似度</li>
     *   <li>{@code L2}：欧氏距离</li>
     *   <li>{@code IP}：内积</li>
     * </ul>
     */
    private String metricType;

    /**
     * SSE 连接的最终超时（毫秒）。模型或客户端异常导致回调不结束时，Spring 会据此释放 emitter 资源。
     */
    private Long sseTimeoutMs = 5 * 60 * 1000L;
}
