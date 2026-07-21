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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PostgreSQL pgvector 的向量检索实现。
 *
 * <p>先将查询文本转换为嵌入向量，再在指定 collection 的文档分块中按余弦距离排序。
 * collectionName 由检索通道传入，既支持意图定向检索，也避免全局检索跨知识库混入无关内容。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        // 文本检索入口负责生成查询向量；已经持有向量的调用方可直接走 retrieveByVector，避免重复调用模型。
        List<Float> embedding = embeddingService.embed(request.getQuery());
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        KnowledgeAccessScope accessScope = request.getAccessScope();
        if (accessScope != null && !accessScope.canReadCollection(request.getCollectionName())) {
            return List.of();
        }
        if (accessScope != null && !accessScope.unrestricted() && accessScope.readableDocumentIds().isEmpty()) {
            return List.of();
        }

        // HNSW 的 ef_search 越大候选探索越充分、召回通常越好，但查询开销也更高。
        // 这里作为会话级参数设置，不改变索引结构。
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET hnsw.ef_search = 200");

        // pgvector JDBC 参数以 [x,y,z] 文本字面量传入，再在 SQL 中显式转换为 vector 类型。
        String vectorLiteral = toVectorLiteral(vector);

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, content, 1 - (embedding <=> ?::vector) AS score "
                + "FROM t_knowledge_vector WHERE metadata->>'collection_name' = ?");
        parameters.add(vectorLiteral);
        parameters.add(request.getCollectionName());

        if (accessScope != null && !accessScope.unrestricted()) {
            String placeholders = String.join(", ", java.util.Collections.nCopies(
                    accessScope.readableDocumentIds().size(), "?"));
            sql.append(" AND metadata->>'doc_id' IN (").append(placeholders).append(')');
            parameters.addAll(accessScope.readableDocumentIds());
        }
        MetadataFilterSupport.appendPgConditions(sql, parameters, request.getMetadataFilters());
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        parameters.add(vectorLiteral);
        parameters.add(request.getTopK());

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(sql.toString(),
                // <=> 是余弦距离；用 1 - distance 转成越大越相关的业务分数。
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                parameters.toArray()
        );
    }

    private float[] normalize(float[] vector) {
        // 归一化使内积尺度稳定，与余弦相似度的几何含义保持一致。
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        // 嵌入服务返回装箱列表，pgvector 参数构造使用原始 float 数组以减少装拆箱。
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        // PostgreSQL pgvector 接受形如 [0.1,0.2] 的文本表示。
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
