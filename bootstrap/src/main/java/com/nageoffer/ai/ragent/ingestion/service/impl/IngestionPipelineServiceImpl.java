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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 可配置摄取流水线的管理服务。
 *
 * <p>流水线主表保存名称和描述，节点表保存节点类型、连线和 JSON 参数；
 * 运行时再将两者组装为 {@link PipelineDefinition} 交给摄取引擎执行。</p>
 */
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        // 主表先插入以获得 pipelineId，再保存节点，保证所有节点都属于同一流水线。
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            // 数据库唯一约束是并发创建同名流水线时的最终防线。
            throw new ClientException("流水线名称已存在");
        }
        upsertNodes(pipeline.getId(), request.getNodes());
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        // 节点列表未传时只更新主表；传空列表则明确删除全部节点。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        if (request.getNodes() != null) {
            upsertNodes(pipeline.getId(), request.getNodes());
        }
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    public IngestionPipelineVO get(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword) {
        // 每条主记录都加载自身节点，列表页因此能直接展示配置概览。
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pipelineId) {
        // 主表和节点表同一事务删除，避免留下无法运行的节点孤儿记录。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        pipeline.setDeleted(1);
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.deleteById(pipeline);

        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipeline.getId());
        nodeMapper.delete(qw);
    }

    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        // 这是运行时入口：持久化 JSON 需要转换回引擎认识的 NodeConfig。
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    private void upsertNodes(String pipelineId, List<IngestionPipelineNodeRequest> nodes) {
        if (nodes == null) {
            return;
        }
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId);
        // 当前接口采用“全量替换节点图”语义，避免局部更新时遗留已移除的连线。
        nodeMapper.delete(qw);
        for (IngestionPipelineNodeRequest node : nodes) {
            if (node == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(node.getNodeId())
                    // 入库时严格校验节点类型，配置错误应在管理阶段暴露而非运行时才失败。
                    .nodeType(normalizeNodeType(node.getNodeType()))
                    .nextNodeId(node.getNextNodeId())
                    .settingsJson(toJson(node.getSettings()))
                    .conditionJson(toJson(node.getCondition()))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    private List<IngestionPipelineNodeDO> fetchNodes(String pipelineId) {
        // 读取时排除逻辑删除节点，保证运行定义与管理展示一致。
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        return vo;
    }

    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        return vo;
    }

    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        // JSON 解析失败会得到 null，具体节点可按自己的默认设置处理。
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .nextNodeId(node.getNextNodeId())
                .build();
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            // 历史脏配置不阻断列表读取；真正执行时由节点配置校验给出业务错误。
            return null;
        }
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    private String normalizeNodeTypeForOutput(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }
}
