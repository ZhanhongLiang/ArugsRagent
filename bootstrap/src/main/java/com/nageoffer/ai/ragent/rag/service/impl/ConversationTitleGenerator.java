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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;

/**
 * 使用首个用户问题生成会话标题的独立组件。
 *
 * <p>拆成单独 Spring Bean 是为了让 {@link RagTraceNode} 经过 AOP 代理生效；
 * 若在 {@code ConversationServiceImpl} 内部自调用，Spring 无法拦截该调用，标题生成会脱离当前 Trace 链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTitleGenerator {

    private final MemoryProperties memoryProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final LLMService llmService;

    @RagTraceNode(name = "conversation-title-gen", type = "TITLE_GEN")
    public String generate(String question) {
        // 配置异常时保留合理兜底，确保新会话仍可创建。
        int maxLen = memoryProperties.getTitleMaxLength();
        if (maxLen <= 0) {
            maxLen = 30;
        }
        // 模板把长度约束和原问题一起交给模型，避免在服务端粗暴截断语义。
        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", String.valueOf(maxLen),
                        "question", question
                )
        );

        try {
            // 标题生成不需要深度推理；失败不能阻断聊天主流程，故返回固定兜底标题。
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            return llmService.chat(request);
        } catch (Exception ex) {
            log.warn("生成会话标题失败", ex);
            return "新对话";
        }
    }
}
