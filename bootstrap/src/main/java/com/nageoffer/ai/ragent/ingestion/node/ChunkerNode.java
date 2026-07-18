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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将文档文本切成可检索向量块并批量生成 Embedding 的节点。
 *
 * <p>增强文本存在时优先切增强文本，否则使用解析原文；策略工厂根据配置选择固定长度或结构感知切片，
 * 随后一次性为每个 Chunk 填充向量，交给 IndexerNode 写入向量库。</p>
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    /** 用于读取 ChunkerSettings。 */
    private final ObjectMapper objectMapper;
    /** 策略工厂，按配置选择具体切片算法。 */
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    /** 对切分结果批量向量化的服务。 */
    private final ChunkEmbeddingService chunkEmbeddingService;

    /** @return chunker 节点类型。 */
    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    /**
     * 选择文本、解析切片配置、执行切片，并为每个 Chunk 填充向量。
     * ChunkEmbeddingService 必须保持输入输出顺序一致，否则 Chunk 内容会与向量错配。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 增强节点生成了可用文本时优先使用，允许管道把关键词、摘要等信息纳入切片。
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }
        ChunkerSettings settings = parseSettings(config.getSettings());
        // 工厂将枚举策略映射为实现，未知策略应尽早返回可读失败而非 NPE。
        ChunkingStrategy chunker = chunkingStrategyFactory.requireStrategy(settings.getStrategy());
        if (chunker == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + settings.getStrategy()));
        }

        ChunkingOptions chunkConfig = convertToChunkConfig(settings);
        List<VectorChunk> results = chunker.chunk(text, chunkConfig);
        List<VectorChunk> chunks = convertToVectorChunks(results);

        // 切片后立即批量 Embedding，IndexerNode 只负责校验和持久化，不再重复调用模型。
        chunkEmbeddingService.embed(chunks, null);

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    /** 根据策略生成其专属的默认选项对象，统一传给 ChunkingStrategy。 */
    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    /**
     * 复制策略输出为独立的 VectorChunk 列表，保留 id、顺序、内容、元数据和已有向量。
     * 这样后续 Embedding/Indexer 修改不会影响策略实现内部对象。
     */
    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .build())
                .collect(Collectors.toList());
    }

    /** 解析分块设置，并为缺失或非法数值提供安全默认的 size=512、overlap=128。 */
    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
