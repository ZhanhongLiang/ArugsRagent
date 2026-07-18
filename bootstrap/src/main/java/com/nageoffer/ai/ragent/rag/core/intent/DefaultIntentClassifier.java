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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.knowledge.access.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;
/**
 * 默认意图分类器。
 *
 * <p>它基于意图树逐层打分，把用户问题匹配到业务知识库节点、MCP 工具节点或系统节点。
 * 这个组件只负责打分和排序，不负责后续检索和工具执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIntentClassifier implements IntentClassifier, IntentNodeRegistry {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final KnowledgeAccessService knowledgeAccessService;

    /**
     * 从Redis加载意图树并构建内存结构
     * 每次调用都会重新从Redis读取，确保数据是最新的
     */
    private IntentTreeData loadIntentTreeData() {
        // 1. 从Redis读取（如果不存在会自动从数据库加载）
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();

        // 2. 如果Redis也没有，从数据库加载并缓存
        if (CollUtil.isEmpty(roots)) {
            roots = loadIntentTreeFromDB(); // 从数据库加载
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
            }
        }

        // 3. 构建内存结构（临时使用）
        if (CollUtil.isEmpty(roots)) {
            return new IntentTreeData(List.of(), List.of(), Map.of());
        }
        // 扁平化所有节点
        List<IntentNode> allNodes = flatten(roots); // 扁平化处理，其实就是存在栈中
        List<IntentNode> leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf) // 找到所有叶子节点
                .collect(Collectors.toList());
        Map<String, IntentNode> id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.debug("意图树数据加载完成, 总节点数: {}, 叶子节点数: {}", allNodes.size(), leafNodes.size());

        return new IntentTreeData(allNodes, leafNodes, id2Node);
    }

    @Override
    public IntentNode getNodeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        IntentTreeData data = loadIntentTreeData();
        return data.id2Node.get(id);
    }

    /**
     * 意图树数据结构（临时对象，不持久化）
     */
    private record IntentTreeData(
            List<IntentNode> allNodes,
            List<IntentNode> leafNodes,
            Map<String, IntentNode> id2Node
    ) {
    }
    // 扁平化
    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            // 将节点存在栈中
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 对所有"叶子分类节点"做意图识别，由 LLM 输出每个分类的 score
     * - 返回结果已按 score 从高到低排序
     */
    @Override
    public List<NodeScore> classifyTargets(String question) {
        return classifyTargets(question, knowledgeAccessService.currentAccessScope());
    }

    @Override
    public List<NodeScore> classifyTargets(String question, KnowledgeAccessScope accessScope) {
        // 每次都从Redis读取最新数据, 先尝试从Redis命中树节点
        IntentTreeData data = loadIntentTreeData(); // 获得record的IntentTreeData, 包含所有节点、叶子节点, id:所有节点

        List<IntentNode> candidateNodes = filterReadableLeafNodes(data.leafNodes, accessScope);
        if (candidateNodes.isEmpty()) {
            log.info("当前用户没有可参与分类的意图叶子节点，跳过意图识别");
            return List.of();
        }
        Map<String, IntentNode> candidateNodeById = candidateNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, node -> node));
        String systemPrompt = buildPrompt(candidateNodes); // 构造提示词
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build(); // 构造caht请求参数

        String raw = llmService.chat(request); // 调用LLM

        try {
            // 移除可能的 markdown 代码块标记
            // LLM返回的JSON的五层容错机制
            /**
             * 有些模型会自动给 JSON 输出加上 ````json` 和 ````` 围栏。对人类来说这是友好的格式化，对代码来说就是多了两行需要剥掉的噪音。两个正则，一个去头一个去尾，干净利落。
             */
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw); // markdown代码块清理
            /**
             * 正常情况 LLM 直接返回 `[...]` 数组。但有些模型倾向于在外面包一层对象：`{"results": [...]}`。
             * 代码兼容这两种格式——是数组就直接用，是对象就取 `results` 字段。如果两种都不是，打 warn 日志返回空列表。
             */
            JsonElement root = JsonParser.parseString(cleanedRaw);

            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
                // 容错：如果模型外面又包了一层 { "results": [...] }
                arr = root.getAsJsonObject().getAsJsonArray("results");
            } else {
                log.warn("LLM 返回了非预期的 JSON 格式, 原始响应: {}", raw);
                return List.of();
            }

            List<NodeScore> scores = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                /**
                 * 遍历数组时，如果某个元素不是 JSON 对象，跳过；
                 * 如果缺少 `id` 或 `score` 字段，也跳过。不因为一个坏元素丢掉整批结果。
                 * 假设 LLM 返回了 3 个元素，其中 1 个格式有问题，另外 2 个没问题——那就用那 2 个，
                 * 而不是整个解析失败。
                 */
                if (!obj.has("id") || !obj.has("score")) continue;

                String id = obj.get("id").getAsString();
                double score = obj.get("score").getAsDouble();
                /**
                 * LLM 返回的 `id` 在 `id2Node` 查找表中找不到。可能的原因：LLM 编造了一个不存在的 ID，
                 * 或者拼写有偏差。跳过并打 warn 日志——日志里记录了 LLM 编造的 ID，方便排查 Prompt 是不是需要调整。
                 */
                IntentNode node = candidateNodeById.get(id);
                if (node == null) {
                    log.warn("LLM 返回了未知的意图节点 ID: {}, 已跳过", id);
                    continue;
                }

                scores.add(new NodeScore(node, score));
            }

            // 降序排序
            scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

            log.info("当前问题：{}\n意图识别树如下所示：{}\n",
                    question,
                    JSONUtil.toJsonPrettyStr(
                            scores.stream().peek(each -> {
                                IntentNode node = each.getNode();
                                node.setChildren(null);
                            }).collect(Collectors.toList())
                    )
            );
            return scores;
        } catch (Exception e) {
            log.warn("解析 LLM 响应失败, 原始内容: {}", raw, e);
            return List.of();
        }
    }

    /**
     * 系统和 MCP 节点不依赖知识库；KB 节点必须先通过数据范围过滤，
     * 避免未授权知识库的名称、描述与示例被拼入发给模型的分类 Prompt。
     */
    private List<IntentNode> filterReadableLeafNodes(List<IntentNode> leafNodes,
                                                      KnowledgeAccessScope accessScope) {
        return leafNodes.stream()
                .filter(node -> !node.isKB()
                        || accessScope.canReadCollection(node.getCollectionName()))
                .toList();
    }

    /**
     * 方便使用：
     * - 只取前 topN
     * - 过滤掉 score < minScore 的分类
     */
    @Override
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出所有【叶子节点】的 id / 路径 / 描述 / 示例问题
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     * - 特别强调：如果问题里只提到 "OA系统"，不要选 "保险系统" 的分类
     * - 如果存在 MCP 类型节点，使用增强版 Prompt 并添加 type/toolId 标识
     */
    private String buildPrompt(List<IntentNode> leafNodes) {
        StringBuilder sb = new StringBuilder();

        for (IntentNode node : leafNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");
            sb.append("  description=").append(node.getDescription()).append("\n");

            // 添加节点类型标识（V3 Enterprise 支持 MCP）
            if (node.isMCP()) {
                sb.append("  type=MCP\n");
                if (node.getMcpToolId() != null) {
                    sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
                }
            } else if (node.isSystem()) {
                sb.append("  type=SYSTEM\n");
            } else {
                sb.append("  type=KB\n");
            }

            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return promptTemplateLoader.render(
                INTENT_CLASSIFIER_PROMPT_PATH,
                Map.of("intent_list", sb.toString())
        );
    }
    // 从数据库加载意图树
    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除且已启用的节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // 2. DO -> IntentNode（第一遍：先把所有节点建出来，放到 map 里）
        Map<String, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);
            // 数据库中的 code 映射到 IntentNode 的 id/parentId
            node.setId(each.getIntentCode());
            node.setParentId(each.getParentCode());
            node.setMcpToolId(each.getMcpToolId());
            node.setParamPromptTemplate(each.getParamPromptTemplate());
            // 确保 children 不为 null（避免后面 add NPE）
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            id2Node.put(node.getId(), node);
        }

        // 3. 第二遍：根据 parentId 组装 parent -> children
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId，当作根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                // 找不到父节点，兜底也当作根节点，避免节点丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // 4. 填充 fullPath（跟你原来的 fillFullPath 一样的逻辑）
        fillFullPath(roots, null);

        return roots;
    }

    /**
     * 填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 业务系统 > OA系统 > 系统介绍
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}
