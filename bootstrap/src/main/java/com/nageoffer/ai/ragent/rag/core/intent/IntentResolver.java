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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.rag.dto.IntentCandidate;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM;

/**
 * 意图解析编排器。
 *
 * <p>它接收改写服务产出的多个子问题，并发调用 IntentClassifier 打分，
 * 再做分数阈值过滤和全局数量裁剪。输出的 SubQuestionIntent 会决定后续走知识库检索、
 * MCP 工具调用，还是系统直答。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentResolver {

    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier intentClassifier;
    private final Executor intentClassifyExecutor;

    /**
     * 对每个子问题并发做意图分类。
     *
     * <p>子问题之间互不依赖，所以这里使用 intentClassifyExecutor 并行执行，
     * 降低多问题问答时的总耗时。</p>
     */
    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        /**
         * - 每个子问题独立调一次 LLM，通过 `CompletableFuture.supplyAsync()`
         * 提交到 `intentClassifyExecutor` 专用线程池并行执行。
         * 三个子问题不用串行等，并行跑完时间只取最慢的那一个。
         * - 单个子问题的分类失败降级为空意图列表（`List.of()`），不会阻断其他子问题——这是隔离性设计。
         * 一个子问题的 LLM 调用超时或者解析失败，其他子问题的结果不受影响。
         * - 所有子问题分类完成后，`capTotalIntents()` 做全局封顶。
         * 如果总意图数超过 `MAX_INTENT_COUNT`（3），每个子问题至少保留 1 个最高分意图，剩余配额按分数从高到低分配。
         * 封顶算法的具体策略在第 7 篇展开。
         */
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream() // 并发操作, 也就是每个子问题是独立进行打分的
                .map(q -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new SubQuestionIntent(q, classifyIntents(q)); // 尝试从意识图分解出来
                            } catch (Exception e) {
                                log.error("子问题意图分类失败，降级为空意图，question：{}", q, e);
                                return new SubQuestionIntent(q, List.of());
                            }
                        },
                        intentClassifyExecutor // 专门线程池
                ))
                .toList();
        /**
         * `.join()` 会阻塞当前线程直到 future 完成。三个 future 虽然是挨个调 `.join()` 的，
         * 但它们在 `intentClassifyExecutor` 池子里已经并行跑了，
         * 所以总耗时不是三次 `.join()` 的累加，而是 max（三个 future 各自的耗时）
         * 打个比方：你同时点了三家外卖，然后坐在那里等。第一家 10 分钟到，
         * 第二家 12 分钟到，第三家 8 分钟到——你等到手的时间是 12 分钟，
         * 不是 30 分钟。`.join()` 的语义就是这样。
         */
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();
        return capTotalIntents(subIntents); // 封顶算法
    }

    /**
     * 把多个子问题的意图合并成全局意图组，供检索和 Prompt 规划统一消费。
     */
    public IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        List<NodeScore> mcpIntents = new ArrayList<>();
        List<NodeScore> kbIntents = new ArrayList<>();
        for (SubQuestionIntent si : subIntents) {
            mcpIntents.addAll(NodeScoreFilters.mcp(si.nodeScores()));
            kbIntents.addAll(NodeScoreFilters.kb(si.nodeScores()));
        }
        return new IntentGroup(mcpIntents, kbIntents);
    }

    /**
     * 恰好只有一个意图，且 kind 是 SYSTEM，才算 SYSTEM-only。
     *
     * 为什么这么谨慎？看两个场景：
     *
     * | 场景                                 | 子问题意图                                                   | 是否短路 | 原因                      |
     * | ------------------------------------ | ------------------------------------------------------------ | -------- | ------------------------- |
     * | 用户说“你好”                         | [欢迎与问候（SYSTEM，0.88）]                                 | ✅ 短路   | 唯一意图是 SYSTEM         |
     * | 用户说“你好，顺便帮我查一下退货政策” | 子问题 1：[欢迎与问候（SYSTEM，0.85）]<br/>子问题 2：[退货政策（KB，0.78）] | ❌ 不短路 | 子问题 2 不是 SYSTEM-only |
     * | 用户说“谢谢”                         | [情感反馈（SYSTEM，0.82）]                                   | ✅ 短路   | 唯一意图是 SYSTEM         |
     * @param nodeScores
     * @return
     */
    public boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1
                && nodeScores.get(0).getNode() != null
                && nodeScores.get(0).getNode().getKind() == SYSTEM;
    }

    private List<NodeScore> classifyIntents(String question) {
        List<NodeScore> scores = intentClassifier.classifyTargets(question); //利用LLM进行意图识别
        /**
         * - 过滤掉分数低于 `INTENT_MIN_SCORE`（0.35）的节点。
         * - 限制每个子问题最多保留 `MAX_INTENT_COUNT`（3）个意图。
         */
        return scores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .limit(MAX_INTENT_COUNT)
                .toList();
    }

    /**
     * 限制总意图数量不超过 MAX_INTENT_COUNT
     * <p>
     * 策略：
     * 1. 如果总数未超限，直接返回
     * 2. 如果超限，每个子问题至少保留 1 个最高分意图
     * 3. 剩余配额按分数从高到低分配给其他意图
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        int totalIntents = subIntents.stream()
                .mapToInt(si -> si.nodeScores().size())
                .sum();

        // 未超限，直接返回
        if (totalIntents <= MAX_INTENT_COUNT) {
            return subIntents;
        }

        // 步骤1：收集所有意图，按子问题索引分组
        List<IntentCandidate> allCandidates = collectAllCandidates(subIntents);

        // 步骤2：每个子问题保留最高分意图
        List<IntentCandidate> guaranteedIntents = selectTopIntentPerSubQuestion(allCandidates, subIntents.size());

        // 步骤3：计算剩余配额
        int remaining = MAX_INTENT_COUNT - guaranteedIntents.size();

        // 步骤4：从剩余候选中按分数选择
        List<IntentCandidate> additionalIntents = selectAdditionalIntents(allCandidates, guaranteedIntents, remaining);

        // 步骤5：合并并重建结果
        return rebuildSubIntents(subIntents, guaranteedIntents, additionalIntents);
    }

    /**
     * 收集所有意图候选，标记所属子问题索引
     */
    private List<IntentCandidate> collectAllCandidates(List<SubQuestionIntent> subIntents) {
        List<IntentCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < subIntents.size(); i++) {
            List<NodeScore> nodeScores = subIntents.get(i).nodeScores();
            if (CollUtil.isEmpty(nodeScores)) {
                continue;
            }
            for (NodeScore ns : nodeScores) {
                candidates.add(new IntentCandidate(i, ns));
            }
        }
        // 按分数降序排序
        candidates.sort((a, b) -> Double.compare(b.nodeScore().getScore(), a.nodeScore().getScore()));
        return candidates;
    }

    /**
     * 每个子问题选择最高分意图（保底策略）
     */
    private List<IntentCandidate> selectTopIntentPerSubQuestion(List<IntentCandidate> allCandidates, int subQuestionCount) {
        List<IntentCandidate> topIntents = new ArrayList<>();
        boolean[] selected = new boolean[subQuestionCount];

        for (IntentCandidate candidate : allCandidates) {
            int index = candidate.subQuestionIndex();
            if (!selected[index]) {
                topIntents.add(candidate);
                selected[index] = true;
            }
            // 所有子问题都有了保底意图，提前退出
            if (topIntents.size() == subQuestionCount) {
                break;
            }
        }
        return topIntents;
    }

    /**
     * 从剩余候选中选择额外意图
     */
    private List<IntentCandidate> selectAdditionalIntents(List<IntentCandidate> allCandidates,
                                                          List<IntentCandidate> guaranteedIntents,
                                                          int remaining) {
        if (remaining <= 0) {
            return List.of();
        }

        List<IntentCandidate> additional = new ArrayList<>();
        for (IntentCandidate candidate : allCandidates) {
            // 跳过已经被选为保底的意图
            if (guaranteedIntents.contains(candidate)) {
                continue;
            }
            additional.add(candidate);
            if (additional.size() >= remaining) {
                break;
            }
        }
        return additional;
    }

    /**
     * 根据选中的意图重建 SubQuestionIntent 列表
     */
    /**
     * 这一步做的事情：把 `guaranteedIntents` 和 `additionalIntents` 合并，
     * 按 `subQuestionIndex` 分组，然后遍历原始子问题列表重建 `SubQuestionIntent`。
     *
     * 为什么要保留原始结构？因为下游（第 9 篇）要按子问题走定向检索，
     * 每个子问题的意图列表要明确关联到它的子问题文本。算法内部为了方便做全局排序，
     * 先把意图扁平化了；做完选择之后再按子问题分组塞回去，保证输出结构和下游约定一致。
     *
     * 注意 `getOrDefault(i, List.of())`
     * 这个兜底——如果某个子问题的意图全部被淘汰了（或者它本来就没匹配到任何意图），
     * 重建的时候会给它一个空列表，不会丢失这个子问题的位置。
     *
     * @param originalSubIntents
     * @param guaranteedIntents
     * @param additionalIntents
     * @return
     */
    private List<SubQuestionIntent> rebuildSubIntents(List<SubQuestionIntent> originalSubIntents,
                                                      List<IntentCandidate> guaranteedIntents,
                                                      List<IntentCandidate> additionalIntents) {
        // 合并所有选中的意图
        List<IntentCandidate> allSelected = new ArrayList<>(guaranteedIntents);
        allSelected.addAll(additionalIntents);

        // 按子问题索引分组
        Map<Integer, List<NodeScore>> groupedByIndex = new HashMap<>();
        for (IntentCandidate candidate : allSelected) {
            groupedByIndex.computeIfAbsent(candidate.subQuestionIndex(), k -> new ArrayList<>())
                    .add(candidate.nodeScore());
        }

        // 重建结果
        List<SubQuestionIntent> result = new ArrayList<>();
        for (int i = 0; i < originalSubIntents.size(); i++) {
            SubQuestionIntent original = originalSubIntents.get(i);
            List<NodeScore> retained = groupedByIndex.getOrDefault(i, List.of());
            result.add(new SubQuestionIntent(original.subQuestion(), retained));
        }
        return result;
    }
}
