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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Service
@RequiredArgsConstructor
/**
 * 默认上下文格式化器。
 *
 * <p>负责把检索分块和工具结果格式化成模型容易阅读、可引用的 Prompt 片段。</p>
 */
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    /**
     * 这个方法的作用是：把多个意图命中的检索结果，整理成一段可读的上下文文本，塞进最终 Prompt 给大模型回答用。
     * 它一般处理的是这种场景：
     * 用户问题同时命中了多个知识库意图
     * 比如：
     * “这款产品适合什么人？售后政策怎么样？”
     *
     * 可能命中：
     * 1. 商品介绍意图
     * 2. 售后政策意图
     * formatMultiIntentContext(...) 里面几个参数可以这样理解：
     * List<NodeScore> kbIntents
     * 表示这次识别出来的多个 KB 类型意图，比如商品知识库、售后知识库、促销知识库。
     * Map<String, List<RetrievedChunk>> rerankedByIntent
     * 表示每个意图对应的检索结果，而且通常已经经过 rerank 排序了。
     * 大概结构是：
     * {
     *   "product_info": [chunk1, chunk2],
     *   "after_sales": [chunk3, chunk4]
     * }
     * int topK
     * 表示最终每个意图或者整体最多取多少条 Chunk，避免 Prompt 太长。
     * 所以这个方法做的事情大概是：
     * 1. 遍历多个命中的意图
     * 2. 找到每个意图对应的 rerank 后 Chunk
     * 3. 按 topK 截断
     * 4. 给每组 Chunk 加上意图名称/标题
     * 5. 拼成一段结构化上下文
     * 6. 返回给 Prompt 组装逻辑
     * 最终效果类似：
     * 【商品介绍】
     * 1. xxx
     * 2. xxx
     *
     * 【售后政策】
     * 1. xxx
     * 2. xxx
     * 它解决的问题是：
     * 多意图检索时，不能把所有 Chunk 混在一起直接丢给模型，否则模型不知道哪些内容属于哪个业务方向。formatMultiIntentContext 会按意图分组，把上下文组织得更清楚，让模型回答组合问题时更稳定。
     * @param kbIntents        知识库意图节点及其得分列表
     * @param rerankedByIntent 按意图分组的重排序后检索文档块
     * @param topK             每个意图下保留的最大文档块数量
     * @return
     */
    @Override
    public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        if (rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }
        if (CollUtil.isEmpty(kbIntents)) {
            return formatChunksWithoutIntent(rerankedByIntent, topK);
        }
        if (kbIntents.size() > 1) { // 如果命中的意图叶子节点超1个
            return formatMultiIntentContext(kbIntents, rerankedByIntent, topK);
        }
        return formatSingleIntentContext(kbIntents.get(0), rerankedByIntent, topK);
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        List<RetrievedChunk> chunks = rerankedByIntent.get(nodeScore.getNode().getId());
        if (CollUtil.isEmpty(chunks)) {
            return "";
        }
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        String body = joinChunkTexts(chunks, topK);
        return renderKbSection(renderSnippetRules(snippet), body);
    }

    /**
     * 格式化多意图上下文， 多意图叶子节点使用
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        String snippetSection = "";
        if (!snippets.isEmpty()) {
            String numberedRules = IntStream.range(0, snippets.size())
                    .mapToObj(i -> (i + 1) + ". " + snippets.get(i))
                    .collect(Collectors.joining("\n"));
            snippetSection = renderSnippetRules(numberedRules);
        }

        // 2. 合并所有意图的文档片段（去重）
        List<RetrievedChunk> allChunks = rerankedByIntent.values().stream()
                .flatMap(List::stream)
                .distinct()
                .limit(topK)
                .toList();

        if (allChunks.isEmpty()) {
            return snippetSection;
        }

        String body = allChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        return renderKbSection(snippetSection, body);
    }

    private String formatChunksWithoutIntent(Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : rerankedByIntent.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
                if (chunks.size() >= limit) {
                    break;
                }
            }
            if (chunks.size() >= limit) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }

        String body = chunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        return renderKbSection("", body);
    }

    /**
     * 1. 根据 mcpIntents 建立 toolId -> IntentNode 映射
     * 2. 根据 toolId 找到对应的 CallToolResult 列表
     * 3. 提取 CallToolResult 里的文本内容
     * 4. 成功结果放进 <data>
     * 5. 失败结果放进 <errors>
     * 6. 如果意图节点配置了 promptSnippet，就放进 <rules>
     * 7. 最后拼成一段给大模型看的上下文
     * 例子：
     * 工具 sales_query 返回：
     * 华东区本月销售额为 120 万，订单数 86 单。
     * 意图节点配置：
     * promptSnippet = 回答销售数据问题时，需要说明统计周期和关键指标。
     * 格式化后类似：
     * <rules>
     * 回答销售数据问题时，需要说明统计周期和关键指标。
     * </rules>
     *
     * <data>
     * 华东区本月销售额为 120 万，订单数 86 单。
     * </data>
     * 如果工具失败：
     * 远程调用失败: connection refused
     * 会格式化成：
     * <errors>
     * - 工具调用失败: 远程调用失败: connection refused
     * </errors>
     * 一句话总结：
     * formatMcpContext 的作用是把 MCP 工具返回的成功/失败结果，按工具和意图规则整理成结构化文本，让大模型能基于实时业务数据生成最终回答。
     * @param toolResults MCP 工具调用结果，按工具名称分组
     * @param mcpIntents  MCP 意图节点及其得分列表
     * @return
     */
    @Override
    public String formatMcpContext(Map<String, List<CallToolResult>> toolResults,
                                   List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(toolResults)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mergeAllResultsToText(toolResults);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<CallToolResult> results = toolResults.get(entry.getKey());
                    if (CollUtil.isEmpty(results)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mergeResultsToText(results);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    String snippetSection = StrUtil.isNotBlank(snippet)
                            ? templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-intent-rules", Map.of("rules", snippet))
                            : "";
                    return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-section", Map.of(
                            "snippet_section", snippetSection,
                            "body", body
                    ));
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    // ==================== 工具方法 ====================

    private String renderKbSection(String snippetSection, String chunksBody) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "chunks_body", chunksBody
        ));
    }

    private String renderSnippetRules(String snippet) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "snippet-rules", Map.of("rules", snippet));
    }

    private String joinChunkTexts(List<RetrievedChunk> chunks, int topK) {
        return chunks.stream()
                .limit(topK)
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
    }

    private String mergeAllResultsToText(Map<String, List<CallToolResult>> toolResults) {
        List<CallToolResult> allResults = toolResults.values().stream()
                .flatMap(List::stream)
                .toList();
        return mergeResultsToText(allResults);
    }

    /**
     * 将多个 CallToolResult 合并为文本
     */
    private String mergeResultsToText(List<CallToolResult> results) {
        if (CollUtil.isEmpty(results)) {
            return "";
        }

        List<String> successTexts = new ArrayList<>();
        List<String> errorTexts = new ArrayList<>();

        for (CallToolResult result : results) {
            boolean isError = result.isError() != null && result.isError();
            String text = extractTextContent(result);
            if (!isError && text != null) {
                successTexts.add(text);
            } else if (isError && text != null) {
                errorTexts.add("- 工具调用失败: " + text);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String text : successTexts) {
            sb.append(text).append("\n\n");
        }

        if (CollUtil.isNotEmpty(errorTexts)) {
            String errorList = String.join("\n", errorTexts);
            sb.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-error", Map.of("error_list", errorList)));
        }

        return sb.toString().trim();
    }

    private String extractTextContent(CallToolResult result) {
        if (result == null || result.content() == null) {
            return null;
        }
        List<String> texts = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }
}
