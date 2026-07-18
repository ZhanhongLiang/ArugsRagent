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

package com.nageoffer.ai.ragent.knowledge.access.service;

import com.nageoffer.ai.ragent.knowledge.access.controller.request.KnowledgeResourceScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.UserDataScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopTeamCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.KnowledgeResourceScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.UserDataScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopTeamVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopVO;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.knowledge.access.enums.KnowledgeResourceType;

import java.util.List;

/**
 * 车间、班组和知识资源数据权限的唯一业务入口。
 */
public interface KnowledgeAccessService {

    KnowledgeAccessScope currentAccessScope();

    KnowledgeAccessScope resolveAccessScope(String userId, String role);

    void requireReadableKnowledgeBase(String knowledgeBaseId);

    void requireReadableDocument(String documentId);

    void requireManageKnowledgeBase(String knowledgeBaseId);

    void requireManageDocument(String documentId);

    List<WorkshopVO> listWorkshops();

    WorkshopVO createWorkshop(WorkshopCreateRequest request);

    List<WorkshopTeamVO> listTeams(String workshopId);

    WorkshopTeamVO createTeam(String workshopId, WorkshopTeamCreateRequest request);

    List<UserDataScopeVO> listUserScopes(String userId);

    void replaceUserScopes(String userId, UserDataScopeReplaceRequest request);

    List<KnowledgeResourceScopeVO> listResourceScopes(KnowledgeResourceType resourceType, String resourceId);

    void replaceResourceScopes(KnowledgeResourceType resourceType,
                               String resourceId,
                               KnowledgeResourceScopeReplaceRequest request);
}
