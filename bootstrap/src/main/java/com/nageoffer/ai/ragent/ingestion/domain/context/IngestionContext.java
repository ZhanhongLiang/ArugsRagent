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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 一次文档摄取流水线的共享可变上下文。
 *
 * <p>Fetcher、Parser、Enhancer、Chunker、Indexer 等节点依次读写同一个对象，避免每个节点重新组装 DTO；
 * 引擎负责记录节点日志和最终状态，异常也在这里汇总给任务服务。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionContext {

    /** 摄取任务 id，用于日志、幂等、追踪和任务状态更新。 */
    private String taskId;

    /** 本次执行使用的管道配置 id，决定节点顺序与每个节点参数。 */
    private String pipelineId;

    /** 原文档来源，FetcherNode 据此获取 rawBytes。 */
    private DocumentSource source;

    /** FetcherNode 产出的原始字节；上传入口也可提前预置以跳过下载。 */
    private byte[] rawBytes;

    /** 原文 MIME 类型，ParserNode 用它选择 Markdown、Tika 等解析器。 */
    private String mimeType;

    /** 解析后的纯文本，增强、切片等文本阶段的基础输入。 */
    private String rawText;

    /** 解析器保留的结构化文档及元数据。 */
    private StructuredDocument document;

    /** Chunker 生成的向量块列表，Indexer 最终写入向量存储。 */
    private List<VectorChunk> chunks;

    /** Enhancer/Enricher 生成的增强文本，可替代或补充 rawText 参与切片。 */
    private String enhancedText;

    /** 文档增强阶段抽取的关键词，供检索和展示使用。 */
    private List<String> keywords;

    /** 可选生成的示例问题，用于知识库运营或问答引导。 */
    private List<String> questions;

    /** 节点间传递的扩展元数据，避免频繁扩大 Context 字段集合。 */
    private Map<String, Object> metadata;

    /** 指定向量写入的目标空间；为空时 Indexer 使用默认知识库空间。 */
    private VectorSpaceId vectorSpaceId;

    /** 引擎推进的任务状态，例如运行中、成功、失败。 */
    private IngestionStatus status;

    /** 每个节点产出的执行日志，最终可用于任务详情和故障排查。 */
    private List<NodeLog> logs;

    /** 未被节点消化的异常，任务服务据此持久化失败状态和错误信息。 */
    private Throwable error;

    /**
     * 是否跳过 IndexerNode 的真实向量写入。
     * true 时节点仅做校验，调用方可把数据库更新与向量持久化放入更高层事务边界统一协调。
     */
    @Builder.Default
    private boolean skipIndexerWrite = false;
}
