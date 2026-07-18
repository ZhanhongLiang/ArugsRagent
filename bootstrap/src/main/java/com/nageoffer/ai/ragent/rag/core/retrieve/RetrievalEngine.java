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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.mcp.McpParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final McpParameterExtractor mcpParameterExtractor;
    private final McpToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor; // ThreadPoolExecutorConfig定义了线程池, 维护高并发
    private final Executor mcpBatchExecutor; // ThreadPoolExecutorConfig定义了线程池，维护高并发

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        return retrieve(subIntents, topK, null);
    }

    /**
     * 在同一请求中将权限快照传递给每个并发子问题的知识库检索。
     */
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents,
                                     int topK,
                                     KnowledgeAccessScope accessScope) {
        // 校验问题是否是空的
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : searchProperties.getDefaultTopK();
        // 每个子问题用线程并发执行
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream() //还是并发执行，确保每个子问题同时开始
                .map(si -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return buildSubQuestionContext(
                                        si,
                                        resolveSubQuestionTopK(si, finalTopK),
                                        accessScope
                                );
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", si.subQuestion(), e);
                                return new SubQuestionContext(si.subQuestion(), "", "", Map.of());
                            }
                        },
                        ragContextExecutor // 专用线程池, 这个是外层线程池
                ))
                .toList();
        // 最后合并不同线程的子问题上下文
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        Map<String, List<RetrievedChunk>> mergedIntentChunks = new HashMap<>();
        // 把每个  子意图：chunks列表  将所有子问题的intentChunks扁平化，放在一个列表里面
        for (SubQuestionContext context : contexts) {
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        boolean singleQuestion = contexts.size() == 1;
        String kbContext;
        String mcpContext;

        if (singleQuestion) {
            SubQuestionContext only = contexts.get(0);
            kbContext = StrUtil.emptyIfNull(only.kbContext()).trim();
            mcpContext = StrUtil.emptyIfNull(only.mcpContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            StringBuilder mcpBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                boolean hasKb = StrUtil.isNotBlank(context.kbContext());
                boolean hasMcp = StrUtil.isNotBlank(context.mcpContext());
                if (hasKb || hasMcp) {
                    globalIndex++;
                }
                if (hasKb) {
                    appendSection(kbBuilder, "sub-question-kb-wrapper", globalIndex, context.question(), context.kbContext());
                }
                if (hasMcp) {
                    appendSection(mcpBuilder, "sub-question-mcp-wrapper", globalIndex, context.question(), context.mcpContext());
                }
            }
            // 相当于把kbBuilder和mcpBuilder里面添加section、globalIndex、context的子问题、上下文结果
            kbContext = kbBuilder.toString().trim();
            mcpContext = mcpBuilder.toString().trim();
        }
        // 相当于把多个子问题的MCP上下文、kb上下文、子意图chunks列表
        return RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .build();
    }

    /**
     * NodeScoreFilters` 是分流的关键工具类，提供了三个静态方法：`mcp()` 过滤 MCP 意图，`kb()` 过滤 KB 意图，`
     * kb(scores, minScore)` 在过滤 KB 意图的基础上再加一道分数门槛。
     * 后面讲 `IntentDirectedSearchChannel` 的时候会用到带 `minScore` 的重载版本——不是所有 KB 意图都值得走一次定向检索，
     * 分数太低的直接过滤掉。
     * @param intent
     * @param topK
     * @return
     */
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent,
                                                        int topK,
                                                        KnowledgeAccessScope accessScope) {
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores()); // 过滤 KB 类型意图（node 非空、kind 为 null 或 KB）
        List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores()); //过滤 MCP 类型意图（node 非空、kind=MCP、mcpToolId 非空）
        // 检索主要入口, 重点分析这里, 最后得到包装后的KbResult结果,
        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK, accessScope);
        // MCP工具主要调用入口
        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";
        // 将mcpContext上下文、kbResult检索上下文、kbResult的节点：chunks列表、intent的子问题(因为是并行，所以这里是一个子问题)
        // 包装成了SubQuestionContext上下文这种
        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return NodeScoreFilters.kb(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    private void appendSection(StringBuilder builder, String section, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, section, Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }
    // MCP入口
    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }
        // 主要入口， 返回toolID 和 List<CallToolResult>形式， 因为一个toolID有可能对应多个执行结果
        Map<String, List<CallToolResult>> toolResults = executeMcpTools(question, mcpIntents);
        if (toolResults.isEmpty()) {
            return "";
        }
        // 根据MCP工具调用结果+MCP意图叶子节点进行上下文拼接
        // 最终得到LLM可以调用的MCP上下文信息
        return contextFormatter.formatMcpContext(toolResults, mcpIntents);
    }

    /**
     *
     * @param intent
     * @param kbIntents
     * @param topK
     * @return
     */
    private KbResult retrieveAndRerank(SubQuestionIntent intent,
                                       List<NodeScore> kbIntents,
                                       int topK,
                                       KnowledgeAccessScope accessScope) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        /**
         * KB 意图的检索不是 `RetrievalEngine` 自己做的，而是委托给了 `MultiChannelRetrievalEngine`（多通道检索引擎）。
         * 这个引擎内部维护了两个检索通道（`SearchChannel`），它们根据意图的置信度动态决定是否激活：
         *
         * - `IntentDirectedSearchChannel`（意图定向检索，优先级 1）：根据意图节点的 `collectionName` 做精准检索，最快最准
         * - `VectorGlobalSearchChannel`（向量全局检索，优先级 10）：在所有 Collection 中搜索，作为兜底安全网
         *
         * 两个通道不是二选一——根据条件，可能只激活定向检索，可能只激活全局检索，也可能两个同时激活。
         */
        // 经过意图向量检索+全局向量检索+全局关键词检索 + 去重 + Rerank(TopK)最后得到某个子问题的候选切片数
        List<RetrievedChunk> chunks = accessScope == null
                ? multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK)
                : multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK, accessScope);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文），意图子节点ID : List<RetrievedChunk>
        Map<String, List<RetrievedChunk>> intentChunks = new HashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }
        // 合并意图叶子节点中的前端人工填写的规则
        // 将kbIntents 意图子节点、 意图子节点(是之前经过意图识别后的也意图子节点)传入
        //多意图检索时，不能把所有 Chunk 混在一起直接丢给模型，否则模型不知道哪些内容属于哪个业务方向。
        // formatMultiIntentContext 会按意图分组，把上下文组织得更清楚，让模型回答组合问题时更稳定。
        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    /**
     * 执行 MCP 工具调用，返回按 toolId 分组的结果
     * - 并行调用**：多个 MCP 意图通过 `CompletableFuture + mcpBatchExecutor` 并行调用，
     * 和 KB 检索用的是不同的线程池（`mcpBatchExecutor` vs `ragContextExecutor`），互不干扰。
     * - **单工具失败不阻断**：`try-catch` 在每个 future 内部，一个工具调用失败返回失败的 `ToolOutput`，其他工具不受影响。
     *
     */
    // 返回<toolID, List<CallToolResult>>的结果
    private Map<String, List<CallToolResult>> executeMcpTools(String question,
                                                              List<NodeScore> mcpIntentScores) {
        if (CollUtil.isEmpty(mcpIntentScores)) {
            return Map.of();
        }
        // 打分节点流，还是并发操作，就是有可能一个子问题存在两个MCP意图
        // MCP也是并发的，线程
        List<CompletableFuture<ToolOutput>> futures = mcpIntentScores.stream()
                .map(ns -> CompletableFuture.supplyAsync(
                        () -> {
                            String toolId = ns.getNode().getMcpToolId();
                            try { // 并发内部try兜底
                                CallToolResult result = executeSingleMcpTool(question, ns.getNode());
                                // 改toolID需要和mcp-server里面的toolID保持一致对齐
                                return result == null ? null : new ToolOutput(toolId, result);
                            } catch (Exception e) {
                                log.error("MCP 工具调用异常, toolId: {}", toolId, e);
                                return new ToolOutput(toolId, CallToolResult.builder()
                                        .content(List.of(new TextContent("工具调用异常: " + e.getMessage())))
                                        .isError(true)
                                        .build());
                            }
                        },
                        mcpBatchExecutor // 已经定义好了线程内部参数，在config文件中
                ))
                .toList();
        // 所以相当于最后是1个toolID有可能有多种MCP执行结果
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ToolOutput::toolId,
                        Collectors.mapping(ToolOutput::result, Collectors.toList())
                ));
    }

    /**
     * `McpParameterExtractor.extractParameters()` 会调用一次 LLM，
     * 让模型根据工具的参数 Schema 和用户问题提取出参数。比如用户问“帮我查订单 2024112801 的物流进度”，
     * LLM 提取出 `{"orderId": "2024112801"}`。
     *
     * 这里又调了一次 LLM——和意图分类的那次不同，这次是专门做参数提取的。
     * 意图节点上的 `paramPromptTemplate` 是可选的自定义提取提示词，
     * 可以针对特定工具定制提取规则。比如订单查询工具的节点配了一段“从用户问题中提取订单号，
     * 订单号通常是数字开头的 10~20 位字符串”。
     * @param question
     * @param intentNode
     * @return
     */
    private CallToolResult executeSingleMcpTool(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId(); // 获得MCP toolID
        /**
         * McpToolRegistry` 是一个工具注册表，维护 `toolId → McpToolExecutor` 的映射。
         * 每个 `McpToolExecutor` 封装了一个 MCP 工具的完整调用逻辑——工具定义（参数 Schema、描述）和执行方法。
         */
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }
        // 这里是多态, 会执行DefaultMcpToolRegistry里的实现
        McpToolExecutor executor = executorOpt.get(); // 获得执行实例
        Tool tool = executor.getToolDefinition(); // 获得工具定义，这个在启动的时候就通过McpClientAutoConfiguration配置文件自动扫描是否有MCP工具，自动先注册进去
        // 调用LLM
        String customParamPrompt = intentNode.getParamPromptTemplate();
        // 通过LLM通过工具schame和用户提问来提取tool id得到params，里面也是内置系统prompt
        // 通过extractParameters提取各种参数，包括tool的定义、参数，customParamPrompt是前端人为定义的参数
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);
        // 这个返回params就是大模型通过用户问题、tool参数、customParamPrompt当前mcp叶子节点的人为填写规则来提取出最后
        // 调用MCPClient调用MCP工具，返回CallToolResult，传入params，得到MCP执行结果
        return executor.execute(params != null ? params : new HashMap<>());
    }

    private record ToolOutput(String toolId, CallToolResult result) {
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
