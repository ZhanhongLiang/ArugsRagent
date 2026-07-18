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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式模型调用的 Trace 节点支持。
 *
 * <p>AOP 只能观察提交异步任务的方法，无法覆盖后台线程持续读取 SSE 的真实耗时；
 * 本组件显式创建 Span，并在流完成、异常或取消时由回调结束节点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagStreamTraceSupportImpl implements RagStreamTraceSupport {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final RagTraceProperties traceProperties;
    private final RagTraceRecordService traceRecordService;

    @Override
    public StreamSpan beginStreamNode(String name, String type) {
        if (!traceProperties.isEnabled()) {
            return NOOP_SPAN;
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            return NOOP_SPAN;
        }

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        long startMillis = System.currentTimeMillis();

        // 在异步读取真正开始前插入 RUNNING，保证首包、取消等子事件都有稳定父节点。
        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(type, "STREAM"))
                .nodeName(name)
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .build());

        // 调用线程暂时入栈，使紧随其后的首包探测等同步子节点正确关联；detach 后不污染外层。
        RagTraceContext.pushNode(nodeId);

        return new StreamSpanImpl(traceId, nodeId, startMillis);
    }

    private final class StreamSpanImpl implements StreamSpan {
        private final String traceId;
        private final String nodeId;
        private final long startMillis;
        private final AtomicBoolean detached = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        StreamSpanImpl(String traceId, String nodeId, long startMillis) {
            this.traceId = traceId;
            this.nodeId = nodeId;
            this.startMillis = startMillis;
        }

        @Override
        public void detach() {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            // 仅当栈顶为本节点才 pop，防止嵌套或并发场景误弹其他节点。
            if (nodeId.equals(RagTraceContext.currentNodeId())) {
                RagTraceContext.popNode();
            }
        }

        @Override
        public void finishSuccess() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                // AtomicBoolean 保证 SSE 完成回调重复触发时只更新一次。
                traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishSuccess 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        @Override
        public void finishError(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                // 错误流同样需要收尾，否则管理端无法区分失败和仍在运行。
                traceRecordService.finishNode(traceId, nodeId, STATUS_ERROR,
                        truncateError(error), new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishError 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        @Override
        public void finishCancelledIfRunning() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                // 用户主动停止是正常终止状态，不应伪装为模型调用错误。
                traceRecordService.finishNode(traceId, nodeId, STATUS_CANCELLED, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishCancelled 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": "
                + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}
