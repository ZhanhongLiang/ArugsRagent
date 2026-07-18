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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeBatchRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 意图树的管理端接口。
 *
 * <p>节点增删改后由 {@code IntentTreeService} 负责持久化并失效 Redis 树缓存，
 * 所以本控制器不能直接操作缓存或数据库实体。</p>
 */
@RestController
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    /**
     * 获取完整的意图节点树
     */
    @GetMapping("/intent-tree/trees")
    public Result<List<IntentNodeTreeVO>> tree() {
        // 返回嵌套树供管理页渲染，而不是让前端根据 parentId 自行拼装。
        return Results.success(intentTreeService.getFullTree());
    }

    /**
     * 创建意图节点
     */
    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        // 服务层负责校验父节点、知识库或 MCP 工具绑定关系。
        return Results.success(intentTreeService.createNode(requestParam));
    }

    /**
     * 更新意图节点
     */
    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable String id, @RequestBody IntentNodeUpdateRequest requestParam) {
        // 路径 ID 是更新目标，避免以请求体 ID 作为可信定位条件。
        intentTreeService.updateNode(id, requestParam);
    }

    /**
     * 删除意图节点
     */
    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
    }

    /**
     * 批量启用节点
     */
    @PostMapping("/intent-tree/batch/enable")
    public void batchEnable(@RequestBody IntentNodeBatchRequest requestParam) {
        // 批量状态操作仍通过服务层统一刷新缓存。
        intentTreeService.batchEnableNodes(requestParam.getIds());
    }

    /**
     * 批量停用节点
     */
    @PostMapping("/intent-tree/batch/disable")
    public void batchDisable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDisableNodes(requestParam.getIds());
    }

    /**
     * 批量删除节点
     */
    @PostMapping("/intent-tree/batch/delete")
    public void batchDelete(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDeleteNodes(requestParam.getIds());
    }
}
