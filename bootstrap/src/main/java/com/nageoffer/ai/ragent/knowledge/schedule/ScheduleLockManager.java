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

package com.nageoffer.ai.ragent.knowledge.schedule;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于调度表行的分布式租约锁管理器。
 *
 * <p>锁所有权保存在 {@code lockOwner + lockUntil}：抢占、续租、释放都在 SQL WHERE 中校验随机 lockToken。
 * 因此锁过期后，即使旧工作线程还在运行，也无法续租、释放或覆盖新持锁实例的状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleLockManager {

    /** 调度行级分布式锁的关键不变量：所有写锁操作必须以 token 证明当前仍拥有租约。 */

    /** 将条件更新落到调度表，实现数据库租约锁。 */
    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    /** 锁 TTL 与默认值配置。 */
    private final KnowledgeScheduleProperties scheduleProperties;

    /** 当前 JVM 的稳定前缀，结合 UUID 形成可追踪且全局唯一的 lockToken。 */
    private final String instancePrefix = resolveInstancePrefix();
    /** 单线程守护心跳池；业务线程不被续租网络/数据库波动阻塞。 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("kb_schedule_lock_heartbeat_")
                    .setDaemon(true)
                    .build()
    );

    /**
     * 尝试获取一条调度任务的租约锁。
     *
     * 这个组件解决的核心问题是：多个实例同时扫描到同一个任务时，怎么保证只有一个实例在执行。
     *
     * 它提供了四个核心方法：
     *
     * - `tryAcquire(scheduleId, now)`：尝试获取锁，返回 lease 或 null
     * - `renew(lease)`：续期锁，返回是否成功
     * - `release(lease)`：释放锁
     * - `startHeartbeat(lease)`：启动自动心跳续锁
     *
     * 其中最有特色的是自动心跳续锁机制。传统的做法是业务代码在每个耗时操作前手动调用 `renew(lease)`，但这样容易遗漏。自动心跳机制启动一个后台线程，周期性自动续锁，业务代码只需在关键阶段检测锁是否失效即可。
     *
     * 这个组件的设计思路是：把锁的复杂性封装起来，业务代码不需要关心锁的续期细节。
     */
    public ScheduleLockLease tryAcquire(String scheduleId, Date now) {
        // 只有 lockUntil 为空或已过期才能条件更新成功；随机 token 是本次租约唯一身份。
        ScheduleLockLease lease = new ScheduleLockLease(scheduleId, nextLockToken());
        int updated = scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                        .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
        );
        return updated > 0 ? lease : null;
    }

    /**
     * 按 token 续租当前锁。
     * 返回 false 表示锁已过期并被其它实例接管，当前工作线程必须停止后续状态写入。
     */
    public boolean renew(ScheduleLockLease lease) {
        // WHERE lockOwner=token 防止旧实例把新实例持有的锁续期。
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 主动释放当前租约；同样以 token 限定，防止过期工作者清空新持有者的锁。
     */
    public boolean release(ScheduleLockLease lease) {
        // 释放也必须校验 token，防止租约过期后的旧线程误删后来者的锁。
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, null)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, null)
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 为长时间刷新任务启动后台续租心跳。
     * 业务代码仍需在阶段边界调用 renew/检查 isLost，因为心跳只是降低锁自然过期概率，不可替代所有权校验。
     */
    public ScheduleLockHeartbeat startHeartbeat(ScheduleLockLease lease) {
        // 后台心跳负责周期续租，关键业务阶段仍会同步检查锁是否丢失。
        long now = System.currentTimeMillis();
        ScheduleLockHeartbeat heartbeat = new ScheduleLockHeartbeat(lease, now, effectiveLockMillis());
        long intervalMillis = computeHeartbeatIntervalMillis();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(
                () -> doHeartbeat(heartbeat),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        heartbeat.bind(future);
        return heartbeat;
    }

    /** @return 从当前时刻起加有效 TTL 得到的新锁截止时间。 */
    public Date computeLockUntil() {
        return new Date(System.currentTimeMillis() + effectiveLockMillis());
    }

    /**
     * 单次心跳续租：续租成功更新最后确认时间；连续失败超过租约 TTL 才标记 lost，
     * 允许短暂数据库抖动在安全窗口内自动恢复。
     */
    private void doHeartbeat(ScheduleLockHeartbeat heartbeat) {
        // 续租失败后不能再写主调度状态，因为其它实例可能已经接管该任务。
        if (heartbeat.isClosed() || heartbeat.isLost()) {
            return;
        }
        try {
            if (renew(heartbeat.lease())) {
                heartbeat.markRenewed();
                return;
            }
            heartbeat.markLost();
            log.warn("定时刷新锁已丢失: scheduleId={}, lockToken={}",
                    heartbeat.lease().scheduleId(), heartbeat.lease().lockToken());
        } catch (Exception e) {
            if (heartbeat.isExpiredWithoutConfirmation()) {
                heartbeat.markLost();
                log.warn("定时刷新锁续约失败且已超过安全窗口: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            } else {
                log.warn("定时刷新锁续约失败，将继续重试: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            }
        }
    }

    /** 将心跳间隔限制在 5 到 60 秒，通常为有效 TTL 的三分之一。 */
    private long computeHeartbeatIntervalMillis() {
        long effectiveLockSeconds = effectiveLockSeconds();
        long intervalSeconds = Math.max(5, Math.min(effectiveLockSeconds / 3, 60));
        return intervalSeconds * 1000;
    }

    /** 将有效锁秒数转换为毫秒。 */
    private long effectiveLockMillis() {
        return effectiveLockSeconds() * 1000;
    }

    /** 锁 TTL 最少 60 秒，避免错误配置使长任务几乎立即失锁。 */
    private long effectiveLockSeconds() {
        return Math.max(scheduleProperties.getLockSeconds(), 60L);
    }

    /** 生成当前实例前缀加 UUID 的不可猜测租约 token。 */
    private String nextLockToken() {
        return instancePrefix + ":" + UUID.randomUUID();
    }

    /** 尝试把主机名编入实例前缀，诊断日志中可快速定位持锁机器。 */
    private static String resolveInstancePrefix() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    /** 应用停止时关闭守护心跳线程，避免 JVM 退出阶段继续发起数据库操作。 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 单次租约的心跳控制句柄。
     * lost 和 closed 用原子标记保证心跳任务、业务线程及关闭钩子并发执行时状态一致。
     */
    public static final class ScheduleLockHeartbeat implements AutoCloseable {

        /** 对应的调度租约。 */
        private final ScheduleLockLease lease;
        /** 超过该时间未确认续租，即使还在重试也必须判为失锁。 */
        private final long lockTtlMillis;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastConfirmedAt = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        /** 初始化时把抢锁时间视为首次确认时间。 */
        private ScheduleLockHeartbeat(ScheduleLockLease lease, long startAt, long lockTtlMillis) {
            this.lease = lease;
            this.lockTtlMillis = lockTtlMillis;
            this.lastConfirmedAt.set(startAt);
        }

        /** 绑定定时任务句柄，lost/close 时可取消后续心跳。 */
        private void bind(ScheduledFuture<?> future) {
            this.future = future;
        }

        /** @return 本心跳守护的租约凭证。 */
        public ScheduleLockLease lease() {
            return lease;
        }

        /** @return 是否已确认失去锁所有权。 */
        public boolean isLost() {
            return lost.get();
        }

        /** @return 是否已被正常关闭，无需继续续租。 */
        private boolean isClosed() {
            return closed.get();
        }

        /** 续租成功后刷新最后一次数据库确认时间。 */
        private void markRenewed() {
            lastConfirmedAt.set(System.currentTimeMillis());
        }

        /** 判断距离上次成功确认是否已超过整个锁 TTL。 */
        private boolean isExpiredWithoutConfirmation() {
            return System.currentTimeMillis() - lastConfirmedAt.get() >= lockTtlMillis;
        }

        /**
         * 首次失锁时原子地标记并取消心跳；重复调用不产生副作用。
         */
        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }

        /** 正常结束刷新任务时取消心跳，避免继续无意义续租。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }
    }
}
