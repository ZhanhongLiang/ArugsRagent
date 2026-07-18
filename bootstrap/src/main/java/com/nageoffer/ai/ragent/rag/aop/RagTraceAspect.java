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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 拦截 {@link RagTraceNode} 的同步方法，并将其记录为当前 Trace 的树节点。
 *
 * <p>节点 ID 入栈后，嵌套方法可读取它作为 parentNodeId；无论方法成功或抛异常，
 * 最终都会更新节点状态与耗时，避免管理端长期看到 RUNNING 节点。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final RagTraceRecordService traceRecordService;
    private final RagTraceProperties traceProperties;

    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        if (!traceProperties.isEnabled()) {
            // 关闭采集时完全透传，Trace 不应影响业务可用性。
            return joinPoint.proceed();
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            // 当前请求不在一条 Trace 中，不能创建无归属节点。
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        // 先落 RUNNING 节点，再执行真实业务；这样异常发生时仍有可更新的记录。
        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
                .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
                .className(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        // 入栈建立父子层级，finally 中必须配对弹栈。
        RagTraceContext.pushNode(nodeId);
        try {
            Object result = joinPoint.proceed();
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            traceRecordService.finishNode(
                    traceId,
                    nodeId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            throw ex;
        } finally {
            // 即使业务异常也恢复调用线程上下文，防止后续节点错误挂到本节点下。
            RagTraceContext.popNode();
        }
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        // 仅保存异常类型和消息，受配置长度限制，避免大堆栈或模型输出膨胀 Trace 表。
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= traceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, traceProperties.getMaxErrorLength());
    }
}
