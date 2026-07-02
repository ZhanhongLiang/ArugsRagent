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

package com.nageoffer.ai.ragent.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器。
 *
 * <p>它实现第 57 篇的 probe-and-commit 模式：供应商回调先进入 bridge，
 * 在首包探测成功前不直接转发给真实 callback，而是缓冲成 Runnable。路由线程通过
 * {@link #awaitFirstPacket(long, TimeUnit)} 等待第一个 content/thinking/error/complete 信号。</p>
 *
 * <p>探测成功时 commit：把缓冲事件按原顺序刷给真实 callback，并切换为直通模式。
 * 探测失败时不 commit：缓冲事件被丢弃，路由层取消当前连接并尝试下一个模型，前端完全无感知。</p>
 */
public final class ProbeStreamBridge implements StreamCallback {

    // 真实业务回调。commit 前不直接调用它，避免失败供应商的残片推给前端。
    private final StreamCallback downstream;
    // 首包信号。异步读取线程 complete，路由线程 get/timeout 等待。
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    // 保护 buffer 与 committed 的同一把锁，避免事件丢失、并发修改和乱序。
    private final Object lock = new Object();
    // commit 前的回调动作缓冲区；lambda 捕获具体 downstream 调用，commit 时顺序执行。
    private final List<Runnable> buffer = new ArrayList<>();
    // committed=false 表示探测阶段；true 表示已确认供应商可用，后续事件直通。
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onContent(String content) {
        // 第一个正式内容代表供应商已经可用，唤醒路由线程。
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    @Override
    public void onThinking(String content) {
        // 第一个正式内容代表供应商已经可用，唤醒路由线程。
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    @Override
    public void onComplete() {
        // 未产生任何内容就完成，视为 NO_CONTENT，路由层会尝试下一个模型。
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    @Override
    public void onError(Throwable t) {
        // 首包前出错会唤醒路由层；错误事件本身先缓冲，失败切换时不会转发给真实 callback。
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲。
     *
     * <p>这是把异步回调世界拉回同步路由决策的关键：路由线程只等首包这一个信号，
     * 成功后立即返回 handle，后续 token 继续异步推送。</p>
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            // 超时说明供应商迟迟没有首包，视为当前候选失败。
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        if (result.isSuccess()) {
            // 成功后提交缓冲事件，并切换为直通模式。
            commit();
        }
        return result;
    }

    private void commit() {
        synchronized (lock) {
            // commit 只允许执行一次；后续事件会在 bufferOrDispatch 中走直通路径。
            if (committed) {
                return;
            }
            committed = true;
            // 在锁内执行缓冲事件，避免新事件抢先直通导致前端看到乱序内容。
            buffer.forEach(Runnable::run);
        }
    }

    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            // check committed 与加入 buffer 必须在同一临界区，避免 commit 插队造成事件丢失。
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        if (dispatchNow) {
            // 已 commit 后不再缓冲，直接透传给真实 callback。
            action.run();
        }
    }

    /**
     * 首包探测结果。
     *
     * <p>SUCCESS 表示可以 commit；ERROR/TIMEOUT/NO_CONTENT 都会让路由层取消当前连接并尝试下一个候选。</p>
     */
    @Getter
    public static class ProbeResult {

        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
