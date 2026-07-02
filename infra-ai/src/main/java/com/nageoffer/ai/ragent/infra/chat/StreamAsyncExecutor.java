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

import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器。
 *
 * <p>SSE 读取会阻塞在 {@code readUtf8Line()} 等待供应商推送下一行，如果放在 Tomcat 请求线程上，
 * 一个长回答就会长期占住一个 Web 线程。本工具把阻塞式读取提交到专用模型流式线程池，
 * 调用线程只负责拿到 {@link StreamCancellationHandle} 后返回。</p>
 *
 * <p>线程池拒绝时会立即取消 OkHttp Call、通过 callback.onError 通知调用方，并返回 noop 句柄，
 * 保证异常路径也有统一的取消语义。</p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamAsyncExecutor {

    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        // cancelled 是协作式取消信号，doStream 循环会定期检查它。
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            // 真正的阻塞式 SSE 读取放到专用线程池中执行，避免占用请求线程。
            CompletableFuture.runAsync(() -> streamTask.accept(cancelled), executor);
        } catch (RejectedExecutionException ex) {
            // 任务没有提交成功，先取消底层 HTTP 调用，避免悬挂连接。
            call.cancel();
            // 流式错误通过回调通知，而不是从 submit 向外抛出。
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        // 正常路径返回 OkHttp 取消句柄：cancelled 标志 + call.cancel() 双重取消。
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}
