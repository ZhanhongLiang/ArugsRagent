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

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG Trace 上下文
 * 使用 TTL 在异步线程池中透传 traceId 与节点栈
 */
public final class RagTraceContext {

    /** 一次问答链路的全局追踪 ID，会随 TTL 透传到异步线程。 */
    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    /** 一次流式问答任务的任务 ID，用于关联 SSE、取消和 Trace。 */
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    // 父线程 -> 子线程的 NODE_STACK 必须深拷贝
    // 默认 copy() 返回父值引用，并发子任务会共用同一个 Deque，
    // 互相 push/pop 时父子节点 ID 串挂，trace 层级紊乱
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
        @Override
        public Deque<String> copy(Deque<String> parentValue) {
            return parentValue == null ? null : new ArrayDeque<>(parentValue);
        }
    };

    private RagTraceContext() {
        // 工具类不允许创建实例，所有状态都由静态 ThreadLocal 保存。
    }

    public static String getTraceId() {
        // 读取当前线程或其父任务透传而来的 Trace ID。
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        // 在入口处建立 Trace ID，后续节点只负责读取和关联。
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        // 读取当前流式任务 ID，用于将节点日志关联到同一次对话。
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        // 在创建流式任务时写入，异步检索和模型回调可继续使用。
        TASK_ID.set(taskId);
    }

    public static int depth() {
        // 节点栈为空代表当前不在任何可追踪子节点中。
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    public static String currentNodeId() {
        // Deque 栈顶就是当前正在执行的最内层 Trace 节点。
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    public static void pushNode(String nodeId) {
        // 首次进入节点时延迟创建栈，避免没有 Trace 时分配对象。
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        // push 后该节点成为栈顶，深度随之增加。
        stack.push(nodeId);
    }

    public static void popNode() {
        // 节点退出时仅操作当前线程自己的深拷贝栈。
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            // 防御性返回，避免异常清理路径重复 pop 导致新的异常。
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            // 栈清空后移除 ThreadLocal，避免线程池线程保留空容器。
            NODE_STACK.remove();
        }
    }

    public static void clear() {
        // 任务结束统一释放所有透传状态，防止线程复用造成链路串号。
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
