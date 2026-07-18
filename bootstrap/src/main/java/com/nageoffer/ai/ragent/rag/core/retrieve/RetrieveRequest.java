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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量检索的不可变语义参数载体（由 Lombok 生成访问器和构建器）。
 *
 * <p>调用方通过它同时描述“检索什么、从哪里检索、取多少候选、需要哪些元数据约束”，
 * 使检索通道不需要知道底层使用 pgvector、Milvus 或其他实现。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrieveRequest {

    /**
     * 用户自然语言问题 / 查询语句
     */
    private String query;

    /**
     * 召回候选数量，默认值只在调用方未指定时生效；后置处理器可进一步截断。
     */
    @Builder.Default
    private int topK = 5;

    /**
     * 目标向量集合名称：
     * - 为空时走默认 Collection
     * - 非空时按指定 Collection 检索
     */
    private String collectionName;

    /**
     * 元数据等值过滤条件。实现层可将各条件用 AND 拼接，保证召回范围同时满足全部业务约束。
     * 例如 {@code {"biz_type": "ATTENDANCE", "env": "TEST"}}。
     */
    private Map<String, Object> metadataFilters;

    /**
     * 本次请求已解析完成的知识数据可见范围。
     *
     * <p>向量实现必须将其作为最终查询条件，不能只依赖上游通道筛选，
     * 以避免全局检索、意图缓存或未来新增通道绕过数据权限。</p>
     */
    private KnowledgeAccessScope accessScope;
}

