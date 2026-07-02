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

package com.nageoffer.ai.ragent.infra.model;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模型选择器。
 *
 * <p>它负责根据 YAML 中的模型配置，产出一份已经排序、已经做过基础健康过滤的候选模型列表。
 * 这份列表不会在这里直接调用大模型，而是交给 {@link ModelRoutingExecutor} 执行。</p>
 *
 * <p>在“三态熔断器与故障转移”的链路里，本类处在第一层过滤位置：</p>
 * <ul>
 *     <li>选择阶段：通过 {@link ModelHealthStore#isUnavailable(String)} 跳过处于 OPEN 冷却期、
 *     或 HALF_OPEN 且已有探测请求在飞的模型。</li>
 *     <li>调用阶段：{@link ModelRoutingExecutor} 仍会在真正调用前通过
 *     {@link ModelHealthStore#allowCall(String)} 做第二层检查，防止选择之后状态又发生变化。</li>
 * </ul>
 *
 * <p>因此，ModelSelector 的职责不是“保证某个模型一定能调用成功”，而是尽量把不可用模型提前从候选列表里剔除，
 * 同时保留按默认模型、优先级、模型 id 排序后的故障转移顺序。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    /**
     * 模型配置来源，对应 application 配置里的 ai model providers、chat、embedding、rerank 等分组。
     */
    private final AIModelProperties properties;

    /**
     * 三态熔断器状态存储。
     *
     * <p>本类只在构建候选列表时调用只读风格的 {@code isUnavailable}，
     * 真正的 OPEN/HALF_OPEN/CLOSED 状态转换由执行器调用 {@code allowCall}、
     * {@code markSuccess}、{@code markFailure} 完成。</p>
     */
    private final ModelHealthStore healthStore;

    /**
     * 选择 Chat 候选模型。
     *
     * <p>Chat 有两种入口：普通对话和深度思考。深度思考模式会优先选择
     * {@code deepThinkingModel}，并且只保留声明支持 thinking 的候选模型。</p>
     *
     * @param deepThinking 是否开启深度思考
     * @return 按故障转移顺序排列的 Chat 模型候选列表
     */
    public List<ModelTarget> selectChatCandidates(boolean deepThinking) {
        // Chat 分组是普通对话能力的配置根节点；如果没有配置，直接返回空列表，让执行器抛出“无候选模型”异常。
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        // 先解析当前模式下的第一优先模型：深度思考优先 deepThinkingModel，否则使用 defaultModel。
        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);

        // 再按“首选模型 -> priority -> id”的规则排序，并过滤掉不可用模型。
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }

    /**
     * 选择 Embedding 候选模型。
     *
     * <p>Embedding 不区分深度思考模式，直接使用 embedding 分组的默认模型和候选列表。</p>
     *
     * @return 按故障转移顺序排列的 Embedding 模型候选列表
     */
    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    /**
     * 选择 Rerank 候选模型。
     *
     * <p>Rerank 同样不区分深度思考模式，直接使用 rerank 分组配置。</p>
     *
     * @return 按故障转移顺序排列的 Rerank 模型候选列表
     */
    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    /**
     * 解析 Chat 场景的首选模型 id。
     *
     * <p>首选模型只影响排序，不代表只调用这个模型。它会排到候选列表最前面，
     * 后续仍由 {@link ModelRoutingExecutor} 在失败时切换到下一个候选。</p>
     */
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, boolean deepThinking) {
        // 深度思考模式优先使用 deepThinkingModel，适合选择推理能力更强、但可能更慢或更贵的模型。
        if (deepThinking) {
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }

        // 普通模式或未配置 deepThinkingModel 时，回退到分组默认模型。
        return group.getDefaultModel();
    }

    /**
     * 通用候选选择入口。
     *
     * <p>Embedding、Rerank 这类没有 deepThinking 分支的能力走这里。</p>
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        // 分组缺失时不做兜底猜测，避免误调其它能力的模型。
        if (group == null) {
            return List.of();
        }

        // 非 Chat 深度思考场景默认使用 defaultModel 作为第一优先模型。
        return selectCandidates(group, group.getDefaultModel(), false);
    }

    /**
     * 根据指定分组构建候选模型列表。
     *
     * <p>这个方法串起两步：</p>
     * <ol>
     *     <li>先过滤配置层面的不可用候选，并按故障转移优先级排序。</li>
     *     <li>再转换成 {@link ModelTarget}，同时结合熔断器状态过滤掉当前不可用的模型。</li>
     * </ol>
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, boolean deepThinking) {
        // candidates 是真正可参与故障转移的模型列表；没有配置时返回空列表。
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        // 先处理静态规则：enabled、supportsThinking、首选模型、priority、id。
        List<AIModelProperties.ModelCandidate> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        // 再处理动态规则：provider 配置是否存在、模型是否处于熔断不可用状态。
        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 过滤并排序候选模型列表。
     *
     * <p>这一层只处理“配置决定的顺序”，还不检查三态熔断器状态。</p>
     *
     * <p>排序规则对应故障转移顺序：</p>
     * <ol>
     *     <li>当前场景的 firstChoiceModelId 排在最前。</li>
     *     <li>priority 越小越靠前，作为人工配置的优先级。</li>
     *     <li>id 字典序作为最后的稳定排序条件，避免同优先级时顺序抖动。</li>
     * </ol>
     */
    private List<AIModelProperties.ModelCandidate> filterAndSortCandidates(List<AIModelProperties.ModelCandidate> candidates,
                                                                           String firstChoiceModelId,
                                                                           boolean deepThinking) {
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                // 跳过空配置和显式 enabled=false 的模型；enabled 为空时视为启用，减少 YAML 配置噪音。
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                // 深度思考模式只保留 supportsThinking=true 的模型，避免把普通模型误用于推理增强场景。
                .filter(c -> !deepThinking || Boolean.TRUE.equals(c.getSupportsThinking()))
                .sorted(Comparator
                        // comparing 的结果为 false 的元素排在 true 前面，所以匹配 firstChoiceModelId 的候选会排到第一位。
                        .comparing((AIModelProperties.ModelCandidate c) ->
                                !Objects.equals(resolveId(c), firstChoiceModelId))
                        // priority 为空时放到最后；这让显式配置过优先级的模型优先参与故障转移。
                        .thenComparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        // id 作为稳定排序兜底，保证同等优先级下每次返回顺序一致。
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());

        // 深度思考模式没有候选时只记录警告，不在这里抛异常；统一由执行器处理“无候选”失败。
        if (deepThinking && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
        }

        return enabled;
    }

    /**
     * 将排序后的候选配置转换成可执行的模型目标。
     *
     * <p>这里进入“三态熔断器”的第一层检查：如果模型已经处于明显不可用状态，
     * 直接不放入本次候选列表，避免每个请求都先撞到已熔断模型再等待失败。</p>
     */
    private List<ModelTarget> buildAvailableTargets(List<AIModelProperties.ModelCandidate> candidates) {
        // provider 配置提供 baseUrl、apiKey 等客户端调用所需信息；candidate 只描述“选哪个模型”。
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        return candidates.stream()
                // 单个 candidate 可能因为 provider 缺失或熔断状态被转换为 null。
                .map(candidate -> buildModelTarget(candidate, providers))
                // 过滤掉不可执行的模型，让执行器拿到的列表尽量都是可尝试目标。
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 构建单个可执行模型目标。
     *
     * <p>{@link ModelTarget} 同时持有模型 id、候选配置和 provider 配置，
     * 后续执行器可以用它找到客户端并发起真实调用。</p>
     */
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        // 模型 id 是熔断器的 key。优先使用配置中的 id，没有 id 时用 provider::model 合成稳定标识。
        String modelId = resolveId(candidate);

        // 选择阶段的熔断预过滤：
        // OPEN 且冷却未到，或 HALF_OPEN 已有探测请求在飞时，当前请求直接跳过该模型。
        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        // 找到供应商配置。普通供应商必须有 ProviderConfig，否则没有 baseUrl/apiKey 等信息，无法构建客户端调用。
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());

        // NOOP 是特殊的空实现供应商，可以没有真实 provider 配置；其它供应商缺失配置时需要跳过。
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}", candidate.getProvider(), modelId);
            return null;
        }

        // 通过基础过滤和熔断预过滤后，包装成执行器可消费的目标对象。
        return new ModelTarget(modelId, candidate, provider);
    }

    /**
     * 解析候选模型的稳定 id。
     *
     * <p>这个 id 不只是日志展示名，也是 {@link ModelHealthStore} 记录熔断状态的 key。
     * 因此必须稳定、唯一。配置显式 id 优先；没有 id 时使用 provider::model 合成。</p>
     */
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        // 显式 id 最可靠，适合一个 provider 下同一个 model 配多套参数时区分不同候选。
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }

        // 没有显式 id 时，用 provider 和 model 生成兜底 id；缺失字段用 unknown，避免空指针。
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}