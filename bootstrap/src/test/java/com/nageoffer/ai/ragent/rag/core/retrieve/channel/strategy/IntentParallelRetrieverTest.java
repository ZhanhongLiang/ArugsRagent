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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentParallelRetrieverTest {

    @Test
    void retrievesOnlyReadablePrimaryAndSupplementalCollections() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        Executor directExecutor = Runnable::run;
        KnowledgeAccessScope accessScope = KnowledgeAccessScope.restricted(
                Set.of("fault-kb", "manual-kb"),
                Set.of("fault-collection", "manual-collection"),
                Set.of("fault-doc", "manual-doc"));
        IntentParallelRetriever retriever = new IntentParallelRetriever(
                retrieverService,
                directExecutor,
                accessScope,
                Map.of("CELL_FAULT_WIND_TENSION", List.of("manual-collection", "maintenance-collection")),
                Map.of("CELL_FAULT_WIND_TENSION", Map.of("process_code", "winding")));
        IntentNode node = IntentNode.builder()
                .id("CELL_FAULT_WIND_TENSION")
                .name("卷绕张力与纠偏异常")
                .kind(IntentKind.KB)
                .collectionName("fault-collection")
                .topK(3)
                .build();

        retriever.executeParallelRetrieval(
                "卷绕机张力不稳", List.of(NodeScore.builder().node(node).score(0.9D).build()), 5, 2);

        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(2)).retrieve(requestCaptor.capture());
        assertEquals(
                Set.of("fault-collection", "manual-collection"),
                requestCaptor.getAllValues().stream()
                        .map(RetrieveRequest::getCollectionName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, requestCaptor.getAllValues().get(0).getTopK());
        assertEquals("winding", requestCaptor.getAllValues().get(0).getMetadataFilters().get("process_code"));
    }
}
