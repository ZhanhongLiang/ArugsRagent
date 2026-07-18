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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个意图节点及其与当前问题的匹配分数。
 *
 * <p>分数只是分类模型的相对置信信号，并非最终答案质量；
 * 后续会结合阈值、歧义策略和节点类型决定是否检索、调用工具或要求澄清。</p>
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class NodeScore {

    /**
     * 意图节点
     */
    private IntentNode node;

    /**
     * 分类模型给出的匹配分数，通常按降序排序后使用。
     */
    private double score;
}
