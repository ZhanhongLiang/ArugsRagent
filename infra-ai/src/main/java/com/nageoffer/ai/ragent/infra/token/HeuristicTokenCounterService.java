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

package com.nageoffer.ai.ragent.infra.token;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 启发式 Token 估算服务。
 *
 * <p>它不依赖具体模型 tokenizer，而是按字符类型估算：ASCII 约 4 字符 1 token，
 * CJK 约 1 字符 1 token，其它字符约 2 字符 1 token。这个精度足够用于上下文预算控制，
 * 同时避免引入 tiktoken/sentencepiece 的模型词表依赖。</p>
 */
@Service
public class HeuristicTokenCounterService implements TokenCounterService {

    @Override
    public Integer countTokens(String text) {
        // 空文本不占 token。
        if (!StringUtils.hasText(text)) {
            return 0;
        }

        // 三类字符分别计数，再套用不同经验系数。
        int asciiCount = 0;
        int cjkCount = 0;
        int otherCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 空白字符对预算影响较小，直接跳过。
            if (Character.isWhitespace(ch)) {
                continue;
            }
            // ASCII 包含英文、数字、半角标点，粗略按 4 字符 1 token。
            if (ch <= 0x7F) {
                asciiCount++;
            } else if (isCjk(ch)) {
                // 中日韩字符通常接近 1 字符 1 token，按保守估算处理。
                cjkCount++;
            } else {
                // 其它 Unicode 字符按 2 字符 1 token 估算。
                otherCount++;
            }
        }

        // 向上取整，避免低估上下文长度。
        int asciiTokens = (asciiCount + 3) / 4; // 英文等按 4 字符约 1 token
        int otherTokens = (otherCount + 1) / 2; // 其他字符按 2 字符约 1 token
        int total = asciiTokens + cjkCount + otherTokens;
        return Math.max(total, 1);
    }

    /**
     * 判断字符是否属于常见 CJK Unicode 块。
     */
    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
