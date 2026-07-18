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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH;

/**
 * 规则阈值无法定论时的 LLM 歧义确认器。
 *
 * <p>它不参与正常意图分类，只比较已排序的候选节点是否需要用户补充条件；
 * 因此只在阈值缓冲区调用，以避免每个问题都额外增加一次模型请求。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmbiguityLLMChecker {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 确认候选意图是否语义上难以区分。
     *
     * <p>低温度、低 Top-P 且关闭思考模式，目标是稳定输出 JSON 布尔值而非生成开放性回答。
     * 解析或调用失败时返回 {@code true}：多一轮澄清的代价小于错误路由后答非所问的代价。</p>
     */
    public boolean checkAmbiguity(String question, List<NodeScore> ranked) {
        // 只传候选节点的可解释信息，避免让确认模型重新遍历整棵意图树。
        String candidatesText = buildCandidatesText(ranked);
        String prompt = promptTemplateLoader.render(
                GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH,
                Map.of(
                        "question", question,
                        "candidates", candidatesText
                )
        );

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user(prompt)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        try {
            // 模型偶尔会把 JSON 包进 Markdown 代码块，先清理围栏再做严格 JSON 解析。
            String raw = llmService.chat(request);
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);

            if (!root.isJsonObject()) {
                log.warn("歧义确认 LLM 返回非 JSON 对象: {}", raw);
                return true; // 非预期结构无法可靠判定，保守地要求澄清。
            }

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("ambiguous")) {
                boolean ambiguous = obj.get("ambiguous").getAsBoolean();
                String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";
                log.info("LLM 歧义确认结果: ambiguous={}, reason={}, question={}", ambiguous, reason, question);
                return ambiguous;
            }

            log.warn("歧义确认 LLM 返回缺少 ambiguous 字段: {}", raw);
            return true; // 缺少关键字段同样按不确定处理。
        } catch (Exception e) {
            log.warn("歧义确认 LLM 调用失败, 降级为触发澄清, question={}", question, e);
            return true;
        }
    }

    private String buildCandidatesText(List<NodeScore> ranked) {
        // 路径能帮助模型区分同名但属于不同业务领域的叶子节点。
        return ranked.stream()
                .map(ns -> {
                    IntentNode node = ns.getNode();
                    String systemPath = node.getFullPath() != null ? node.getFullPath() : node.getName();
                    return String.format("- 品类ID: %s, 名称: %s, 路径: %s, 分数: %.2f",
                            node.getId(), node.getName(), systemPath, ns.getScore());
                })
                .collect(Collectors.joining("\n"));
    }
}
