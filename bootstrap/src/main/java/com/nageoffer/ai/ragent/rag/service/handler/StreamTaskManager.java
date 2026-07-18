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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 流式任务管理器。
 *
 * <p>它是“用户点击停止生成”链路的核心组件：本地用 Guava Cache 保存 taskId 对应的
 * SSE sender、取消句柄和取消时落库回调；集群间用 Redis Bucket + Pub/Sub 传播取消信号。</p>
 *
 * <p>为什么同时需要本地 Cache 和 Redis？本地 Cache 支撑高频 isCancelled() 判断，避免每个 token
 * 都访问 Redis；Redis 负责跨节点同步，解决停止请求落在 A 节点而模型流跑在 B 节点的问题。</p>
 */
@Slf4j
@Component
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    // 取消状态保留 30 分钟：取消后不立刻清理，是为了让延迟到达的 token 回调还能读到 cancelled=true。
    // 本地Cache保留状态
    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
//     * 订阅 Redis 取消广播。
     *
     * <p>任意节点收到停止请求后都会 publish taskId；所有节点收到广播后都尝试 cancelLocal。
     * 真正持有该 taskId 的节点会中断本地 LLM 流，其它节点查不到任务则直接忽略。</p>
     */
    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, taskId) -> {
            if (StrUtil.isBlank(taskId)) {
                return;
            }
            cancelLocal(taskId);
        });
    }

    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册本地流式任务。
     *
     * <p>StreamChatEventHandler 构造时会先注册 sender 和取消落库回调；此时底层模型调用可能还没启动，
     * 所以取消句柄会稍后由 bindHandle 补上。这里还会检查 Redis 取消标记，覆盖“停止请求先到、任务后注册”的竞态。</p>
     */
    public void register(String taskId, SseEmitterSender sender, Supplier<CompletionPayload> onCancelSupplier) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.sender = sender;
        taskInfo.onCancelSupplier = onCancelSupplier;
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(sender, payload);
            sender.complete();
        }
    }

    /**
     * 绑定底层取消句柄。
     *对，正常情况下它主要就是做映射绑定：
     * taskInfo.handle = handle;
     * 也就是建立：
     * taskId -> StreamCancellationHandle
     * 正常流程里，bindHandle() 本身不会触发取消。
     * 只有一种特殊情况会立刻触发：
     * if (taskInfo.cancelled.get() && handle != null) {
     *     handle.cancel();
     * }
     * 这处理的是“先取消，后绑定”的竞态问题。
     * 举个时序：
     * 正常情况：
     * 1. 用户发起提问
     * 2. 后端创建 taskId
     * 3. llmService.streamChat() 返回 handle
     * 4. bindHandle(taskId, handle)
     * 5. taskInfo.cancelled = false
     * 6. 只保存 handle，不调用 cancel
     * 7. 模型继续流式输出
     * 也就是：
     * taskInfo.handle = handle;
     * // cancelled=false，不进 if
     * 异常竞态情况：
     * 1. 用户发起提问
     * 2. 后端创建 taskId
     * 3. 前端很快点了停止
     * 4. taskManager.cancel(taskId) 先执行
     * 5. taskInfo.cancelled = true
     * 6. 但此时 handle 还没返回，没法真正 cancel OkHttp
     * 7. 过一会儿 llmService.streamChat() 返回 handle
     * 8. bindHandle(taskId, handle)
     * 9. 发现 cancelled=true
     * 10. 立刻 handle.cancel()
     * 这个判断就是为了防止：
     * 用户已经点停止了，但因为 handle 来得晚，模型流还继续跑
     * 所以你可以这样理解：
     * bindHandle 正常作用：
     * 保存 taskId 和 handle 的映射。
     *
     * bindHandle 特殊作用：
     * 如果发现这个任务在绑定前已经被取消，就立刻补打一发 handle.cancel()。
     * 一句话：
     * 是的，bindHandle() 正常只是绑定映射；只有当 taskInfo.cancelled=true，说明用户已经提前取消过，它才会立即触发 handle.cancel()，用来兜住“先取消后拿到句柄”的竞态。
     * <p>句柄只有 llmService.streamChat() 返回后才拿得到。若用户在返回前已经点了停止，
     * 本地 cancelled 会先被置 true；绑定时发现已取消就立即调用 handle.cancel()，覆盖“先取消后绑定”的竞态。</p>
     */
    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.handle = handle;
        if (taskInfo.cancelled.get() && handle != null) {
            handle.cancel();
        }
    }

    /**
     * 判断某个任务是否已取消。
     *
     * <p>StreamChatEventHandler 在 onContent/onThinking/onComplete/onError 前都会调用它，
     * 取消后到达的后续 token 会被静默丢弃，避免用户点停止后页面还继续冒字。</p>
     */
    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 发起集群级取消。
     *
     * <p>顺序必须是“先写 Redis 标记，再发 Pub/Sub”：Bucket 是持久化取消状态，
     * Pub/Sub 是实时通知。即使广播丢了，后续 register/bind 也能通过 Bucket 发现该任务已取消。</p>
     */
    public void cancel(String taskId) {
        // 先设置 Redis 标记，再发布消息
        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(Boolean.TRUE, CANCEL_TTL); // 先写标记，再发布消息

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消
     * 如果是，会同步状态到本地缓存
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        Boolean cancelled = bucket.get();
        if (Boolean.TRUE.equals(cancelled)) {
            taskInfo.cancelled.set(true);
            return true;
        }
        return false;
    }

    /**
     * 本地执行取消逻辑。
     *
     * <p>只有真正持有 taskId 的节点会找到 StreamTaskInfo。CAS 保证多次停止、广播重复到达、
     * emitter 超时等并发路径只会执行一次真实取消。</p>
     * 这个是本地取消, 他就是在执行的机器上真正做取消流程的
     */
    private void cancelLocal(String taskId) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        if (taskInfo.handle != null) {
            taskInfo.handle.cancel();
        }

        // 在取消时执行回调，保存已累积的内容
        if (taskInfo.sender != null) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(taskInfo.sender, payload);
            taskInfo.sender.complete();
        }
    }

    /**
     * 正常完成后主动清理任务状态。
     *
     * <p>正常完成可以立即清理本地 Cache 和 Redis 取消标记；取消路径不会主动 unregister，
     * 会保留一段 TTL，让迟到回调继续感知 cancelled=true。</p>
     */
    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
    }

    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    /**
     * 发送 cancel + done 两个事件。
     *
     * <p>cancel 告诉前端“已停止并保存了部分回复”，done 是最终关闭哨兵。
     * 前端收到 done 后再关闭 EventSource，体验上不会只剩一个断开的空白连接。</p>
     */
    private void sendCancelAndDone(SseEmitterSender sender, CompletionPayload payload) {
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
    }

    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    /**
     * 单个流式任务的本地状态。
     *
     * <p>sender 与 onCancelSupplier 先注册，handle 后绑定，因此字段都用 volatile 保证不同线程可见。
     * cancelled 用 AtomicBoolean 做状态门闩，所有取消路径都围绕它做幂等控制。</p>
     */
    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile StreamCancellationHandle handle;
        private volatile SseEmitterSender sender;
        private volatile Supplier<CompletionPayload> onCancelSupplier;
    }
}
