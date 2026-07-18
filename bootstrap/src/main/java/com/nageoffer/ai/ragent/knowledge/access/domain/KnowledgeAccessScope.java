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

package com.nageoffer.ai.ragent.knowledge.access.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次请求已经解析完成的知识数据可见范围。
 *
 * <p>检索层只依赖此值对象，不感知用户、车间、班组和 ACL 的持久化细节；
 * 因而未来替换为项目组、租户或外部 IAM 时无需修改检索通道。</p>
 */
public record KnowledgeAccessScope(
        boolean unrestricted,
        Set<String> readableKnowledgeBaseIds,
        Set<String> readableCollectionNames,
        Set<String> readableDocumentIds
) {

    public KnowledgeAccessScope {
        readableKnowledgeBaseIds = immutableCopy(readableKnowledgeBaseIds);
        readableCollectionNames = immutableCopy(readableCollectionNames);
        readableDocumentIds = immutableCopy(readableDocumentIds);
    }

    public static KnowledgeAccessScope allAccess() {
        return new KnowledgeAccessScope(true, Set.of(), Set.of(), Set.of());
    }

    public static KnowledgeAccessScope restricted(Set<String> knowledgeBaseIds,
                                                  Set<String> collectionNames,
                                                  Set<String> documentIds) {
        return new KnowledgeAccessScope(false, knowledgeBaseIds, collectionNames, documentIds);
    }

    public boolean canReadKnowledgeBase(String knowledgeBaseId) {
        return unrestricted || readableKnowledgeBaseIds.contains(knowledgeBaseId);
    }

    public boolean canReadCollection(String collectionName) {
        return unrestricted || readableCollectionNames.contains(collectionName);
    }

    public boolean canReadDocument(String documentId) {
        return unrestricted || readableDocumentIds.contains(documentId);
    }

    private static Set<String> immutableCopy(Set<String> values) {
        return values == null || values.isEmpty()
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(values));
    }
}
