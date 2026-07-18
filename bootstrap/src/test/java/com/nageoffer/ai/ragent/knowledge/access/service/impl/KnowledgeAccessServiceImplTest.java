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

package com.nageoffer.ai.ragent.knowledge.access.service.impl;

import com.nageoffer.ai.ragent.knowledge.access.dao.entity.KnowledgeResourceScopeDO;
import com.nageoffer.ai.ragent.knowledge.access.dao.entity.UserDataScopeDO;
import com.nageoffer.ai.ragent.knowledge.access.dao.entity.WorkshopDO;
import com.nageoffer.ai.ragent.knowledge.access.dao.entity.WorkshopTeamDO;
import com.nageoffer.ai.ragent.knowledge.access.dao.mapper.KnowledgeResourceScopeMapper;
import com.nageoffer.ai.ragent.knowledge.access.dao.mapper.UserDataScopeMapper;
import com.nageoffer.ai.ragent.knowledge.access.dao.mapper.WorkshopMapper;
import com.nageoffer.ai.ragent.knowledge.access.dao.mapper.WorkshopTeamMapper;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.knowledge.access.enums.DataScopeType;
import com.nageoffer.ai.ragent.knowledge.access.enums.KnowledgeResourceScopeType;
import com.nageoffer.ai.ragent.knowledge.access.enums.KnowledgeResourceType;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeAccessServiceImplTest {

    @Test
    void documentScopeOverridesKnowledgeBaseScopeForTeamUser() {
        UserDataScopeMapper userDataScopeMapper = mock(UserDataScopeMapper.class);
        KnowledgeResourceScopeMapper resourceScopeMapper = mock(KnowledgeResourceScopeMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        WorkshopMapper workshopMapper = mock(WorkshopMapper.class);
        WorkshopTeamMapper workshopTeamMapper = mock(WorkshopTeamMapper.class);
        KnowledgeAccessServiceImpl service = new KnowledgeAccessServiceImpl(
                workshopMapper,
                workshopTeamMapper,
                userDataScopeMapper,
                resourceScopeMapper,
                knowledgeBaseMapper,
                documentMapper,
                mock(UserMapper.class));

        when(workshopMapper.selectList(any())).thenReturn(List.of(
                WorkshopDO.builder().id("workshop-a").enabled(1).deleted(0).build()));
        when(workshopTeamMapper.selectList(any())).thenReturn(List.of(
                WorkshopTeamDO.builder().id("team-a-01").workshopId("workshop-a").enabled(1).deleted(0).build(),
                WorkshopTeamDO.builder().id("team-a-02").workshopId("workshop-a").enabled(1).deleted(0).build()));

        when(userDataScopeMapper.selectList(any())).thenReturn(List.of(
                UserDataScopeDO.builder()
                        .userId("user-a")
                        .scopeType(DataScopeType.TEAM)
                        .workshopId("workshop-a")
                        .teamId("team-a-01")
                        .build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("kb-a").collectionName("collection-a").deleted(0).build()));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id("doc-normal").kbId("kb-a").deleted(0).build(),
                KnowledgeDocumentDO.builder().id("doc-team-a-01").kbId("kb-a").deleted(0).build(),
                KnowledgeDocumentDO.builder().id("doc-sensitive").kbId("kb-a").deleted(0).build()));
        when(resourceScopeMapper.selectList(any())).thenReturn(List.of(
                scope(KnowledgeResourceType.KNOWLEDGE_BASE, "kb-a", KnowledgeResourceScopeType.WORKSHOP,
                        "workshop-a", null),
                scope(KnowledgeResourceType.DOCUMENT, "doc-team-a-01", KnowledgeResourceScopeType.TEAM,
                        "workshop-a", "team-a-01"),
                scope(KnowledgeResourceType.DOCUMENT, "doc-sensitive", KnowledgeResourceScopeType.TEAM,
                        "workshop-a", "team-a-02")));

        KnowledgeAccessScope accessScope = service.resolveAccessScope("user-a", "user");

        assertTrue(accessScope.canReadKnowledgeBase("kb-a"));
        assertTrue(accessScope.canReadCollection("collection-a"));
        assertFalse(accessScope.canReadDocument("doc-normal"));
        assertTrue(accessScope.canReadDocument("doc-team-a-01"));
        assertFalse(accessScope.canReadDocument("doc-sensitive"));
    }

    @Test
    void workshopScopeCanReadAllTeamsWithinTheWorkshop() {
        UserDataScopeMapper userDataScopeMapper = mock(UserDataScopeMapper.class);
        KnowledgeResourceScopeMapper resourceScopeMapper = mock(KnowledgeResourceScopeMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        WorkshopMapper workshopMapper = mock(WorkshopMapper.class);
        WorkshopTeamMapper workshopTeamMapper = mock(WorkshopTeamMapper.class);
        KnowledgeAccessServiceImpl service = new KnowledgeAccessServiceImpl(
                workshopMapper,
                workshopTeamMapper,
                userDataScopeMapper,
                resourceScopeMapper,
                knowledgeBaseMapper,
                documentMapper,
                mock(UserMapper.class));

        when(workshopMapper.selectList(any())).thenReturn(List.of(
                WorkshopDO.builder().id("workshop-a").enabled(1).deleted(0).build()));
        when(workshopTeamMapper.selectList(any())).thenReturn(List.of(
                WorkshopTeamDO.builder().id("team-a-02").workshopId("workshop-a").enabled(1).deleted(0).build()));

        when(userDataScopeMapper.selectList(any())).thenReturn(List.of(
                UserDataScopeDO.builder()
                        .userId("manager-a")
                        .scopeType(DataScopeType.WORKSHOP)
                        .workshopId("workshop-a")
                        .build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("kb-a").collectionName("collection-a").deleted(0).build()));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id("doc-a-02").kbId("kb-a").deleted(0).build()));
        when(resourceScopeMapper.selectList(any())).thenReturn(List.of(
                scope(KnowledgeResourceType.DOCUMENT, "doc-a-02", KnowledgeResourceScopeType.TEAM,
                        "workshop-a", "team-a-02")));

        KnowledgeAccessScope accessScope = service.resolveAccessScope("manager-a", "user");

        assertTrue(accessScope.canReadDocument("doc-a-02"));
    }

    @Test
    void globalKnowledgeBaseIsReadableWithoutAnOrganizationAssignment() {
        UserDataScopeMapper userDataScopeMapper = mock(UserDataScopeMapper.class);
        KnowledgeResourceScopeMapper resourceScopeMapper = mock(KnowledgeResourceScopeMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        WorkshopMapper workshopMapper = mock(WorkshopMapper.class);
        WorkshopTeamMapper workshopTeamMapper = mock(WorkshopTeamMapper.class);
        KnowledgeAccessServiceImpl service = new KnowledgeAccessServiceImpl(
                workshopMapper,
                workshopTeamMapper,
                userDataScopeMapper,
                resourceScopeMapper,
                knowledgeBaseMapper,
                documentMapper,
                mock(UserMapper.class));

        when(workshopMapper.selectList(any())).thenReturn(List.of());
        when(workshopTeamMapper.selectList(any())).thenReturn(List.of());

        when(userDataScopeMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("kb-global").collectionName("collection-global").deleted(0).build()));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id("doc-global").kbId("kb-global").deleted(0).build()));
        when(resourceScopeMapper.selectList(any())).thenReturn(List.of(
                scope(KnowledgeResourceType.KNOWLEDGE_BASE, "kb-global", KnowledgeResourceScopeType.GLOBAL,
                        null, null)));

        KnowledgeAccessScope accessScope = service.resolveAccessScope("user-a", "user");

        assertTrue(accessScope.canReadKnowledgeBase("kb-global"));
        assertTrue(accessScope.canReadCollection("collection-global"));
        assertTrue(accessScope.canReadDocument("doc-global"));
    }

    @Test
    void disablesAccessWhenAssignedWorkshopIsInactive() {
        UserDataScopeMapper userDataScopeMapper = mock(UserDataScopeMapper.class);
        KnowledgeResourceScopeMapper resourceScopeMapper = mock(KnowledgeResourceScopeMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        WorkshopMapper workshopMapper = mock(WorkshopMapper.class);
        WorkshopTeamMapper workshopTeamMapper = mock(WorkshopTeamMapper.class);
        KnowledgeAccessServiceImpl service = new KnowledgeAccessServiceImpl(
                workshopMapper,
                workshopTeamMapper,
                userDataScopeMapper,
                resourceScopeMapper,
                knowledgeBaseMapper,
                documentMapper,
                mock(UserMapper.class));

        when(userDataScopeMapper.selectList(any())).thenReturn(List.of(
                UserDataScopeDO.builder()
                        .userId("user-a")
                        .scopeType(DataScopeType.WORKSHOP)
                        .workshopId("workshop-disabled")
                        .build()));
        when(workshopMapper.selectList(any())).thenReturn(List.of());
        when(workshopTeamMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("kb-a").collectionName("collection-a").deleted(0).build()));
        when(documentMapper.selectList(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id("doc-a").kbId("kb-a").deleted(0).build()));
        when(resourceScopeMapper.selectList(any())).thenReturn(List.of(
                scope(KnowledgeResourceType.KNOWLEDGE_BASE, "kb-a", KnowledgeResourceScopeType.WORKSHOP,
                        "workshop-disabled", null)));

        KnowledgeAccessScope accessScope = service.resolveAccessScope("user-a", "user");

        assertFalse(accessScope.canReadKnowledgeBase("kb-a"));
        assertFalse(accessScope.canReadDocument("doc-a"));
    }

    private KnowledgeResourceScopeDO scope(KnowledgeResourceType resourceType,
                                           String resourceId,
                                           KnowledgeResourceScopeType scopeType,
                                           String workshopId,
                                           String teamId) {
        return KnowledgeResourceScopeDO.builder()
                .resourceType(resourceType)
                .resourceId(resourceId)
                .scopeType(scopeType)
                .workshopId(workshopId)
                .teamId(teamId)
                .build();
    }
}
