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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

/**
 * 文档分块向量的写入、更新和删除抽象。
 *
 * <p>摄取流水线只依赖本接口，不感知底层是 pgvector 还是 Milvus；这样切换向量引擎不需要改动分块、嵌入和任务编排代码。</p>
 */
public interface VectorStoreService {

    /**
     * 批量建立文档的向量索引。调用前必须已完成嵌入计算，否则底层无法执行相似度检索。
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param chunks         文档切片列表，须包含已计算好的 embedding
     */
    void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks);

    /**
     * 更新单个 chunk 的向量索引，通常用于编辑正文、重新分块或重算嵌入后。
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param chunk          待更新的文档切片，须包含最新的 embedding
     */
    void updateChunk(String collectionName, String docId, VectorChunk chunk);

    /**
     * 删除文档的所有向量索引，使重建或删除文档后旧内容不再参与召回。
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     */
    void deleteDocumentVectors(String collectionName, String docId);

    /**
     * 删除指定的单个 chunk 向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param chunkId        chunk 的唯一标识
     */
    void deleteChunkById(String collectionName, String chunkId);

    /**
     * 批量删除指定 chunk 的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param chunkIds       chunk 唯一标识列表
     */
    void deleteChunksByIds(String collectionName, List<String> chunkIds);
}
