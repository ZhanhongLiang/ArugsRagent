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

package com.nageoffer.ai.ragent.ingestion.engine;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于节点连线定义执行文档摄取流水线的引擎。
 *
 * <p>引擎不实现具体抓取、解析或索引能力，而是负责：校验管道图、定位起始节点、按 nextNodeId 串行调度、
 * 执行条件分支，并把每个节点的耗时、输出摘要和异常写入 IngestionContext。</p>
 */
@Slf4j
@Component
public class IngestionEngine {

    /** 节点类型到 Spring Bean 的运行时路由表。 */
    private final Map<String, IngestionNode> nodeMap;
    /** 解析节点配置中的执行条件。 */
    private final ConditionEvaluator conditionEvaluator;
    private final NodeOutputExtractor outputExtractor;

    /**
     * 收集全部节点实现并构建类型索引。
     * 管道配置中不存在的节点类型会在 executeNode 阶段明确报错，而不是静默跳过。
     */
    public IngestionEngine(
            List<IngestionNode> nodes,
            ConditionEvaluator conditionEvaluator,
            NodeOutputExtractor outputExtractor) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
    }

    /**
     * 执行给定管道，并将最终状态写回上下文。
     * 运行前先校验图结构，避免部分执行后才发现环路或悬空 nextNodeId。
     */
    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        // 支持调用方复用 Context；未初始化时才创建日志列表。
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }
        context.setStatus(IngestionStatus.RUNNING);

        // 以 nodeId 建索引，后续执行和校验均为常数时间查找。
        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());

        // 先检出环路和不存在的后继节点，防止任务执行到一半才失败。
        validatePipeline(nodeConfigMap);

        // 起始节点定义为“没有被任何其它节点 nextNodeId 引用”的节点。
        String startNodeId = findStartNode(nodeConfigMap);
        if (StrUtil.isBlank(startNodeId)) {
            throw new ClientException("流水线未找到起始节点");
        }

        log.info("流水线从节点开始执行: {}", startNodeId);

        // 当前实现是单链执行；条件节点通过返回结果控制是否继续，不做并行分支扇出。
        executeChain(startNodeId, nodeConfigMap, context);

        // 只有没有节点将状态改为失败时，才将自然走完的流水线标记为完成。
        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }
        return context;
    }

    /** 将管道节点列表转为 nodeId 索引；空配置以空 Map 表示。 */
    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        if (nodes == null) {
            return Collections.emptyMap();
        }
        return nodes.stream()
                .collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }

    /**
     * 校验每个单链路径不存在环，并确保所有 nextNodeId 都指向已定义节点。
     * path 记录当前遍历路径用于检测环，visited 避免已校验链重复扫描。
     */
    private void validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> visited = new HashSet<>();

        for (String nodeId : nodeConfigMap.keySet()) {
            if (visited.contains(nodeId)) {
                continue;
            }

            // 每个新路径独立记录，重遇 path 中节点就说明存在循环引用。
            Set<String> path = new HashSet<>();
            String current = nodeId;

            while (current != null) {
                if (path.contains(current)) {
                    throw new ClientException("流水线存在环: " + current);
                }

                path.add(current);
                visited.add(current);

                NodeConfig config = nodeConfigMap.get(current);
                if (config == null) {
                    break;
                }

                // 有 nextNodeId 时必须能解析到节点，避免运行期静默断链。
                String nextId = config.getNextNodeId();
                if (StringUtils.hasText(nextId)) {
                    if (!nodeConfigMap.containsKey(nextId)) {
                        throw new ClientException("找不到下一个节点: " + nextId + "，被节点 " + current + " 引用");
                    }
                    current = nextId;
                } else {
                    break;
                }
            }
        }
    }

    /** 找到未被引用的节点作为执行入口；没有入口通常说明全图成环。 */
    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> referencedNodes = nodeConfigMap.values().stream()
                .map(NodeConfig::getNextNodeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        return nodeConfigMap.keySet().stream()
                .filter(nodeId -> !referencedNodes.contains(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从给定 nodeId 沿 nextNodeId 串行执行。
     * maxNodes 是运行时防御性兜底，即使配置校验被绕过也不会无限循环。
     */
    private void executeChain(
            String nodeId,
            Map<String, NodeConfig> nodeConfigMap,
            IngestionContext context) {

        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = nodeConfigMap.size();

        while (currentNodeId != null) {
            // 防止无限循环；正常情况下 validatePipeline 已提前消除这种配置。
            if (executedCount++ > maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }

            // 运行期仍防御性判断配置缺失，避免异常数据导致 NPE。
            NodeConfig config = nodeConfigMap.get(currentNodeId);
            if (config == null) {
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }

            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);

            // 节点失败立即终止整条链，并把异常沉淀到 Context 供任务服务持久化。
            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());

                break;
            }

            // terminate 与 fail 不同：它是成功完成但业务上要求停止后续节点。
            if (!result.isShouldContinue()) {
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }

            // 当前节点正常结束后沿配置连线继续。
            currentNodeId = config.getNextNodeId();
        }

        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    /**
     * 执行单个节点并无论成功、跳过或异常都追加 NodeLog。
     * 节点内部异常被转换为 NodeResult.fail，保证上层执行链只处理一种返回协议。
     */
    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        // nodeType 决定具体实现，nodeId 仅标识该实现的一次配置实例。
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        // 条件不满足是正常跳过：记录成功日志并继续下一节点，不把它视为任务失败。
        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
                NodeResult skip = NodeResult.skip("条件未满足");
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)
                        .success(true)
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());
                return skip;
            }
        }

        // 以毫秒记录实际节点耗时，便于定位摄取慢在下载、解析、Embedding 还是写向量库。
        long start = System.currentTimeMillis();
        try {
            NodeResult result = node.execute(context, nodeConfig);
            long duration = System.currentTimeMillis() - start;

            // 输出提取器只保留可展示摘要，避免把整份文档、向量等大对象塞进任务日志。
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(result.getMessage())
                    .durationMs(duration)
                    .success(result.isSuccess())
                    .error(result.getError() == null ? null : result.getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());

            log.info("节点 {} 执行完成，耗时 {}ms: {}", nodeId, duration, result.getMessage());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(e.getMessage())
                    .durationMs(duration)
                    .success(false)
                    .error(e.getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
            log.error("节点 {} 执行失败，耗时 {}ms", nodeId, duration, e);
            return NodeResult.fail(e);
        }
    }
}
