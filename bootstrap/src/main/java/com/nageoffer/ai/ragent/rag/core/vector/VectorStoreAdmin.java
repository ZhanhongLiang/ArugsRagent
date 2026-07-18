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

/**
 * 向量空间与索引的管理抽象，与文档写入和在线检索解耦。
 *
 * <p>知识库创建阶段可通过它确保底层资源准备完成；具体实现自行决定“空间”对应独立 collection 还是共享表索引。</p>
 */
public interface VectorStoreAdmin {

    /**
     * 幂等：确保向量空间存在（不存在则创建）
     *
     * @param spec 向量空间规格（跨引擎统一定义）
     */
    void ensureVectorSpace(VectorSpaceSpec spec);

    /**
     * 只判断存在性（不创建）
     */
    boolean vectorSpaceExists(VectorSpaceId spaceId);
}
