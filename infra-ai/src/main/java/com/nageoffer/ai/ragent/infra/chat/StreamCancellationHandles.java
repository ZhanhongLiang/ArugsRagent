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

import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamCancellationHandle 工具类。
 *
 * <p>流式取消要同时覆盖两条路径：</p>
 * <ul>
 *     <li>协作式取消：设置 AtomicBoolean，让 doStream 下一轮循环主动退出。</li>
 *     <li>阻塞 I/O 取消：调用 OkHttp Call.cancel()，打断正在 readUtf8Line() 上等待的线程。</li>
 * </ul>
 *
 * <p>内部句柄用 CAS 保证 cancel 幂等，用户多次点击“停止生成”也只会执行一次真实取消。</p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamCancellationHandles {

    // 线程池拒绝等“任务未真正启动”的场景使用空句柄，调用 cancel 不产生任何副作用。
    private static final StreamCancellationHandle NOOP = () -> {
    };

    public static StreamCancellationHandle noop() {
        return NOOP;
    }

    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        return new OkHttpCancellationHandle(call, cancelled);
    }

    private static final class OkHttpCancellationHandle implements StreamCancellationHandle {

        private final Call call;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean once = new AtomicBoolean(false);

        private OkHttpCancellationHandle(Call call, AtomicBoolean cancelled) {
            this.call = call;
            this.cancelled = cancelled;
        }

        @Override
        public void cancel() {
            // CAS 保证取消逻辑只执行一次，满足句柄幂等语义。
            if (!once.compareAndSet(false, true)) {
                return;
            }
            if (cancelled != null) {
                // 通知读取循环走协作式退出路径。
                cancelled.set(true);
            }
            if (call != null) {
                // 打断 OkHttp 底层 socket，唤醒阻塞中的 readUtf8Line/call.execute。
                call.cancel();
            }
        }
    }
}
