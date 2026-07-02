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

package com.nageoffer.ai.ragent.infra.util;

import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

/**
 * LLM 输出清理工具类。
 *
 * <p>有些模型被要求输出 JSON 时，会额外包一层 Markdown 代码围栏，例如 ```json ... ```。
 * 直接 JSON 解析会失败，所以在结构化解析前可以先调用本工具做轻量清洗。</p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class LLMResponseCleaner {

    // 匹配开头围栏：```、```json、```markdown 等。
    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");
    // 匹配结尾围栏，允许围栏前后有换行或空白。
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 移除 Markdown 代码块围栏。
     *
     * <p>无围栏文本也可以安全调用，正则匹配不到时会保持原文。</p>
     */
    public static String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        // 先 trim，避免围栏前后的空白影响正则锚点匹配。
        String cleaned = raw.trim();
        // 去掉开头 ```json 等语言标识围栏。
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 去掉结尾 ``` 围栏。
        cleaned = TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        return cleaned.trim();
    }
}
