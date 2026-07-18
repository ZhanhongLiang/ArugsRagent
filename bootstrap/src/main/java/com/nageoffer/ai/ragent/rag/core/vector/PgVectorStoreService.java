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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 pgvector 表保存知识库分块向量的实现。
 *
 * <p>物理上所有知识库共用 {@code t_knowledge_vector}；collection_name 和 doc_id 存在 JSONB 元数据中，
 * 使逻辑知识库隔离、文档级删除和分块级更新无需为每个知识库创建新表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            // 空文档无需访问数据库，避免产生无意义批处理。
            return;
        }

        // 每个切片写入正文、结构化元数据和已计算好的嵌入；批量写入减少 JDBC 往返。
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                chunks, chunks.size(), (ps, chunk) -> {
                    ps.setString(1, chunk.getChunkId());
                    ps.setString(2, chunk.getContent());
                    // 元数据同时保留切片原有属性和平台必须的归属字段。
                    ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                    ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
                });

        log.info("批量写入向量到 PostgreSQL，collectionName={}, docId={}, count={}", collectionName, docId, chunks.size());
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        // collectionName 与 docId 双条件防止相同文档编号误删其他知识库数据。
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update(
                "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? AND metadata->>'doc_id' = ?",
                collectionName, docId);
        log.info("删除文档向量，collectionName={}, docId={}, deleted={}", collectionName, docId, deleted);
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        // chunkId 在当前表中全局唯一；collectionName 仍保留在接口中以统一不同向量引擎的调用契约。
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id = ?", chunkId);
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // 占位符数量由输入集合生成，值仍通过 JDBC 参数绑定，不能把 chunkId 直接拼到 SQL 中。
        String placeholders = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id IN (" + placeholders + ")", chunkIds.toArray());
        log.info("批量删除 chunk 向量，collectionName={}, count={}, deleted={}", collectionName, chunkIds.size(), deleted);
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        // 以切片 ID 为唯一键 UPSERT，既支持人工编辑后重嵌入，也支持重复消费时的幂等覆盖。
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.update(
                "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector) " +
                        "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding",
                chunk.getChunkId(),
                chunk.getContent(),
                buildMetadataJson(collectionName, docId, chunk),
                toVectorLiteral(chunk.getEmbedding())
        );
    }

    private String buildMetadataJson(String collectionName, String docId, VectorChunk chunk) {
        // LinkedHashMap 先保留上游元数据，再写入平台归属字段；后者覆盖同名字段以保证过滤可靠。
        Map<String, Object> meta = new LinkedHashMap<>();
        if (chunk.getMetadata() != null) {
            meta.putAll(chunk.getMetadata());
        }

        meta.put("collection_name", collectionName);
        meta.put("doc_id", docId);
        meta.put("chunk_index", chunk.getIndex());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("元数据序列化失败", e);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        // pgvector JDBC 驱动未直接映射 float[] 时，使用其标准文本字面量传递向量。
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
