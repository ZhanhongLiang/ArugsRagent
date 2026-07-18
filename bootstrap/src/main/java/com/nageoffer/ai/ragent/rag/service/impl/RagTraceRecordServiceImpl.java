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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Trace 运行记录和节点记录的最小持久化实现。
 * 所有更新均按 traceId（节点还包含 nodeId）定位，避免覆盖同一会话并发请求的记录。
 */
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;

    @Override
    public void startRun(RagTraceRunDO run) {
        // 入口处插入 RUNNING 运行记录，后续由 Trace Runner 统一收尾。
        runMapper.insert(run);
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        // 只更新结束态字段，保留开始时采集的请求上下文和关联信息。
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    @Override
    public void startNode(RagTraceNodeDO node) {
        // 节点先插入再执行，异常路径才能可靠地更新为 ERROR。
        nodeMapper.insert(node);
    }

    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        // traceId + nodeId 是树节点的业务唯一定位条件。
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}
