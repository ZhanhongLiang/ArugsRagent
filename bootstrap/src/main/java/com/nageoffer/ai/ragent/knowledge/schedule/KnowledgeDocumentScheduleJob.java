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
 * 知识库文档定时刷新任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final Executor knowledgeChunkExecutor;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final ScheduleLockManager lockManager;
    private final ScheduleRefreshProcessor scheduleRefreshProcessor;
    private final DocumentStatusHelper documentStatusHelper;

    /**
     * 恢复长时间卡在 RUNNING 状态的文档（进程崩溃等异常场景）
     * 超过配置阈值未完成的 RUNNING 文档重置为 FAILED，允许用户手动重试
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStuckRunningDocuments() {
        long timeoutMinutes = scheduleProperties.getRunningTimeoutMinutes();
        int recovered = documentStatusHelper.recoverStuckRunning(timeoutMinutes);
        if (recovered > 0) {
            log.warn("恢复了 {} 个卡在 RUNNING 状态超过 {} 分钟的文档，已重置为 FAILED",
                    recovered, Math.max(timeoutMinutes, 10));
        }
    }

    /**
     ***第一件事：扫描到期任务**
     *
     * 每隔一段时间（默认 10 秒）执行一次，查询满足条件的任务：
     *
     * - `enabled = 1`：任务处于启用状态
     * - `nextRunTime <= now`：已经到执行时间了
     * - `lockUntil < now`：当前没有其他实例持有锁
     *
     * 找到任务后，不是直接执行，而是先尝试获取锁。抢锁成功的实例才能执行，抢锁失败的实例直接跳过。
     *
     * **第二件事：恢复卡住的文档**
     *
     * 每分钟扫描一次，把卡在 `RUNNING` 状态超过阈值（默认 30 分钟）的文档重置为 `FAILED`。这是进程崩溃后的兜底恢复机制。
     *
     * 这个组件的设计思路是：负责找到该跑的任务和兜底恢复异常状态，不负责具体的刷新逻辑。
     */
    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
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
            // 尝试获取锁，返回 lease 或 null
            ScheduleLockLease lease = lockManager.tryAcquire(schedule.getId(), now);
            if (lease == null) {
                continue;
            }
            try {
                // 抢占锁后就丢到线程池异步执行
                knowledgeChunkExecutor.execute(() -> scheduleRefreshProcessor.process(lease));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                lockManager.release(lease);
            }
        }
    }
}
