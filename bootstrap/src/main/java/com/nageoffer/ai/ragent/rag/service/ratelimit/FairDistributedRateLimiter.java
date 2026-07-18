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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import cn.hutool.core.util.IdUtil;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 通用的分布式公平排队限流器。
 *
 * <p>它解决“10 个人同时提问，只有 3 个坑位”的问题：集群所有节点共享同一组 Redis permit，
 * 拿到 permit 的请求才能执行业务，拿不到的请求按 FIFO 排队等待，超过等待时间再拒绝。</p>
 *
 * <p>核心数据结构：</p>
 * <ul>
 *     <li>RPermitExpirableSemaphore：集群总并发坑位，permit 带 lease，节点崩溃后可自动回收。</li>
 *     <li>ZSET queue：等待者队列，score 使用全局自增 seq，保证 FIFO 且支持任意位置删除。</li>
 *     <li>AtomicLong seq：避免用时间戳做 score 导致同毫秒冲突。</li>
 *     <li>entry 标记：每个 requestId 一个 TTL key，防止节点崩溃留下僵尸队列项。</li>
 *     <li>Topic notify：许可释放或队列变化时广播唤醒各节点等待者。</li>
 * </ul>
 */
@Slf4j
public final class FairDistributedRateLimiter {

    private static final String LUA_PATH = "lua/queue_claim_atomic.lua";
    /**
     * entry TTL 在 maxWaitMillis 之上的额外缓冲，避免毫秒级时钟漂移导致存活条目被误判为僵尸
     */
    private static final long ENTRY_TTL_BUFFER_MILLIS = 5_000L;

    private final String name;
    private final RedissonClient redissonClient;
    private final IntSupplier maxPermitsSupplier;
    private final IntSupplier leaseSecondsSupplier;
    private final IntSupplier pollIntervalMsSupplier;

    // 这五个 key 是限流器在 Redis 中的完整状态：许可池、等待队列、FIFO 序列、通知频道、存活标记。
    private final String semaphoreKey;
    private final String queueKey;
    private final String queueSeqKey;
    private final String notifyTopicKey;
    private final String entryKeyPrefix;
    private final String claimLua;

    private final ScheduledExecutorService scheduler;
    private final PollNotifier pollNotifier;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile int notifyListenerId = -1;

