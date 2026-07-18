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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
/**
 * 并行检索模板。
 *
 * <p>封装拆分检索任务、并发执行、合并结果和统计日志的公共流程，
 * 子类只需要决定如何生成具体 RetrieveRequest。</p>
 *
 * @param <T> 检索目标类型，如意图节点或集合 ID
 */
@Slf4j
public abstract class AbstractParallelRetriever<T> {

    private final Executor executor;

    protected AbstractParallelRetriever(Executor executor) {
        this.executor = executor;
    }

    /**
     * 并行检索模板方法
     *
     * @param question 查询问题
     * @param targets  检索目标列表
     * @param topK     每个目标的 TopK
     * @return 合并后的检索结果
     */
    public final List<RetrievedChunk> executeParallelRetrieval(String question,
                                                               List<T> targets,
                                                               int topK) {
        // 为每个独立目标创建异步任务；例如一个意图对应一个知识库集合。
        // 任务之间没有数据依赖，因此并行可缩短多库检索的总等待时间。
        record RetrievalFuture<T>(T target, CompletableFuture<List<RetrievedChunk>> future) {
        }

        List<RetrievalFuture<T>> futures = targets.stream()
                .map(target -> {
                    CompletableFuture<List<RetrievedChunk>> future = CompletableFuture.supplyAsync(
                            () -> createRetrievalTask(question, target, topK),
                            executor
                    );
                    return new RetrievalFuture<>(target, future);
                })
                .toList();

        // 逐个等待已提交任务。某一个集合失败只记录该集合，不影响其他集合的召回结果。
        List<RetrievedChunk> allChunks = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (RetrievalFuture<T> future : futures) {
            try {
                List<RetrievedChunk> chunks = future.future.join();
                allChunks.addAll(chunks);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.error("{} 获取检索结果失败 - 目标: {}", getStatisticsName(), getTargetIdentifier(future.target), e);
            }
        }

        // 汇总日志用于观察多目标检索的覆盖度和局部故障，不参与结果排序。
        log.info("{} 检索统计 - 总目标数: {}, 成功: {}, 失败: {}, 检索到 Chunk 总数: {}",
                getStatisticsName(), targets.size(), successCount, failureCount, allChunks.size());

        return allChunks;
    }

    /**
     * 创建单个检索任务（子类实现）
     * 注意：此方法内部应包含异常处理，失败时返回空列表
     *
     * @param question 查询问题
     * @param target   检索目标
     * @param topK     TopK
     * @return 检索结果列表
     */
    protected abstract List<RetrievedChunk> createRetrievalTask(String question, T target, int topK);

    /**
     * 获取目标标识（用于日志）
     *
     * @param target 检索目标
     * @return 目标标识字符串
     */
    protected abstract String getTargetIdentifier(T target);

    /**
     * 获取统计名称（用于日志）
     *
     * @return 统计名称，如 "意图检索"、"全局检索"
     */
    protected abstract String getStatisticsName();
}
