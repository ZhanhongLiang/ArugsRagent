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

import lombok.Getter;

/**
 * 歧义引导阶段的显式决策对象。
 *
 * <p>用 {@link Action} 而非空字符串表示是否短路，可避免调用方把“没有提示文本”和“无需引导”混为一谈。</p>
 */
@Getter
public class GuidanceDecision {

    public enum Action {
        /** 无歧义或未启用引导，流水线继续检索。 */
        NONE,
        /** 已生成澄清问题，流水线向前端推送后结束当前轮次。 */
        PROMPT
    }

    private final Action action;
    private final String prompt;

    private GuidanceDecision(Action action, String prompt) {
        this.action = action;
        this.prompt = prompt;
    }

    public static GuidanceDecision none() {
        // 无需携带提示文本，当前请求应继续走后续 RAG 阶段。
        return new GuidanceDecision(Action.NONE, null);
    }

    public static GuidanceDecision prompt(String prompt) {
        // 提示文本由上游服务构造，当前对象只表达控制流决策。
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    public boolean isPrompt() {
        // 为流水线提供语义化判断，隐藏具体枚举比较。
        return action == Action.PROMPT;
    }
}