    public FairDistributedRateLimiter(String name,
                                      RedissonClient redissonClient,
                                      IntSupplier maxPermitsSupplier,
                                      IntSupplier leaseSecondsSupplier,
                                      IntSupplier pollIntervalMsSupplier) {
        this.name = Objects.requireNonNull(name);
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.maxPermitsSupplier = Objects.requireNonNull(maxPermitsSupplier);
        this.leaseSecondsSupplier = Objects.requireNonNull(leaseSecondsSupplier);
        this.pollIntervalMsSupplier = Objects.requireNonNull(pollIntervalMsSupplier);

        this.semaphoreKey = name + ":semaphore";
        this.queueKey = name + ":queue";
        this.queueSeqKey = name + ":queue:seq";
        this.notifyTopicKey = name + ":queue:notify";
        this.entryKeyPrefix = name + ":entry:";
        this.claimLua = loadLuaScript();

        String threadPrefix = name.replace(':', '_');
        int schedulerSize = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger threadCounter = new AtomicInteger();
        // scheduler 同时服务两类触发：等待者的周期 poll，以及 Pub/Sub 通知到达后的本地批量唤醒。
        this.scheduler = new ScheduledThreadPoolExecutor(schedulerSize, r -> {
            Thread t = new Thread(r);
            t.setName(threadPrefix + "_scheduler_" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pollNotifier = new PollNotifier(this::availablePermits, scheduler);
    }

    /**
     * 启动限流器生命周期。
     *
     * <p>通过 Spring @Bean(initMethod="start") 调用。初始化 permit 数并订阅 Redis 通知；
     * started CAS 防止重复初始化，trySetPermits 自身也只在首次设置时生效。</p>
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 一次性初始化 semaphore permits 数；trySetPermits 自身幂等，仅首次生效
        // 后续 acquire/availablePermits 不再重复调用，避免每个 poller 多一次 Redis 往返
        redissonClient.getPermitExpirableSemaphore(semaphoreKey).trySetPermits(maxPermitsSupplier.getAsInt());
        RTopic topic = redissonClient.getTopic(notifyTopicKey);
        notifyListenerId = topic.addListener(String.class, (channel, msg) -> pollNotifier.fire());
    }

    /**
     * 停止限流器生命周期。
     *
     * <p>关闭顺序是先摘 Pub/Sub 监听，再停 scheduler，最后清空本地 poller，
     * 避免应用关闭过程中继续接收通知并向已关闭线程池提交任务。</p>
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (notifyListenerId != -1) {
            redissonClient.getTopic(notifyTopicKey).removeListener(notifyListenerId);
            notifyListenerId = -1;
        }
        scheduler.shutdown();
        awaitShutdown(scheduler);
        pollNotifier.clear();
    }

    /**
     * 非阻塞地排队抢占一个 permit。
     *
     * <p>同步入口：入队后立即尝试抢占，抢到就提交业务；抢不到则注册 poller 进入等待路径。
     * 本方法不会阻塞调用线程等待 permit，因此 SSE 入口线程可以快速返回。</p>
     */
    public void acquire(AcquireRequest req) {
        Ticket ticket = new Ticket(req); // 创建ticket
        // 把ticket注册出去到上一层调用的cancelBinder中，让外面也能触发这个cancel
        if (req.cancelBinder() != null) {
            req.cancelBinder().accept(ticket::cancel);
        }
        // entry 存活标记必须先于入队写入，否则 race 窗口内的并发 claim 会把刚入队的条目当僵尸 ZREM
        /**
         * setEntryMarker(ticket.requestId, req.maxWaitMillis()); 这行可以理解成：
         * 给当前排队请求在 Redis 里放一个“我还活着”的临时标记。
         * 它不是发放许可，也不是入队本身，而是配合 ZSET 队列防止“僵尸排队项”。
         * 核心流程是：
         * setEntryMarker(ticket.requestId, req.maxWaitMillis());
         * queue.add(nextQueueSeq(), ticket.requestId);
         * 意思是：
         * 先写 Redis Key：name:entry:{requestId}，值是 "1"，并设置 TTL。
         * 再把 requestId 放进 Redis ZSET 队列。
         * 后面 Lua 抢队头时，会检查这个 entry 标记是否存在。
         * 如果 ZSET 里有 requestId，但 entry 标记没了，
         *   就认为这个请求已经超时、取消、或者服务崩溃遗留了，把它从队列里 ZREM 掉。
         * 可以把 Redis 里的结构理解成两份：
         * ZSET queue:
         *   requestId -> 排队顺序
         *
         * entry marker:
         *   name:entry:requestId -> 是否还活着，带 TTL
         * 为什么需要它？
         * 因为只用 ZSET 会有问题。假设用户请求入队后：
         * 用户断开连接 / 请求超时 / JVM 崩溃
         * 如果没有清理，requestId 可能永远留在 ZSET 队头，后面的请求就会被它挡住，公平队列就卡死了。
         * 所以 entry marker 的作用就是：
         * ZSET 负责排队顺序
         * entry marker 负责判断这个排队请求是否还有效
         * 源码里的 Lua 就是这么判断的：
         * if redis.call('EXISTS', entryPrefix .. member) == 1 then
         *     -- 还活着，参与公平排队
         * else
         *     -- 标记没了，说明是僵尸项，从 ZSET 删除
         *     redis.call('ZREM', queueKey, member)
         * end
         * 为什么这行必须在 queue.add() 前面？
         * 因为如果先入队，再写 marker，中间有一个很短的并发窗口：
         * 线程 A：queue.add(requestId)
         * 线程 B：Lua 扫描队列，发现 requestId 没有 entry marker
         * 线程 B：把 requestId 当僵尸 ZREM 掉
         * 线程 A：setEntryMarker
         * 这样刚入队的正常请求就被误删了。
         * 所以正确顺序是：
         * 先写存活标记
         * 再进入队列
         * 一句话总结：
         * setEntryMarker(ticket.requestId, req.maxWaitMillis())
         * 是给排队请求写一个带过期时间的 Redis 存活标记，
         * 用来让 Lua 脚本区分“正常等待请求”和“超时/取消/宕机遗留的僵尸队列项”，保证公平队列不会被无效请求卡住。
         *
         *
         */
        // 先
        setEntryMarker(ticket.requestId, req.maxWaitMillis());
        // 这个就是ZSET有序集合
        RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
        // 入队, 生成nextQueueSeqkey
        queue.add(nextQueueSeq(), ticket.requestId);
        //
        if (tryAcquireIfReady(ticket)) {
            return;
        }
        // 抢占失败，也就是某个实例节点上述过程抢占失败的时候，进入定时
        scheduleQueuePoll(ticket);
    }

    // ==================== Ticket 状态机 ====================

    /**
     * 单 CAS 协调点。终态互斥：状态一旦从 PENDING 转走就不再变更，业务回调最多触发一次
     * 资源清理 ({@link Ticket#cleanup()}) 与状态机解耦，幂等执行
     */
    private enum State {PENDING, GRANTED, TIMED_OUT, CANCELLED}

    /**
     * Ticket 是单个排队请求的状态机。
     *
     * <p>它把 requestId、deadline、permitId、future 和回调都收在一起，避免异步路径到处传参数。
     * PENDING 只能通过 CAS 转为 GRANTED / TIMED_OUT / CANCELLED 之一，保证业务回调、超时回调、取消清理互斥。</p>
     */
    private final class Ticket {
        final String requestId = IdUtil.getSnowflakeNextIdStr();
        final long deadline;
        final AcquireRequest req;
        final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        final AtomicReference<String> permitRef = new AtomicReference<>();
        volatile ScheduledFuture<?> future;

        Ticket(AcquireRequest req) {
            this.req = req;
            // deadline 是绝对时间戳，poller 每次只需比较当前时间是否超过它。
            this.deadline = System.currentTimeMillis() + req.maxWaitMillis();
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isPending() {
            return state.get() == State.PENDING;
        }

        /**
         * 外部取消（emitter 完结/超时/出错）：幂等释放队列资源 + 注销，best-effort 抑制业务回调
         *
         * <p>GRANTED 状态下不释放 permit —— permit 已经被 {@link #grant} 包装的 try/finally 接管，
         * 业务执行完毕（含异常）会在 finally 释放。这里跨界释放会导致并发请求拿到尚在使用的 permit
         */
        void cancel() {
            // 取消不一定能立刻打断已执行业务；若已 GRANTED，permit 生命周期仍由业务 finally 释放。
            state.compareAndSet(State.PENDING, State.CANCELLED);
            cleanup();
        }

        /**
         * 排队超时：CAS 抢占终态后 cleanup + 在 caller executor 上跑 onTimeout
         */
        void timeout() {
            if (!state.compareAndSet(State.PENDING, State.TIMED_OUT)) {
                return;
            }
            cleanup();
            // onTimeout 在业务线程池执行，让拒绝落库和 SSE 推送不占用 scheduler 线程。
            submitSafely(req.onTimeout(), "onTimeout");
        }

        /**
         * 拿到 permit。CAS 抢占 GRANTED 终态后将 permit 生命周期交给业务（try/finally 释放）
         *
         * <p>permitRef 设值与 CAS 顺序：先 set，再 CAS。这样并发 cancel/timeout 路径在 CAS 失败
         * 时能看到 permit 并正确释放，避免 grant 与 cancel 时序竞争导致 permit 泄漏
         */
        boolean grant(String permitId) {
            permitRef.set(permitId);
            if (!state.compareAndSet(State.PENDING, State.GRANTED)) {
                // 已被 cancel/timeout 抢占。permitRef 可能已被对方 cleanup 清空，CAS 防双重释放
                if (permitRef.compareAndSet(permitId, null)) {
                    releasePermitQuietly(permitId);
                    publishQueueNotify();
                }
                return false;
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
            Runnable wrapped = () -> {
                try {
                    // 业务真正执行在 onAcquiredExecutor，permit 持有时间覆盖整个 RAG 流式主链路。
                    req.onAcquired().run();
                } finally {
                    // 正常完成、异常抛出都会归还 permit 并广播，唤醒后续排队者。
                    releaseHeldPermit();
                }
            };
            try {
                // 这才是执行过程,在线程中执行
                req.onAcquiredExecutor().execute(wrapped);
                return true;
            } catch (RejectedExecutionException ex) {
                log.warn("[{}] onAcquired 提交失败，降级为 timeout 拒绝路径", name, ex);
                releaseHeldPermit();   // 业务未运行，必须显式释放（cleanup 在 GRANTED 状态不会释放 permit）
                cleanup();
                submitSafely(req.onTimeout(), "onTimeout(fallback)");
                return false;
            }
        }

        /**
         * 释放当前 Ticket 持有的 permit（若有）。线程安全 + 幂等
         */
        void releaseHeldPermit() {
            String pid = permitRef.getAndSet(null);
            if (pid != null) {
                releasePermitQuietly(pid);
                publishQueueNotify();
            }
        }

        /**
         * 幂等清理：移队、删除 entry 标记、释放 permit（仅在非 GRANTED 状态下）、注销 poller、取消 future
         *
         * <p>GRANTED 状态下 permit 已由 grant 的包装 Runnable 接管，cleanup 不再释放，
         * 否则会在业务运行期间把 permit 还给 semaphore，等价于把同一 slot 让给另一个请求
         */
        void cleanup() {
            boolean removed = false;
            try {
                removed = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE).remove(requestId);
            } catch (Exception ex) {
                log.debug("[{}] 移除队列失败 (requestId={})", name, requestId, ex);
            }
            deleteEntryMarker(requestId);

            boolean releasedPermit = false;
            if (state.get() != State.GRANTED) {
                String permitId = permitRef.getAndSet(null);
                if (permitId != null) {
                    releasePermitQuietly(permitId);
                    releasedPermit = true;
                }
            }
            if (removed || releasedPermit) {
                publishQueueNotify();
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
        }

        void unregisterFromNotifier() {
            pollNotifier.unregister(requestId);
        }

        void cancelFutureQuietly() {
            ScheduledFuture<?> f = future;
            if (f != null && !f.isCancelled()) {
                f.cancel(false);
            }
        }

        private void submitSafely(Runnable r, String label) {
            try {
                req.onAcquiredExecutor().execute(r);
            } catch (Exception ex) {
                log.warn("[{}] {} 提交失败，回调被丢弃", name, label, ex);
            }
        }
    }

    // ==================== 抢占核心 ====================

    /**
     * 四阶段抢占：状态检查 -> 可用许可数检查 -> Lua 原子 claim 队头窗口 -> 真实 semaphore acquire。
     *
     * <p>claim 成功不等于拿到许可，因为多个节点可能基于稍早的 availablePermits 同时进入 Lua。
     * 最终是否能跑业务，以 RPermitExpirableSemaphore.tryAcquire 返回 permitId 为准。</p>
     *
     *
     * 非原子情况下，`ZRANK` 和 `ZREM` 两步之间有时间窗口。在这个窗口内，队列可能因为其他请求被取消而发生排名变化，
     * 导致后来的请求在排名前移后抢先 claim 成功，而先来的请求因为 GC 停顿或网络延迟错过了许可。最终 FIFO 顺序被破坏——后到的反而先跑了。
     *
     * Lua 脚本在 Redis 单线程执行环境下保证原子性：从遍历 `ZRANGE` 到 `ZREM` 是一个不可分的事务单元。同一时刻只有一个 Lua 在跑，
     * 其他节点的 Lua 必须排队等。这样就不可能出现「两个请求同时看到自己 liveRank=0」的情况——一个先跑完 `ZREM` 之后，
     * 另一个再跑遍历时看到的队列已经不包含前一个了，但许可数是动态的，最终能 acquire 成功的还是受信号量限制。
     *
     * 新版 Lua 脚本比旧版多了僵尸清理，原子性更加重要——如果 `EXISTS` 检查和 `ZREM` 清理不在同一个原子操作里，
     * 两个节点可能同时看到同一个僵尸条目，一个清理后另一个误以为没清理过，导致队列状态不一致。
     *
     * 回到在线教育公司：10 个请求几乎同时打到两台机器，每台机器跑各自的 `tryAcquireIfReady`。
     * Redis 单线程串行执行 Lua 脚本，第 1 个 Lua 用 `ZRANGE` 取出队头窗口，遍历时看到 R1 的 entry 标记存在（liveRank=0），
     * 满足 `liveRank < maxRank(3)`，`ZREM R1` + `DEL entry:R1` 后队列变成 `[R2, R3, ..., R10]` 共 9 个；
     * 第 2 个 Lua 是 R2，同样扫描队头发现自己 liveRank=0，`ZREM R2`；
     * 第 3 个 Lua 是 R3，liveRank=0，`ZREM R3`。**前 3 个都成功 claim**。
     *
     * 第 4 个 Lua 跑过来——扫描队头窗口看到 R4 的 liveRank=0（R1/R2/R3 都不在队列里了），
     * 看起来还能 claim。但传入的 `maxRank` 已经是阶段 2 查到的当时的可用许可数。
     * 如果阶段 2 查时许可还是 3 但阶段 3 跑 Lua 时其实只剩 0（被 R1/R2/R3 拿走了），
     * R4 的 Lua claim 会成功但阶段 4 `tryAcquire` 会失败——这就是 claim 成功但 acquire 失败的兜底场景。
     */
    private boolean tryAcquireIfReady(Ticket ticket) {
        // 阶段 1：本地状态门闩。已取消/超时/已授权的请求不再参与抢占。
        if (!ticket.isPending()) {
            return false;
        }
        // 这里是普通的信号量查询
        // 阶段 2：看当前 Redis semaphore 是否有空坑位；没有则直接进入等待路径。
        // 阶段2查询的是许可，其实也就是信号量，也就是这个可以保证同时超过许可的实例进入的时候
        // 能保证
        int avail = availablePermits();
        if (avail <= 0) {
            return false;
        }
        // 阶段 3：Lua 原子 claim。只有位于“存活队头窗口”内的请求才能从 ZSET 出队。
        long claimedScore = claimIfReady(ticket.requestId, avail);
        if (claimedScore < 0L) {
            return false;
        }
        // 阶段 4：拿真实 permit。waitTime=0，不阻塞当前线程；拿不到就按原 score 重入队
        // tryAcquirePermit里面保证了独占锁，也就是我可以保证前面lua原子claim成功，但是实际能进入线程只有许可那么多
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            // 队头但无 permit：按原 score 重入队，保留排队位次（公平性），这个确保try失败的重新进入ZSET队列,按照之前自己的分数
            // 与 cancel/timeout 的 race：claimIfReady 已 ZREM，cleanup 的 remove 在此刻是 no-op；
            // 必须 add 后回查 state，若已终态则自行回滚，避免僵尸条目永久占据队头窗口
            setEntryMarker(ticket.requestId, Math.max(1, ticket.deadline - System.currentTimeMillis()));
            RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
            queue.add(claimedScore, ticket.requestId); // 进队列
            publishQueueNotify(); // Redisson PUB广播
            // 状态机里面的状态只有三种，ticket三种状态都是原子操作，不存在互转，只有三种pending->cancle , peding -> timeout, pdeing -> grant
            if (!ticket.isPending()) {
                queue.remove(ticket.requestId);
                deleteEntryMarker(ticket.requestId);
            }
            return false;
        }
        if (!ticket.isPending()) {
            // claim 与 acquire 之间被取消/超时：必须释放 permit 并通知，否则其他等待者要等下一次 poll
            releasePermitQuietly(permitId);
            publishQueueNotify();
            return false;
        }
        publishQueueNotify(); // Redisson PUB广播
        return ticket.grant(permitId);
    }

    /**
     * 等待路径入口。
     *
     * <p>同步抢占失败后，Ticket 会同时拥有两条唤醒路径：scheduler 周期轮询兜底，
     * PollNotifier 在 Redis Pub/Sub 通知到达时即时触发。
     * 两条路径调用同一个 poller，靠 Ticket 状态机保证幂等。</p>
     */
    private void scheduleQueuePoll(Ticket ticket) {
        // 配置下限 50ms：太低会让等待者频繁打 Redis，正常释放场景主要依赖 Pub/Sub 即时唤醒。
        int interval = Math.max(50, pollIntervalMsSupplier.getAsInt());
        Runnable poller = () -> {
            if (!ticket.isPending()) {
                ticket.unregisterFromNotifier();
                ticket.cancelFutureQuietly();
                return;
            }
            if (System.currentTimeMillis() > ticket.deadline) {
                ticket.timeout();
                return;
            }
            // 再尝试抢占
            tryAcquireIfReady(ticket);
        };
        // 首次延迟一个 interval，因为 acquire() 刚刚已经同步尝试过一次，立刻再试只会浪费 Redis 往返。
        // 这个就是定时任务轮询！！为什么还需要定时任务轮询兜底, 只有PUB/SUB机制不够吗
        ticket.future = scheduler.scheduleAtFixedRate(poller, interval, interval, TimeUnit.MILLISECONDS);
        // 注册到 PollNotifier，后续任意节点释放许可时，Pub/Sub 会触发本地 poller 立即再试。
        pollNotifier.register(ticket.requestId, poller);
    }

    // ==================== Redis 操作 ====================

    /**
     * 尝试获取真实 Redis permit。
     *
     * <p>tryAcquire 的 lease 是终极兜底：如果 JVM 崩溃或网络中断导致业务无法主动 release，
     * Redis 会在租约到期后自动回收该 permit，避免集群并发坑位永久泄漏。</p>
     */
    private String tryAcquirePermit() {
        // 得到信号量，semaphoreKey，根据name+semaphore查找到key
        RPermitExpirableSemaphore sem = redissonClient.getPermitExpirableSemaphore(semaphoreKey);
        try {
            // 调用信号量的tryAcquire， 尝试抢断独占锁
            return sem.tryAcquire(0, leaseSecondsSupplier.getAsInt(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int availablePermits() {
        return redissonClient.getPermitExpirableSemaphore(semaphoreKey).availablePermits();
    }

    private void releasePermitQuietly(String permitId) {
        try {
            // 释放锁
            redissonClient.getPermitExpirableSemaphore(semaphoreKey).release(permitId);
        } catch (Exception ex) {
            log.debug("[{}] 释放 permit 失败（可能已过期）：{}", name, ex.getMessage());
        }
    }

    /**
     * 写入 entry 存活标记，TTL = 等待预算 + 缓冲。JVM 崩溃后 Key 自然过期，
     * 后续 {@link #claimIfReady} 在 Lua 内会把对应 ZSet 条目当僵尸清理掉，避免永久占据队头窗口
     */
    private void setEntryMarker(String requestId, long remainingMillis) {
        long ttlMillis = Math.max(remainingMillis, 1L) + ENTRY_TTL_BUFFER_MILLIS;
        try {
            // bucket redis ZSET有序集合
            RBucket<String> bucket = redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE);
            bucket.set("1", Duration.ofMillis(ttlMillis));
        } catch (Exception ex) {
            log.debug("[{}] 写入 entry 标记失败 (requestId={})", name, requestId, ex);
        }
    }

    private void deleteEntryMarker(String requestId) {
        try {
            redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE).delete();
        } catch (Exception ex) {
            log.debug("[{}] 删除 entry 标记失败 (requestId={})", name, requestId, ex);
        }
    }

    /**
     * Lua 原子 claim 队头窗口。
     *
     * <p>Lua 脚本完成两个动作：清理 entry 标记已过期的僵尸条目；判断当前 requestId 是否位于
     * 前 availablePermits 个存活等待者中。成功时把自己从队列移除并返回原始 score。</p>
     *
     * @return 成功返回 ticket 的原始 score（用于失败时按原位次重入队），未 claim 返回 -1
     */
    private long claimIfReady(String requestId, int availablePermits) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLua, // 这个LUA脚本已经写好的
                RScript.ReturnType.LIST,
                List.of(queueKey),
                requestId,
                String.valueOf(availablePermits),
                entryKeyPrefix
        );
        if (result == null || result.isEmpty() || parseLong(result.get(0)) != 1L) {
            return -1L;
        }
        return result.size() >= 2 ? parseLong(result.get(1)) : nextQueueSeq();
    }

    private long nextQueueSeq() {
        RAtomicLong seq = redissonClient.getAtomicLong(queueSeqKey);
        return seq.incrementAndGet();
    }

    /**
     * 发布队列/许可变化通知。
     *
     * <p>广播语义不是“分配给某个人”，而是“状态变了，等待者请重新评估”。
     * 所有节点都会收到通知，但最终谁能拿到 permit 仍由 ZSET FIFO + semaphore 双重约束决定。</p>
     */
    private void publishQueueNotify() {
        redissonClient.getTopic(notifyTopicKey).publish("permit_changed");
    }

    // ==================== 辅助 ====================

    private static long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("加载 Lua 脚本失败：" + LUA_PATH, ex);
        }
    }

    private static void awaitShutdown(ScheduledExecutorService exec) {
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ex) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 公开类型 ====================

    /**
     * 抢占请求参数。
     *
     * <p>这是业务方传入的回调集合：onAcquired 是拿到许可后真正执行的业务，onTimeout 是等待超时后的拒绝逻辑，
     * onAcquiredExecutor 决定这些回调在哪个线程池运行，cancelBinder 用来把外部连接生命周期绑定到 Ticket.cancel。</p>
     */
    @Builder
    public record AcquireRequest(long maxWaitMillis,
                                 Runnable onAcquired,
                                 Runnable onTimeout,
                                 Executor onAcquiredExecutor,
                                 Consumer<Runnable> cancelBinder) {
        public AcquireRequest {
            Objects.requireNonNull(onAcquired);
            Objects.requireNonNull(onTimeout);
            Objects.requireNonNull(onAcquiredExecutor);
            if (maxWaitMillis <= 0) {
                throw new IllegalArgumentException("maxWaitMillis must be > 0");
            }
        }
    }

    // ==================== PollNotifier ====================

    /**
     * 防惊群通知器：跨实例 RTopic 通知到达后，批量唤醒本进程所有 poller
     *
     * <p>通过 {@code firing} CAS + {@code pendingNotifications} 计数做合并：连续到达的多次通知
     * 只触发一次扫描，避免风暴。复用外部 scheduler 执行扫描，无需独立线程
     */
    private static final class PollNotifier {

        private final IntSupplier permitSupplier;
        private final Executor executor;
        private final ConcurrentHashMap<String, Runnable> pollers = new ConcurrentHashMap<>();
        private final AtomicBoolean firing = new AtomicBoolean(false);
        private final AtomicInteger pendingNotifications = new AtomicInteger(0);

        PollNotifier(IntSupplier permitSupplier, Executor executor) {
            this.permitSupplier = permitSupplier;
            this.executor = executor;
        }

        void register(String requestId, Runnable poller) {
            pollers.put(requestId, poller);
        }

        void unregister(String requestId) {
            pollers.remove(requestId);
        }

        void fire() {
            // 连续广播会被合并：如果已经有扫描在跑，只累加 pendingNotifications，不再提交新的全量扫描。
            // 第一道防线：CAS原子性
            pendingNotifications.incrementAndGet();
            if (!firing.compareAndSet(false, true)) {
                return;
            }
            executor.execute(() -> {
                do {
                    pendingNotifications.set(0);
                    try {
                        if (permitSupplier.getAsInt() <= 0) {
                            // permit 已耗尽，本轮不必扫描所有 poller。下一次真正的 release 会发新通知重新驱动
                            break;
                        }
                        for (Runnable poller : pollers.values()) {
                            try {
                                poller.run();
                            } catch (Exception ex) {
                                log.debug("poller 执行异常", ex);
                            }
                        }
                    } finally {
                        firing.set(false);
                    }
                } while (pendingNotifications.get() > 0 && firing.compareAndSet(false, true));
            });
        }

        void clear() {
            pollers.clear();
        }
    }
}
