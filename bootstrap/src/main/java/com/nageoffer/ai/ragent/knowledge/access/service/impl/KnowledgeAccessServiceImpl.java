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

import cn.hutool.core.bean.BeanUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.KnowledgeResourceScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.UserDataScopeReplaceRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.request.WorkshopTeamCreateRequest;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.KnowledgeResourceScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.UserDataScopeVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopTeamVO;
import com.nageoffer.ai.ragent.knowledge.access.controller.vo.WorkshopVO;
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
import com.nageoffer.ai.ragent.knowledge.access.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于“用户组织范围 + 知识资源 ACL”计算数据可见范围的默认实现。
 *
 * <p>角色只决定是否拥有管理员旁路；车间和班组不编码为 Sa-Token permission，
 * 而是由本服务统一解析成 {@link KnowledgeAccessScope}，供管理接口和检索层共同使用。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeAccessServiceImpl implements KnowledgeAccessService {

    private static final String ADMIN_ROLE = "admin";

    private final WorkshopMapper workshopMapper;
    private final WorkshopTeamMapper workshopTeamMapper;
    private final UserDataScopeMapper userDataScopeMapper;
    private final KnowledgeResourceScopeMapper resourceScopeMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final UserMapper userMapper;

    @Override
    public KnowledgeAccessScope currentAccessScope() {
        LoginUser user = UserContext.get();
        if (user == null) {
            return KnowledgeAccessScope.restricted(Set.of(), Set.of(), Set.of());
        }
        return resolveAccessScope(user.getUserId(), user.getRole());
    }

    @Override
    public KnowledgeAccessScope resolveAccessScope(String userId, String role) {
        if (ADMIN_ROLE.equalsIgnoreCase(role)) {
            return KnowledgeAccessScope.allAccess();
        }
        if (!StringUtils.hasText(userId)) {
            return KnowledgeAccessScope.restricted(Set.of(), Set.of(), Set.of());
        }

        UserOrganizationScope userScope = loadUserOrganizationScope(userId);
        List<KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        List<KnowledgeDocumentDO> documents = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (knowledgeBases.isEmpty()) {
            return KnowledgeAccessScope.restricted(Set.of(), Set.of(), Set.of());
        }

        Map<String, List<KnowledgeResourceScopeDO>> scopesByResource = resourceScopeMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeResourceScopeDO.class)
                                .in(KnowledgeResourceScopeDO::getResourceType,
                                        List.of(KnowledgeResourceType.KNOWLEDGE_BASE, KnowledgeResourceType.DOCUMENT)))
                .stream()
                .collect(Collectors.groupingBy(scope -> resourceKey(scope.getResourceType(), scope.getResourceId())));

        Map<String, KnowledgeBaseDO> knowledgeBaseById = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBaseDO::getId, Function.identity()));
        Set<String> readableKnowledgeBaseIds = new HashSet<>();
        Set<String> readableDocumentIds = new HashSet<>();

        for (KnowledgeDocumentDO document : documents) {
            List<KnowledgeResourceScopeDO> documentScopes = scopesByResource.getOrDefault(
                    resourceKey(KnowledgeResourceType.DOCUMENT, document.getId()), List.of());
            List<KnowledgeResourceScopeDO> effectiveScopes = documentScopes.isEmpty()
                    ? scopesByResource.getOrDefault(resourceKey(KnowledgeResourceType.KNOWLEDGE_BASE, document.getKbId()), List.of())
                    : documentScopes;
            if (matchesAny(effectiveScopes, userScope)) {
                readableDocumentIds.add(document.getId());
                readableKnowledgeBaseIds.add(document.getKbId());
            }
        }

        for (KnowledgeBaseDO knowledgeBase : knowledgeBases) {
            List<KnowledgeResourceScopeDO> scopes = scopesByResource.getOrDefault(
                    resourceKey(KnowledgeResourceType.KNOWLEDGE_BASE, knowledgeBase.getId()), List.of());
            if (matchesAny(scopes, userScope)) {
                readableKnowledgeBaseIds.add(knowledgeBase.getId());
            }
        }

        Set<String> readableCollectionNames = readableKnowledgeBaseIds.stream()
                .map(knowledgeBaseById::get)
                .filter(knowledgeBase -> knowledgeBase != null && StringUtils.hasText(knowledgeBase.getCollectionName()))
                .map(KnowledgeBaseDO::getCollectionName)
                .collect(Collectors.toSet());
        return KnowledgeAccessScope.restricted(readableKnowledgeBaseIds, readableCollectionNames, readableDocumentIds);
    }

    @Override
    public void requireReadableKnowledgeBase(String knowledgeBaseId) {
        if (!currentAccessScope().canReadKnowledgeBase(knowledgeBaseId)) {
            throw new ClientException("无权访问该知识库");
        }
    }

    @Override
    public void requireReadableDocument(String documentId) {
        if (!currentAccessScope().canReadDocument(documentId)) {
            throw new ClientException("无权访问该文档");
        }
    }

    @Override
    public void requireManageKnowledgeBase(String knowledgeBaseId) {
        requireKnowledgeManager();
    }

    @Override
    public void requireManageDocument(String documentId) {
        requireKnowledgeManager();
    }

    @Override
    public List<WorkshopVO> listWorkshops() {
        requireAccessManager();
        return workshopMapper.selectList(Wrappers.lambdaQuery(WorkshopDO.class)
                        .eq(WorkshopDO::getDeleted, 0)
                        .orderByAsc(WorkshopDO::getCode))
                .stream()
                .map(item -> BeanUtil.toBean(item, WorkshopVO.class))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkshopVO createWorkshop(WorkshopCreateRequest request) {
        requireAccessManager();
        String code = normalizeRequired(request.getCode(), "车间编码不能为空");
        String name = normalizeRequired(request.getName(), "车间名称不能为空");
        boolean exists = workshopMapper.exists(Wrappers.lambdaQuery(WorkshopDO.class)
                .eq(WorkshopDO::getCode, code)
                .eq(WorkshopDO::getDeleted, 0));
        if (exists) {
            throw new ClientException("车间编码已存在");
        }
        WorkshopDO workshop = WorkshopDO.builder()
                .code(code)
                .name(name)
                .enabled(1)
                .build();
        workshopMapper.insert(workshop);
        return BeanUtil.toBean(workshop, WorkshopVO.class);
    }

    @Override
    public List<WorkshopTeamVO> listTeams(String workshopId) {
        requireAccessManager();
        assertActiveWorkshop(workshopId);
        return workshopTeamMapper.selectList(Wrappers.lambdaQuery(WorkshopTeamDO.class)
                        .eq(WorkshopTeamDO::getWorkshopId, workshopId)
                        .eq(WorkshopTeamDO::getDeleted, 0)
                        .orderByAsc(WorkshopTeamDO::getCode))
                .stream()
                .map(item -> BeanUtil.toBean(item, WorkshopTeamVO.class))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkshopTeamVO createTeam(String workshopId, WorkshopTeamCreateRequest request) {
        requireAccessManager();
        assertActiveWorkshop(workshopId);
        String code = normalizeRequired(request.getCode(), "班组编码不能为空");
        String name = normalizeRequired(request.getName(), "班组名称不能为空");
        boolean exists = workshopTeamMapper.exists(Wrappers.lambdaQuery(WorkshopTeamDO.class)
                .eq(WorkshopTeamDO::getWorkshopId, workshopId)
                .eq(WorkshopTeamDO::getCode, code)
                .eq(WorkshopTeamDO::getDeleted, 0));
        if (exists) {
            throw new ClientException("该车间下班组编码已存在");
        }
        WorkshopTeamDO team = WorkshopTeamDO.builder()
                .workshopId(workshopId)
                .code(code)
                .name(name)
                .enabled(1)
                .build();
        workshopTeamMapper.insert(team);
        return BeanUtil.toBean(team, WorkshopTeamVO.class);
    }

    @Override
    public List<UserDataScopeVO> listUserScopes(String userId) {
        requireAccessManager();
        assertUserExists(userId);
        return userDataScopeMapper.selectList(Wrappers.lambdaQuery(UserDataScopeDO.class)
                        .eq(UserDataScopeDO::getUserId, userId)
                        .orderByAsc(UserDataScopeDO::getScopeType, UserDataScopeDO::getWorkshopId, UserDataScopeDO::getTeamId))
                .stream()
                .map(item -> BeanUtil.toBean(item, UserDataScopeVO.class))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceUserScopes(String userId, UserDataScopeReplaceRequest request) {
        requireAccessManager();
        assertUserExists(userId);
        List<UserDataScopeDO> scopes = new ArrayList<>();
        for (UserDataScopeReplaceRequest.Item item : nullSafe(request.getScopes())) {
            validateUserScopeItem(item);
            scopes.add(UserDataScopeDO.builder()
                    .userId(userId)
                    .scopeType(item.getScopeType())
                    .workshopId(item.getWorkshopId())
                    .teamId(item.getScopeType() == DataScopeType.TEAM ? item.getTeamId() : null)
                    .build());
        }
        userDataScopeMapper.delete(Wrappers.lambdaQuery(UserDataScopeDO.class)
                .eq(UserDataScopeDO::getUserId, userId));
        scopes.forEach(userDataScopeMapper::insert);
    }

    @Override
    public List<KnowledgeResourceScopeVO> listResourceScopes(KnowledgeResourceType resourceType, String resourceId) {
        requireAccessManager();
        assertResourceExists(resourceType, resourceId);
        return resourceScopeMapper.selectList(Wrappers.lambdaQuery(KnowledgeResourceScopeDO.class)
                        .eq(KnowledgeResourceScopeDO::getResourceType, resourceType)
                        .eq(KnowledgeResourceScopeDO::getResourceId, resourceId)
                        .orderByAsc(KnowledgeResourceScopeDO::getScopeType,
                                KnowledgeResourceScopeDO::getWorkshopId,
                                KnowledgeResourceScopeDO::getTeamId))
                .stream()
                .map(item -> BeanUtil.toBean(item, KnowledgeResourceScopeVO.class))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceResourceScopes(KnowledgeResourceType resourceType,
                                      String resourceId,
                                      KnowledgeResourceScopeReplaceRequest request) {
        requireAccessManager();
        assertResourceExists(resourceType, resourceId);
        List<KnowledgeResourceScopeDO> scopes = new ArrayList<>();
        for (KnowledgeResourceScopeReplaceRequest.Item item : nullSafe(request.getScopes())) {
            validateResourceScopeItem(item);
            scopes.add(KnowledgeResourceScopeDO.builder()
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .scopeType(item.getScopeType())
                    .workshopId(item.getScopeType() == KnowledgeResourceScopeType.GLOBAL ? null : item.getWorkshopId())
                    .teamId(item.getScopeType() == KnowledgeResourceScopeType.TEAM ? item.getTeamId() : null)
                    .createdBy(UserContext.getUsername())
                    .build());
        }
        resourceScopeMapper.delete(Wrappers.lambdaQuery(KnowledgeResourceScopeDO.class)
                .eq(KnowledgeResourceScopeDO::getResourceType, resourceType)
                .eq(KnowledgeResourceScopeDO::getResourceId, resourceId));
        scopes.forEach(resourceScopeMapper::insert);
    }

    private UserOrganizationScope loadUserOrganizationScope(String userId) {
        List<UserDataScopeDO> scopes = userDataScopeMapper.selectList(Wrappers.lambdaQuery(UserDataScopeDO.class)
                .eq(UserDataScopeDO::getUserId, userId));
        Set<String> activeWorkshopIds = workshopMapper.selectList(Wrappers.lambdaQuery(WorkshopDO.class)
                        .eq(WorkshopDO::getDeleted, 0)
                        .eq(WorkshopDO::getEnabled, 1))
                .stream()
                .map(WorkshopDO::getId)
                .collect(Collectors.toSet());
        Map<String, WorkshopTeamDO> activeTeamsById = workshopTeamMapper.selectList(
                        Wrappers.lambdaQuery(WorkshopTeamDO.class)
                                .eq(WorkshopTeamDO::getDeleted, 0)
                                .eq(WorkshopTeamDO::getEnabled, 1))
                .stream()
                .filter(team -> activeWorkshopIds.contains(team.getWorkshopId()))
                .collect(Collectors.toMap(WorkshopTeamDO::getId, Function.identity()));
        Set<String> workshopWideIds = scopes.stream()
                .filter(scope -> scope.getScopeType() == DataScopeType.WORKSHOP)
                .map(UserDataScopeDO::getWorkshopId)
                .filter(activeWorkshopIds::contains)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> teamIds = scopes.stream()
                .filter(scope -> scope.getScopeType() == DataScopeType.TEAM)
                .filter(scope -> activeWorkshopIds.contains(scope.getWorkshopId()))
                .map(UserDataScopeDO::getTeamId)
                .filter(activeTeamsById::containsKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return new UserOrganizationScope(workshopWideIds, teamIds);
    }

    private boolean matchesAny(Collection<KnowledgeResourceScopeDO> scopes, UserOrganizationScope userScope) {
        return scopes.stream().anyMatch(scope -> matches(scope, userScope));
    }

    private boolean matches(KnowledgeResourceScopeDO resourceScope, UserOrganizationScope userScope) {
        if (resourceScope.getScopeType() == KnowledgeResourceScopeType.GLOBAL) {
            return true;
        }
        if (resourceScope.getScopeType() == KnowledgeResourceScopeType.WORKSHOP) {
            return userScope.workshopWideIds().contains(resourceScope.getWorkshopId());
        }
        return userScope.workshopWideIds().contains(resourceScope.getWorkshopId())
                || userScope.teamIds().contains(resourceScope.getTeamId());
    }

    private void validateUserScopeItem(UserDataScopeReplaceRequest.Item item) {
        assertActiveWorkshop(item.getWorkshopId());
        if (item.getScopeType() == DataScopeType.WORKSHOP) {
            return;
        }
        if (!StringUtils.hasText(item.getTeamId())) {
            throw new ClientException("班组范围必须指定班组ID");
        }
        assertActiveTeamInWorkshop(item.getTeamId(), item.getWorkshopId());
    }

    private void validateResourceScopeItem(KnowledgeResourceScopeReplaceRequest.Item item) {
        if (item.getScopeType() == KnowledgeResourceScopeType.GLOBAL) {
            return;
        }
        if (!StringUtils.hasText(item.getWorkshopId())) {
            throw new ClientException("车间范围必须指定车间ID");
        }
        assertActiveWorkshop(item.getWorkshopId());
        if (item.getScopeType() == KnowledgeResourceScopeType.WORKSHOP) {
            return;
        }
        if (!StringUtils.hasText(item.getTeamId())) {
            throw new ClientException("班组范围必须指定班组ID");
        }
        assertActiveTeamInWorkshop(item.getTeamId(), item.getWorkshopId());
    }

    private void assertResourceExists(KnowledgeResourceType resourceType, String resourceId) {
        boolean exists;
        if (resourceType == KnowledgeResourceType.KNOWLEDGE_BASE) {
            exists = knowledgeBaseMapper.exists(Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                    .eq(KnowledgeBaseDO::getId, resourceId)
                    .eq(KnowledgeBaseDO::getDeleted, 0));
        } else {
            exists = documentMapper.exists(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                    .eq(KnowledgeDocumentDO::getId, resourceId)
                    .eq(KnowledgeDocumentDO::getDeleted, 0));
        }
        if (!exists) {
            throw new ClientException("知识资源不存在");
        }
    }

    private void assertUserExists(String userId) {
        if (!StringUtils.hasText(userId) || userMapper.selectById(userId) == null) {
            throw new ClientException("用户不存在");
        }
    }

    private void assertActiveWorkshop(String workshopId) {
        WorkshopDO workshop = workshopMapper.selectById(workshopId);
        if (workshop == null || workshop.getDeleted() != null && workshop.getDeleted() == 1
                || workshop.getEnabled() == null || workshop.getEnabled() != 1) {
            throw new ClientException("车间不存在或已停用");
        }
    }

    private void assertActiveTeamInWorkshop(String teamId, String workshopId) {
        WorkshopTeamDO team = workshopTeamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != null && team.getDeleted() == 1
                || team.getEnabled() == null || team.getEnabled() != 1
                || !workshopId.equals(team.getWorkshopId())) {
            throw new ClientException("班组不存在、已停用或不属于指定车间");
        }
    }

    private void requireKnowledgeManager() {
        UserContext.requireUser();
        StpUtil.checkPermission("knowledge:manage");
    }

    private void requireAccessManager() {
        UserContext.requireUser();
        StpUtil.checkPermission("knowledge:access:manage");
    }

    private String normalizeRequired(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ClientException(message);
        }
        return value.trim();
    }

    private String resourceKey(KnowledgeResourceType resourceType, String resourceId) {
        return resourceType.name() + ':' + resourceId;
    }

    private <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record UserOrganizationScope(Set<String> workshopWideIds,
                                         Set<String> teamIds) {
    }
}
