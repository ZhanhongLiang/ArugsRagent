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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataFilterSupportTest {

    @Test
    void createsMilvusExpressionForScalarAndMultipleValues() {
        String expression = MetadataFilterSupport.toMilvusExpression(Map.of(
                "process_code", List.of("winding", "stacking"),
                "effective_status", "有效"));

        assertTrue(expression.contains("metadata[\"process_code\"]"));
        assertTrue(expression.contains("json_contains"));
        assertTrue(expression.contains("\"winding\""));
        assertTrue(expression.contains("\"有效\""));
    }

    @Test
    void rejectsSystemMetadataKey() {
        assertThrows(ClientException.class,
                () -> MetadataFilterSupport.toMilvusExpression(Map.of("doc_id", "forged-document")));
    }
}
