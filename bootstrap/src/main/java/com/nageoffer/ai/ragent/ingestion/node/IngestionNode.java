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

package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;

/**
 * 摄取流水线节点的统一端口。
 *
 * <p>节点通过共享 IngestionContext 读写中间数据，通过 NodeResult 告诉引擎成功、失败、跳过或是否继续，
 * 因此新增节点无需修改 IngestionEngine。</p>
 */
public interface IngestionNode {

    /** @return 与 PipelineDefinition 中 nodeType 对应的稳定类型标识。 */
    String getNodeType();

    /**
     * 执行节点业务逻辑并写回上下文。
     *
     * @param context 本次任务的共享中间数据和状态
     * @param config 当前节点的配置、条件和可选输出字段
     * @return 执行结果，供引擎决定记录日志、失败或继续执行
     */
    NodeResult execute(IngestionContext context, NodeConfig config);
}
