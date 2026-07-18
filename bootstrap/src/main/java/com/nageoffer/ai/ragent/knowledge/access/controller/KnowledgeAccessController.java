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

package com.nageoffer.ai.ragent.knowledge.access.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.KnowledgeResourceScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.UserDataScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopTeamCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.KnowledgeResourceScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.UserDataScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopTeamVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopVO;
import com.nageoffer.ai.ragent.knowledge.access.enums.KnowledgeResourceType;
import com.nageoffer.ai.ragent.knowledge.access.service.KnowledgeAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理车间、班组、用户数据范围和知识资源 ACL 的本地管理接口。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeAccessController {

    private final KnowledgeAccessService knowledgeAccessService;

    @GetMapping("/knowledge-access/workshops")
    public Result<List<WorkshopVO>> listWorkshops() {
        return Results.success(knowledgeAccessService.listWorkshops());
    }

    @PostMapping("/knowledge-access/workshops")
    public Result<WorkshopVO> createWorkshop(@Valid @RequestBody WorkshopCreateRequest request) {
        return Results.success(knowledgeAccessService.createWorkshop(request));
    }

    @GetMapping("/knowledge-access/workshops/{workshop-id}/teams")
    public Result<List<WorkshopTeamVO>> listTeams(@PathVariable("workshop-id") String workshopId) {
        return Results.success(knowledgeAccessService.listTeams(workshopId));
    }

    @PostMapping("/knowledge-access/workshops/{workshop-id}/teams")
    public Result<WorkshopTeamVO> createTeam(@PathVariable("workshop-id") String workshopId,
                                             @Valid @RequestBody WorkshopTeamCreateRequest request) {
        return Results.success(knowledgeAccessService.createTeam(workshopId, request));
    }

    @GetMapping("/knowledge-access/users/{user-id}/scopes")
    public Result<List<UserDataScopeVO>> listUserScopes(@PathVariable("user-id") String userId) {
        return Results.success(knowledgeAccessService.listUserScopes(userId));
    }

    @PutMapping("/knowledge-access/users/{user-id}/scopes")
    public Result<Void> replaceUserScopes(@PathVariable("user-id") String userId,
                                          @Valid @RequestBody UserDataScopeReplaceRequest request) {
        knowledgeAccessService.replaceUserScopes(userId, request);
        return Results.success();
    }

    @GetMapping("/knowledge-access/{resource-type}/{resource-id}/scopes")
    public Result<List<KnowledgeResourceScopeVO>> listResourceScopes(
            @PathVariable("resource-type") KnowledgeResourceType resourceType,
            @PathVariable("resource-id") String resourceId) {
        return Results.success(knowledgeAccessService.listResourceScopes(resourceType, resourceId));
    }

    @PutMapping("/knowledge-access/{resource-type}/{resource-id}/scopes")
    public Result<Void> replaceResourceScopes(
            @PathVariable("resource-type") KnowledgeResourceType resourceType,
            @PathVariable("resource-id") String resourceId,
            @Valid @RequestBody KnowledgeResourceScopeReplaceRequest request) {
        knowledgeAccessService.replaceResourceScopes(resourceType, resourceId, request);
        return Results.success();
    }
}
