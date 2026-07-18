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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 可独立启停功能的轻量开关配置。
 *
 * <p>
 * 关闭某项能力只跳过对应阶段，不改变请求和响应协议，便于降级或 A/B 对比。
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 是否启用问题重写与子问题拆分；关闭后检索直接使用原问题或规则兜底结果。
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 是否启用重排序后置处理；关闭时仍可召回，只是候选按原通道顺序继续截断。
     */
    @Value("${rag.rerank.enabled:true}")
    private Boolean rerankEnabled;
}
