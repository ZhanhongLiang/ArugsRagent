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

import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import java.util.List;

/**
 * 将用户问题映射为意图树叶子节点的分类抽象。
 *
 * <p>实现可采用一次 LLM 打分或按领域并行打分；调用方只依赖按分数降序的统一结果，
 * 再用阈值和数量上限控制后续检索或工具调用范围。</p>
 */
public interface IntentClassifier {

    /**
     * 对所有叶子分类节点做意图识别
     *
     * @param question 用户问题
     * @return 按 score 从高到低排序的节点打分列表
     */
    List<NodeScore> classifyTargets(String question);

    /**
     * 使用请求级数据权限快照进行分类；旧实现无需立即修改。
     */
    default List<NodeScore> classifyTargets(String question, KnowledgeAccessScope accessScope) {
        return classifyTargets(question);
    }

    /**
     * 取前 topN 个且 score >= minScore 的分类
     *
     * @param question 用户问题
     * @param topN     最多返回 N 个结果
     * @param minScore 最低分数阈值
     * @return 过滤后的节点打分列表
     */
    default List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        // 先过滤低置信候选再截断，避免低分节点占用有限的意图名额。
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }
}
