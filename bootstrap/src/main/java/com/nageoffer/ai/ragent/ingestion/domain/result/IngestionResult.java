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

package com.nageoffer.ai.ragent.ingestion.domain.result;

import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 摄取任务对控制器和调用方的最终摘要。
 * 它不承载完整 Context 或大块内容，只返回任务身份、最终状态、分块数量和可展示消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionResult {

    /** 可用于查询后续任务日志、状态和文档记录的任务 id。 */
    private String taskId;

    /** 本次执行采用的流水线 id。 */
    private String pipelineId;

    /** 最终状态，例如成功、失败或处理中。 */
    private IngestionStatus status;

    /** 成功切出的向量块数，是摄取规模的核心摘要。 */
    private Integer chunkCount;

    /** 成功时是概要说明，失败时是面向调用方的错误原因。 */
    private String message;
}
