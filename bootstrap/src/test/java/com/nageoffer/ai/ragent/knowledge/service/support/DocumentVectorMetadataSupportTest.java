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

package com.nageoffer.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentVectorMetadataSupportTest {

    private final DocumentVectorMetadataSupport support = new DocumentVectorMetadataSupport(new ObjectMapper());

    @Test
    void attachesDocumentMetadataWithoutLosingChunkMetadata() {
        VectorChunk chunk = VectorChunk.builder()
                .metadata(Map.of("source_page", 3, "process_code", "incorrect"))
                .build();

        support.attach("{\"process_code\":\"winding\",\"equipment_model\":\"WINDER-W3\"}", List.of(chunk));

        assertEquals(3, chunk.getMetadata().get("source_page"));
        assertEquals("winding", chunk.getMetadata().get("process_code"));
        assertEquals("WINDER-W3", chunk.getMetadata().get("equipment_model"));
    }

    @Test
    void rejectsReservedVectorMetadataKey() {
        assertThrows(ClientException.class, () -> support.normalize("{\"doc_id\":\"forged\"}"));
    }
}
