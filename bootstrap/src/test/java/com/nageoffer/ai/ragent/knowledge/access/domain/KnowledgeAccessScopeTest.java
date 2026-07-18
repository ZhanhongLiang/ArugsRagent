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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeAccessScopeTest {

    @Test
    void restrictedScopeOnlyAllowsAssignedResources() {
        KnowledgeAccessScope scope = KnowledgeAccessScope.restricted(
                Set.of("kb-a"), Set.of("collection-a"), Set.of("doc-a"));

        assertTrue(scope.canReadKnowledgeBase("kb-a"));
        assertFalse(scope.canReadKnowledgeBase("kb-b"));
        assertTrue(scope.canReadCollection("collection-a"));
        assertFalse(scope.canReadCollection("collection-b"));
        assertTrue(scope.canReadDocument("doc-a"));
        assertFalse(scope.canReadDocument("doc-b"));
    }

    @Test
    void administratorScopeAllowsEveryResource() {
        KnowledgeAccessScope scope = KnowledgeAccessScope.allAccess();

        assertTrue(scope.unrestricted());
        assertTrue(scope.canReadKnowledgeBase("any-kb"));
        assertTrue(scope.canReadCollection("any-collection"));
        assertTrue(scope.canReadDocument("any-document"));
    }
}
