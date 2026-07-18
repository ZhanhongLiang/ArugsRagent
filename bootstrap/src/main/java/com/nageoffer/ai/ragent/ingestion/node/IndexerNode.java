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

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将已向量化 Chunk 持久化到目标向量空间的索引节点。
 *
 * <p>节点不再调用模型生成 Embedding，而是严格校验 Chunk 中已有向量的维度；随后确保向量空间存在，
 * 合并元数据并交给抽象 VectorStoreService 写入，因而可以兼容 PgVector、Milvus 等实现。</p>
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    /** 将任意扩展元数据安全转换为 JSON 元素。 */
    private static final Gson GSON = new Gson();

    /** 将节点配置转换为 IndexerSettings。 */
    private final ObjectMapper objectMapper;
    /** 查询或创建向量空间的管理端口。 */
    private final VectorStoreAdmin vectorStoreAdmin;
    /** 实际批量写入 Chunk 的向量存储端口。 */
    private final VectorStoreService vectorStoreService;
    /** 未指定 VectorSpaceId 时使用的默认集合名与向量维度。 */
    private final RAGDefaultProperties ragDefaultProperties;

    /** 注入配置、向量空间管理与向量写入依赖。 */
    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       VectorStoreService vectorStoreService,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.vectorStoreService = vectorStoreService;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    /** @return indexer 节点类型。 */
    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    /**
     * 校验 Chunk 向量、定位集合、确保空间存在，构造存储行后执行或跳过真实写入。
     * skipIndexerWrite 支持上层在更大事务边界内自行协调数据库状态与向量提交。
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());
        String collectionName = resolveCollectionName(context);
        if (!StringUtils.hasText(collectionName)) {
            return NodeResult.fail(new ClientException("索引器需要指定集合名称"));
        }

        // 先确定期望维度，再逐块验证，防止不同模型或损坏向量混入同一空间。
        int expectedDim = resolveDimension(chunks);
        if (expectedDim <= 0) {
            return NodeResult.fail(new ClientException("未配置向量维度"));
        }
        float[][] vectorArray;
        try {
            vectorArray = toArrayFromChunks(chunks, expectedDim);
        } catch (ClientException ex) {
            return NodeResult.fail(ex);
        }

        // 首次写入时懒创建向量空间，已存在时不重复创建。
        ensureVectorSpace(collectionName);
        List<JsonObject> rows = buildRows(context, chunks, vectorArray, settings.getMetadataFields());

        if (context.isSkipIndexerWrite()) {
            // 调用方会在事务中统一写向量，此处只做校验和 chunkId/embedding 的填充（buildRows 已完成）
            return NodeResult.ok("已准备 " + rows.size() + " 个分块（向量写入由调用方统一完成）");
        }

        insertRows(collectionName, context.getTaskId(), rows);
        return NodeResult.ok("已写入 " + rows.size() + " 个分块到集合 " + collectionName);
    }

    /** 空节点设置使用默认 IndexerSettings，避免配置缺失阻断基本写入。 */
    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    /** VectorSpaceId 显式指定集合时优先使用，否则回退到 RAG 默认集合。 */
    private String resolveCollectionName(IngestionContext context) {
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getCollectionName();
    }

    /** 检查向量空间存在性，不存在时以最小规范创建。 */
    private void ensureVectorSpace(String collectionName) {
        boolean vectorSpaceExists = vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                .logicalName(collectionName)
                .build());
        if (vectorSpaceExists) {
            return;
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(collectionName)
                        .build())
                .remark("RAG向量存储空间")
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);
    }

    /**
     * 将中间 JSON 行重新转换为 VectorChunk，再通过统一存储端口批量写入。
     * JSON 行主要用于统一处理元数据和跳写场景，存储接口仍以领域 VectorChunk 为输入。
     */
    private void insertRows(String collectionName, String docId, List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // 只还原向量存储必需字段；metadata 已在 buildRows 中用于构造写入行和日志。
        List<VectorChunk> chunks = rows.stream().map(row -> {
            String chunkId = row.get("id").getAsString();
            String content = row.get("content").getAsString();
            JsonArray embeddingArray = row.getAsJsonArray("embedding");
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = embeddingArray.get(i).getAsFloat();
            }

            Integer chunkIndex = null;
            if (row.has("metadata") && row.get("metadata").isJsonObject()) {
                JsonObject metadata = row.getAsJsonObject("metadata");
                if (metadata.has("chunk_index")) {
                    chunkIndex = metadata.get("chunk_index").getAsInt();
                }
            }

            return VectorChunk.builder()
                    .chunkId(chunkId)
                    .content(content)
                    .index(chunkIndex)
                    .embedding(embedding)
                    .build();
        }).toList();

        vectorStoreService.indexDocumentChunks(collectionName, docId, chunks);

        log.info("向量写入成功，集合={}，行数={}", collectionName, chunks.size());
    }

    /** 优先使用全局配置维度；未配置时从第一条有效 Chunk 向量推断。 */
    private int resolveDimension(List<VectorChunk> chunks) {
        Integer configured = ragDefaultProperties.getDimension();
        if (configured != null && configured > 0) {
            return configured;
        }
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                return chunk.getEmbedding().length;
            }
        }
        return 0;
    }

    /**
     * 提取每个 Chunk 的向量并做完整性、维度一致性检查。
     * 一条异常向量会让任务失败，避免部分写入后形成不可检索或分数不可比的集合。
     */
    private float[][] toArrayFromChunks(List<VectorChunk> chunks, int expectedDim) {
        float[][] out = new float[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = chunks.get(i).getEmbedding();
            if (vector == null || vector.length == 0) {
                throw new ClientException("向量结果缺失，索引: " + i);
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new ClientException("向量维度不匹配，索引: " + i);
            }
            out[i] = vector;
        }
        return out;
    }

    /**
     * 为每个 Chunk 生成存储行，补齐 chunkId、基础追踪元数据和配置允许的扩展元数据。
     * content 保留原始文本而不是可能被预处理过的 embedding 输入，确保检索命中后可向用户展示原文。
     */
    private List<JsonObject> buildRows(IngestionContext context,
                                       List<VectorChunk> chunks,
                                       float[][] vectors,
                                       List<String> metadataFields) {
        Map<String, Object> mergedMetadata = mergeMetadata(context);
        List<JsonObject> rows = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String chunkId = StringUtils.hasText(chunk.getChunkId()) ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            chunk.setChunkId(chunkId);
            chunk.setEmbedding(vectors[i]);

            // 使用原始内容作为存储内容，而不是用于 embedding 的预处理文本。
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("chunk_index", chunk.getIndex());
            metadata.addProperty("task_id", context.getTaskId());
            metadata.addProperty("pipeline_id", context.getPipelineId());
            DocumentSource source = context.getSource();
            if (source != null && source.getType() != null) {
                metadata.addProperty("source_type", source.getType().getValue());
            }
            if (source != null && StringUtils.hasText(source.getLocation())) {
                metadata.addProperty("source_location", source.getLocation());
            }

            // 只写入管道配置允许的扩展字段，避免整份 Context 元数据无限膨胀到向量库。
            if (metadataFields != null && !metadataFields.isEmpty()) {
                Map<String, Object> combined = new HashMap<>(mergedMetadata);
                if (chunk.getMetadata() != null) {
                    combined.putAll(chunk.getMetadata());
                }
                for (String field : metadataFields) {
                    if (!StringUtils.hasText(field)) {
                        continue;
                    }
                    Object value = combined.get(field);
                    if (value != null) {
                        addMetadataValue(metadata, field, value);
                    }
                }
            }

            JsonObject row = new JsonObject();
            row.addProperty("id", chunkId);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors[i]));
            rows.add(row);
        }
        return rows;
    }

    /** 复制上下文元数据，防止构造存储行时修改共享 Context 的原始 Map。 */
    private Map<String, Object> mergeMetadata(IngestionContext context) {
        Map<String, Object> merged = new HashMap<>();
        if (context.getMetadata() != null) {
            merged.putAll(context.getMetadata());
        }
        return merged;
    }

    /** 用 Gson 通用序列化扩展字段，支持字符串、数字、列表和嵌套对象。 */
    private void addMetadataValue(JsonObject metadata, String field, Object value) {
        JsonElement element = GSON.toJsonTree(value);
        metadata.add(field, element);
    }

    /** 将 float 向量转换为 JSON 数组，供统一的中间存储行结构使用。 */
    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }
}
