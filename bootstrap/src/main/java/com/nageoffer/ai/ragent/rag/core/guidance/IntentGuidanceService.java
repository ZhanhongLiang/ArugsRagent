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

package com.nageoffer.ai.ragent.rag.core.guidance;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.constant.RAGConstant;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final PromptTemplateLoader promptTemplateLoader;
    private final AmbiguityLLMChecker ambiguityLLMChecker;

    /**
     * - 总开关**：`rag.guidance.enabled=false` 时整体关闭引导功能
     * - 2.
     *   **歧义组检测**：`findAmbiguityGroup()` 内部完成品类分组、快速跳过、三区间判定的全部逻辑，返回 null 说明不存在歧义
     * - 3.
     *   **构造引导 Prompt**：通过模板渲染出引导文案
     * @param question
     * @param subIntents
     * @return
     */
    @RagTraceNode(name = "guidance-detect", type = "GUIDANCE")
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        // 关闭歧义引导的开关
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }
        // 歧义引导， 获得需要澄清的group节点和topicname
        AmbiguityGroup group = findAmbiguityGroup(question, subIntents);
        //
        if (group == null || CollUtil.isEmpty(group.ranked())) {
            return GuidanceDecision.none();
        }

        String prompt = buildPrompt(group.topicName(), group.ranked());
        /**
         * 要么 `NONE`（不引导，继续下游流程），要么 `PROMPT`（带着引导文案短路）。工厂方法 `none()` 和 `prompt()` 保证对象只能通过这两种方式创建。
         * 下游 Pipeline 只需要调一个 `isPrompt()` 就知道该怎么处理，决策和执行完全解耦。
         */
        // 返回 prompt() 状态
        return GuidanceDecision.prompt(prompt);
    }

    /**
     * 五个步骤：
     *
     * - 1.
     *   **前置过滤**：只处理单子问题场景，KB 类候选至少 2 个
     * - 2.
     *   **按品类分组**：通过 `resolveSystemNodeId` 找到每个意图的品类归属，每个品类只保留最高分的 topic 作为代表
     * - 3.
     *   **快速跳过**：分数差距明显或用户已指定系统时，直接判定不歧义
     * - 4.
     *   **三区间判定**：根据分数比值所在区间，决定是明确歧义、调 LLM 确认还是明确无歧义
     * - 5.
     *   **转换为 `AmbiguityGroup`**：提取主题名 + 品类级选项 ID 列表
     *
     * `AmbiguityGroup` 是一个 `record`，
     * 结构很简单——`topicName` 是触发歧义的那个主题名称（退货政策），`
     * ranked` 是按分数降序排列的品类代表列表，包含完整的节点信息（名称、路径、分数），方便下游渲染选项时直接使用。
     * @param question
     * @param subIntents
     * @return
     */
    private AmbiguityGroup findAmbiguityGroup(String question, List<SubQuestionIntent> subIntents) {
        /**
         * 为什么？回忆一下第 4 篇查询改写和第 7 篇封顶算法。复合问题已经被拆成了多个子问题，每个子问题独立走意图分类。
         * 子问题 A 命中退货政策、子问题 B 命中物流费规则，这不是歧义，是用户确实问了两件事。
         * 跨子问题去做歧义判断没有意义——它们本来就该是不同意图。
         *
         * 过了这一关，说明是单子问题。但单子问题如果只命中了 1 个意图，也没有可比的对象，不可能构成歧义。所以紧接着还要检查候选数量：
         */
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }
        //filterCandidates()` 做了什么？只保留 KB 类意图，且分数 >= 0.35（`INTENT_MIN_SCORE`）：
        List<NodeScore> candidates = filterCandidates(subIntents.get(0).nodeScores());
        if (candidates.size() < 2) {
            return null;
        }
        /**
         * 新方案换了个思路：**先按品类分组，每个品类只保留分数最高的 topic 作为代表，然后看各品类代表之间的分数是否接近**。
         * 不再要求节点名称相同——反正最终引导用户选的是品类，不是具体 topic。
         * Collectors.toMap` 的第三个参数是合并函数——同一品类下有多个候选 topic 时
         * （比如 3C 数码下既有退货政策又有保修政策），保留分数高的那个。每个品类只出一个代表。
         */
        Map<String, NodeScore> systemBest = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(resolveSystemNodeId(ns.getNode())))
                .collect(Collectors.toMap(
                        ns -> resolveSystemNodeId(ns.getNode()),
                        ns -> ns,
                        (a, b) -> a.getScore() >= b.getScore() ? a : b
                ));
        // 分完组之后按分数降序排列：
        List<NodeScore> ranked = systemBest.values().stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();

        if (ranked.size() < 2) {
            return null;
        }
        // 品类分好组、排好序之后，先走两个快速通道判断是否可以直接跳过歧义引导。
        if (shouldSkipGuidance(question, ranked)) {
            return null;
        }
        // 快速跳过没拦住，说明分数比值在 `[threshold - margin, 1.0]` 区间内，且用户问题里没有显式品类名。接下来要做正式的歧义判定。
        if (!confirmAmbiguity(question, ranked)) {
            return null;
        }
        //
        List<NodeScore> trimmedRanked = trimRankedOptions(ranked);
        // 获得当前子话题的
        String topicName = trimmedRanked.get(0).getNode().getName();
        return new AmbiguityGroup(topicName, trimmedRanked);
    }

    private boolean shouldSkipGuidance(String question, List<NodeScore> ranked) {
        double top = ranked.get(0).getScore();
        if (top <= 0) {
            return true;
        }

        // 快速通道 1：分数比值低于边界下限，意图明确
        /**
         * `threshold` 默认 0.8，`margin` 默认 0.15。两者相减得到**边界下限** 0.65。当次高分 / 最高分 < 0.65 时，说明最高分品类有压倒性优势，直接走它就行，不需要引导。
         *
         * 为什么用比值而不是差值？高分段和低分段的含义不同：
         *
         * - 0.88 vs 0.78，差 0.1，但 0.88 那个明显更好，不该算歧义
         * - 0.45 vs 0.35，差 0.1，两个都不太行，也不该触发引导
         *
         * 比值法考虑了分数的量级。0.65 / 0.68 ≈ 0.96，远超 0.65 下限，说明两个分数确实非常接近，不能跳过。0.35 / 0.88 ≈ 0.40，远低于 0.65 下限，说明差距很大，直接跳过。
         */
        double ratio = ranked.get(1).getScore() / top;
        double threshold = Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.8D);
        double margin = Optional.ofNullable(guidanceProperties.getAmbiguityMargin()).orElse(0.15D);
        if (ratio < threshold - margin) {
            log.debug("分数比值(ratio={})低于边界下限({}), 跳过澄清", ratio, threshold - margin);
            return true;
        }

        // 快速通道 2：用户问题中显式提到了某个系统的 DOMAIN 级名称
        /**
         * 这条通道面向的是多系统架构——比如企业同时接入了 OA 系统、保险系统、电商系统，每个系统对应意图树的一个 DOMAIN 级节点。
         *
         * 考虑两种情况：
         *
         * - **场景 A**：用户问“数据安全怎么管理？”——没指定系统，OA 和保险都有数据安全相关节点，分数接近，确实需要引导
         * - **场景 B**：用户问“OA系统的数据安全管理”——已经说了 OA 系统
         *
         * 场景 B 即使分数接近，也不该弹引导——用户已经明示了系统归属。
         */
        if (StrUtil.isNotBlank(question)) {
            List<String> domainNames = ranked.stream()
                    .map(ns -> resolveDomainName(ns.getNode()))
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .toList();
            // 归一化
            String normalizedQuestion = normalizeName(question);
            for (String name : domainNames) {
                for (String alias : buildSystemAliases(name)) {
                    if (alias.length() >= 2 && normalizedQuestion.contains(alias)) {
                        log.debug("用户问题包含系统名[{}], 跳过澄清", name);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 以默认配置 `threshold = 0.8`、`margin = 0.15` 为例，分数比值被划分为三个区间：
     *
     * | 区间       | 比值范围            | 判定结果     | 说明                                               |
     * | ---------- | ------------------- | ------------ | -------------------------------------------------- |
     * | 明确歧义   | ratio >= 0.8        | 直接触发引导 | 两个品类分数非常接近，系统没有信心做选择           |
     * | 灰色地带   | 0.65 <= ratio < 0.8 | 调 LLM 确认  | 有一定差距但不够大，需要语义理解辅助判断           |
     * | 明确无歧义 | ratio < 0.65        | 不触发       | 最高分品类优势明显（已在 shouldSkipGuidance 拦截） |
     * @param question
     * @param ranked
     * @return
     */
    private boolean confirmAmbiguity(String question, List<NodeScore> ranked) {
        double top = ranked.get(0).getScore();
        double second = ranked.get(1).getScore();
        if (top <= 0) {
            return false;
        }

        double ratio = second / top;
        double threshold = guidanceProperties.getAmbiguityScoreRatio();
        double margin = guidanceProperties.getAmbiguityMargin();

        if (ratio >= threshold) {
            log.info("分数比值(ratio={})超过阈值({}), 判定为歧义", ratio, threshold);
            return true;
        }

        if (ratio >= threshold - margin) {
            log.info("分数比值(ratio={})在边界区间[{}, {}), 调 LLM 确认", ratio, threshold - margin, threshold);
            return ambiguityLLMChecker.checkAmbiguity(question, ranked); //调LLM来判断和
        }

        // ratio < threshold - margin 但 > skipThreshold，不触发澄清
        return false;
    }
    // filterCandidates()` 做了什么？只保留 KB 类意图，且分数 >= 0.35（`INTENT_MIN_SCORE`）：
    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        return NodeScoreFilters.kb(scores, RAGConstant.INTENT_MIN_SCORE);
    }

    /**
     * resolveDomainName` 从节点向上追溯到 DOMAIN 级祖先，获取系统名称：
     * 拿到 DOMAIN 名称后，做归一化字符串匹配。`buildSystemAliases`
     * 目前只返回归一化后的主名，返回 `List` 留了扩展点，未来可以加别名字典。
     * @param node
     * @return
     */
    private String resolveDomainName(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        while (current != null) {
            if (current.getLevel() == IntentLevel.DOMAIN) {
                return StrUtil.blankToDefault(current.getName(), "");
            }
            current = fetchParent(current);
        }
        return "";
    }

    private List<String> buildSystemAliases(String systemName) {
        if (StrUtil.isBlank(systemName)) {
            return List.of();
        }
        String normalized = normalizeName(systemName);
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(normalized)) {
            aliases.add(normalized);
        }
        return aliases;
    }

    /**
     * 品类分组的关键在于 `resolveSystemNodeId`——它从叶子节点出发，沿 `parentId` 向上回溯，找到 CATEGORY 级祖先作为品类归属。
     * @param node
     * @return
     */
    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        for (; ; ) {
            IntentLevel level = current.getLevel();
            if (level == IntentLevel.CATEGORY && (parent == null || parent.getLevel() == IntentLevel.DOMAIN)) {
                return current.getId();
            }
            if (parent == null) {
                return current.getId();
            }
            current = parent;
            parent = fetchParent(current);
        }
    }

    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    private List<NodeScore> trimRankedOptions(List<NodeScore> ranked) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(ranked.size());
        if (ranked.size() <= maxOptions) {
            return ranked;
        }
        return ranked.subList(0, maxOptions);
    }

    private String buildPrompt(String topicName, List<NodeScore> ranked) {
        String options = renderOptions(ranked);
        return promptTemplateLoader.render(
                RAGConstant.GUIDANCE_PROMPT_PATH,
                Map.of(
                        "topic_name", StrUtil.blankToDefault(topicName, ""),
                        "options", options
                )
        );
    }

    private String renderOptions(List<NodeScore> ranked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranked.size(); i++) {
            IntentNode node = ranked.get(i).getNode();
            String display = resolveOptionDisplay(node);
            sb.append(i + 1).append(") ").append(display).append("\n");
        }
        return sb.toString().trim();
    }

    private String resolveOptionDisplay(IntentNode node) {
        if (node == null) {
            return "";
        }
        if (StrUtil.isNotBlank(node.getFullPath())) {
            return node.getFullPath();
        }
        return StrUtil.blankToDefault(node.getName(), node.getId());
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private record AmbiguityGroup(String topicName, List<NodeScore> ranked) {
    }
}
