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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.rag.config.validation.ValidMemoryConfig;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 多轮对话记忆的容量与压缩策略配置。
 *
 * 最近轮次保留原文以保证追问精度，较早内容可压缩成摘要以控制 Prompt Token 成本。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.memory")
@Validated
@ValidMemoryConfig
public class MemoryProperties {

    /**
     * 保留原文的最近轮数（user+assistant 视为一轮）
     */
    @Min(1)
    @Max(100)
    private Integer historyKeepTurns = 8;

    /**
     * 是否启用旧轮次摘要压缩；关闭时只由 historyKeepTurns 控制保留范围。
     */
    private Boolean summaryEnabled = false;

    /**
     * 用户轮数达到该阈值后才开始生成摘要，通常应大于原文保留轮数。
     */
    private Integer summaryStartTurns = 9;

    /**
     * 摘要最大字数
     */
    @Min(200)
    @Max(1000)
    private Integer summaryMaxChars = 200;

    /**
     * 会话标题最大长度（用于提示词约束）
     */
    @Min(10)
    @Max(100)
    private Integer titleMaxLength = 30;
}
