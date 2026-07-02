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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
/**
 * 多通道检索引擎。
 *
 * <p>它是知识问答篇中检索阶段的总入口：根据意图结果同时调度向量全局检索、
 * 意图定向检索和后置处理器链，最后输出可放入 Prompt 的 RetrievedChunk 列表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels; // spring boot会自动注入bean, 包括检索通道
    private final List<SearchResultPostProcessor> postProcessors; // spring boot自动注入bean
    private final Executor ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     *- **阶段 1**：`executeSearchChannels`——并行执行所有启用的 Channel，收集每个通道的原始结果
     * - **阶段 2**：`executePostProcessors`——串行执行后处理器链，做去重、精排、截断
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        // 构建检索上下文
        //构建检索上下文 SearchContext context = buildSearchContext(subIntents, topK); //【阶段1：多通道并行检索】
        SearchContext context = buildSearchContext(subIntents, topK);

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        // 【阶段2：后置处理器链】
        /**
         * 答案是不能。把场景拉回电商客服。用户问了一句“AirPods Pro 怎么退货？”，经过意图识别和多通道并行检索后，三个通道各自返回了结果：
         *
         * - **意图定向通道**（`INTENT_DIRECTED`）：命中 3C 数码退货政策 Collection，返回 10 条 Chunk，分数范围 0.82~0.45
         * - **关键词检索通道**（`KEYWORD_ES`）：用 BM25 在全局搜到包含 AirPods、退货关键词的 8 条 Chunk，分数范围 12.5~3.2
         * - **全局向量检索通道**（`VECTOR_GLOBAL`）：在所有 Collection 中做向量检索，返回 10 条 Chunk，分数范围 0.78~0.35
         *
         * 三个通道合计 28 条 Chunk。这 28 条有三个问题：
         *
         * - 1.
         *   **有重复**：同一篇退货政策文档在意图定向和全局向量两个通道都被捞到了，不去重就会在 Prompt 里出现两次，白白浪费 Token
         * - 2.
         *   **分数不可比**：向量通道返回的分数是余弦相似度（0~1），ES 通道返回的是 BM25 分数（理论上无上界），意图定向的 0.82 和 ES 的 12.5 放在一起排序毫无意义
         * - 3.
         *   **数量太多**：28 条 Chunk 的文本量可能上万 Token，全塞进 Prompt 会挤压生成空间，核心信息被大量低相关内容稀释
         *
         * 后处理流水线要解决的就是这三个问题：**去重 → 精排 → 截断**，最终只留下最相关的 topK 条喂给大模型
         */
        return executePostProcessors(channelResults, context);
    }

    /**
     * 执行所有启用的检索通道。
     *
     * <p>这个方法是多通道检索的调度核心：先让每个 SearchChannel 自己判断 isEnabled(context)，
     * 再按通道优先级排序，最后把启用的通道提交到 ragRetrievalExecutor 并行执行。</p>
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 逐个询问每个 SearchChannel 是否适合处理本次检索。
        // 判断逻辑不在引擎里写死，而是下沉到各通道自己的 isEnabled(context) 方法中。
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                // 数字越小优先级越高；排序主要影响日志、结果合并和后置处理时的通道顺序。
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        // enabledChannels 已经是“本轮被激活的通道集合”。
        // 这里不会再判断通道类型，而是统一调用 channel.search(context)，让每个通道执行自己的检索策略。
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return emptyResult(channel);
                            }
                        },
                        ragRetrievalExecutor // 内层专用线性池, 内部两个search进行
                ))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    /**
     * 执行后置处理器链
     * 答案是不能。把场景拉回电商客服。用户问了一句“AirPods Pro 怎么退货？”，经过意图识别和多通道并行检索后，三个通道各自返回了结果：
     *
     * - **意图定向通道**（`INTENT_DIRECTED`）：命中 3C 数码退货政策 Collection，返回 10 条 Chunk，分数范围 0.82~0.45
     * - **关键词检索通道**（`KEYWORD_ES`）：用 BM25 在全局搜到包含 AirPods、退货关键词的 8 条 Chunk，分数范围 12.5~3.2
     * - **全局向量检索通道**（`VECTOR_GLOBAL`）：在所有 Collection 中做向量检索，返回 10 条 Chunk，分数范围 0.78~0.35
     *
     * 三个通道合计 28 条 Chunk。这 28 条有三个问题：
     *
     * - 1.
     *   **有重复**：同一篇退货政策文档在意图定向和全局向量两个通道都被捞到了，不去重就会在 Prompt 里出现两次，白白浪费 Token
     * - 2.
     *   **分数不可比**：向量通道返回的分数是余弦相似度（0~1），ES 通道返回的是 BM25 分数（理论上无上界），意图定向的 0.82 和 ES 的 12.5 放在一起排序毫无意义
     * - 3.
     *   **数量太多**：28 条 Chunk 的文本量可能上万 Token，全塞进 Prompt 会挤压生成空间，核心信息被大量低相关内容稀释
     *
     * 后处理流水线要解决的就是这三个问题：**去重 → 精排 → 截断**，最终只留下最相关的 topK 条喂给大模型
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并排序
        /**
         * 去重处理器的 `getOrder()` 返回 1，是流水线中最先执行的。原因很直接：
         * 如果不先去重就精排，同一条 Chunk 在意图定向和全局向量两个通道各出现一次，
         * Rerank 模型要给它打两次分——浪费 API 调用和算力，而且重复 Chunk 还会占据 topK 的名额。
         */
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        // 初始 Chunk 列表（所有通道的结果合并）
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // 依次执行处理器
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                // 也就是依次调用自己的process
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    private SearchChannelResult emptyResult(SearchChannel channel) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .build();
    }

    /**
     * 构建检索上下文
     */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .topK(topK)
                .build();
    }
}
