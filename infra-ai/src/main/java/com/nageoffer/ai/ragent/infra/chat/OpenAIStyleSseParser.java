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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;

/**
 * OpenAI 兼容协议的 SSE 行解析器。
 *
 * <p>它不是通用 SSE 解析器，而是专门消费大模型接口最常见的 data-only SSE：</p>
 * <ul>
 *     <li>剥离 {@code data:} 前缀，拿到真正的 JSON payload。</li>
 *     <li>识别 OpenAI 约定的 {@code [DONE]} 终止标记。</li>
 *     <li>从 {@code choices[0].delta.content} 提取增量回答。</li>
 *     <li>在 reasoningEnabled=true 时，从 {@code reasoning_content} 提取思考过程。</li>
 *     <li>兼容部分供应商把内容放在 {@code message} 而不是 {@code delta} 的非标准行为。</li>
 * </ul>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        // SSE 事件之间常有空行；空行没有业务含义，返回 empty 让调用方跳过。
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        // OpenAI 兼容流通常形如 data: {json}，真正要解析的是 data: 后面的 JSON。
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            // [DONE] 是 OpenAI 协议的结束标记；有些供应商还会同时返回 finish_reason，两个都支持更稳。
            return ParsedEvent.done();
        }

        // 剩余 payload 按 OpenAI chat.completion.chunk JSON 结构解析。
        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        // 正式回答始终尝试提取；思考过程只在请求开启 thinking 时解析，避免无关字段污染回调。
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    private static String extractText(JsonObject choice, String fieldName) {
        // 双路径提取：标准流式路径是 delta，message 是兼容少数供应商的防御性路径。
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * 单行 SSE 的解析结果。
     *
     * <p>record 不可变，适合在线程间传递解析后的 content、reasoning 和 completed 标记。</p>
     */
    record ParsedEvent(String content, String reasoning, boolean completed) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
