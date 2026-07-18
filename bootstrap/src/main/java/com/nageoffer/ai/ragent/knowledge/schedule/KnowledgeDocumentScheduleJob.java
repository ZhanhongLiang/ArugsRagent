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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库 URL 文档的定时扫描与卡死恢复任务。
 *
 * <p>本组件只负责发现到期任务、抢调度租约并投递给工作线程，以及恢复异常卡在 RUNNING 的文档；
 * 真正的下载、变更检测、重建与状态写回交由 ScheduleRefreshProcessor，避免调度线程被长任务占住。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    /**
     * 两项职责：扫描到期 URL 文档并在抢锁成功后投递刷新任务；恢复进程崩溃或线程中断导致的 RUNNING 文档。
     * 具体刷新编排全部位于 ScheduleRefreshProcessor，保证扫描器保持轻量。
     */

    /** 查询到期、启用且未被锁定的调度记录。 */
    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    /** 专用分块线程池，隔离调度扫描线程和耗时的文件/模型操作。 */
    private final Executor knowledgeChunkExecutor;
    /** 扫描间隔、批次大小和超时恢复阈值配置。 */
    private final KnowledgeScheduleProperties scheduleProperties;
    /** 多实例竞争同一调度任务时使用的数据库租约锁。 */
    private final ScheduleLockManager lockManager;
    /** 实际执行远程刷新与重建的编排器。 */
    private final ScheduleRefreshProcessor scheduleRefreshProcessor;
    /** 扫描恢复超时 RUNNING 文档的状态 CAS 辅助组件。 */
    private final DocumentStatusHelper documentStatusHelper;

    /**
     * 定时恢复长时间卡在 RUNNING 状态的文档（进程崩溃、线程中断等异常场景）。
     * 超过配置阈值未完成的 RUNNING 文档重置为 FAILED，允许用户手动重试
     * 首次启动延迟 30 秒，之后每次执行结束延迟 60 秒再运行，避免应用刚启动时与初始化任务争抢资源。
     */
    // fixedDelay 以“上一次结束”为起点，避免恢复扫描自身并发重叠。
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStuckRunningDocuments() {
        // 崩溃恢复：进程在置 RUNNING 后死亡时，超时将文档重置为 FAILED，允许用户手动重试。
        long timeoutMinutes = scheduleProperties.getRunningTimeoutMinutes();
        int recovered = documentStatusHelper.recoverStuckRunning(timeoutMinutes);
        if (recovered > 0) {
            log.warn("恢复了 {} 个卡在 RUNNING 状态超过 {} 分钟的文档，已重置为 FAILED",
                    recovered, Math.max(timeoutMinutes, 10));
        }
    }

    /**
     * 扫描到期任务并异步提交刷新。
     *
     * 每隔一段时间（默认 10 秒）执行一次，查询满足条件的任务：
     *
     * - `enabled = 1`：任务处于启用状态
     * - `nextRunTime <= now`：已经到执行时间了
     * - `lockUntil < now`：当前没有其他实例持有锁
     *
     * 找到任务后，不是直接执行，而是先尝试获取锁。抢锁成功的实例才能执行，抢锁失败的实例直接跳过。
     *
     * 调度器不直接下载文件或调用模型：它只发现工作、获得租约、投递线程池。复杂刷新过程独立到编排器，
     * 使扫描循环在集群和高负载下仍能持续运行。
     */
    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
        // 只扫描启用、到期且锁为空/已过期的任务，减少无效抢锁 SQL。
        Date now = new Date();
        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                                .or()
                                .le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
                        .orderByAsc(KnowledgeDocumentScheduleDO::getNextRunTime)
                        .last("LIMIT " + Math.max(scheduleProperties.getBatchSize(), 1))
        );

        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        for (KnowledgeDocumentScheduleDO schedule : schedules) {
            if (schedule == null || schedule.getId() == null) {
                continue;
            }
            // 尝试获取数据库租约；集群中只有条件更新成功的实例可以继续。
            ScheduleLockLease lease = lockManager.tryAcquire(schedule.getId(), now);
            if (lease == null) {
                continue;
            }
            try {
                // 抢锁成功后投递专用线程池，让调度线程立刻继续扫描下一批。
                knowledgeChunkExecutor.execute(() -> scheduleRefreshProcessor.process(lease));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                lockManager.release(lease);
            }
        }
    }
}
