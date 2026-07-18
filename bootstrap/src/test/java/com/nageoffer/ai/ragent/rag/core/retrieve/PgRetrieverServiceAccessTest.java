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

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PgRetrieverServiceAccessTest {

    @Test
    void skipsDatabaseWhenCollectionIsOutsideAccessScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PgRetrieverService service = new PgRetrieverService(jdbcTemplate, embeddingService);
        RetrieveRequest request = RetrieveRequest.builder()
                .collectionName("restricted-collection")
                .accessScope(KnowledgeAccessScope.restricted(
                        Set.of("kb-a"), Set.of("allowed-collection"), Set.of("doc-a")))
                .build();

        assertTrue(service.retrieveByVector(new float[]{0.2F, 0.3F}, request).isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void skipsDatabaseWhenNoDocumentIsReadable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PgRetrieverService service = new PgRetrieverService(jdbcTemplate, embeddingService);
        RetrieveRequest request = RetrieveRequest.builder()
                .collectionName("allowed-collection")
                .accessScope(KnowledgeAccessScope.restricted(
                        Set.of("kb-a"), Set.of("allowed-collection"), Set.of()))
                .build();

        assertTrue(service.retrieveByVector(new float[]{0.2F, 0.3F}, request).isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsReadableDocumentFilterToVectorQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PgRetrieverService service = new PgRetrieverService(jdbcTemplate, embeddingService);
        RetrieveRequest request = RetrieveRequest.builder()
                .collectionName("allowed-collection")
                .topK(3)
                .accessScope(KnowledgeAccessScope.restricted(
                        Set.of("kb-a"), Set.of("allowed-collection"), Set.of("doc-a", "doc-b")))
                .build();

        service.retrieveByVector(new float[]{0.2F, 0.3F}, request);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parametersCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), parametersCaptor.capture());

        assertTrue(sqlCaptor.getValue().contains("metadata->>'doc_id' IN (?, ?)"));
        assertTrue(List.of(parametersCaptor.getValue()).containsAll(List.of("doc-a", "doc-b")));
    }
}
