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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 定时刷新状态的唯一写入出口。
 *
 * <p>刷新编排器只负责执行，所有 schedule 主表与 exec 明细表状态转移集中在此处。
 * 主表更新必须验证 lockToken；若锁已丢失，仍允许记录本次执行明细，但绝不能覆盖接管实例写入的最新调度状态。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleStateManager {

    /** 成功、跳过、失败或失锁时统一使用的状态写入规则，便于故障恢复审计。 */

    /** 主表写入失败且确认失锁时附加到执行记录的说明。 */
    private static final String LEASE_LOST_NOTE = "（调度锁已失效，未写回调度状态）";

    /** 调度主表 Mapper，所有更新均需要 lockOwner 条件保护。 */
    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    /** 每次运行的执行明细 Mapper，保留即使失锁也有价值的诊断记录。 */
    private final KnowledgeDocumentScheduleExecMapper execMapper;


    /**
     * 将“远程内容未变化”写为 SKIPPED，并保存本次 ETag、Last-Modified、内容哈希以便下次比较。
     *
     * 这个组件解决的核心问题是：更新状态时必须感知锁所有权，防止覆盖其他实例的状态。
     *
     * 它提供的核心方法都采用 `xxxIfOwned` 模式：
     *
     * - `markSuccessIfOwned(lease, ctx, fetchResult, stored)`：成功时更新状态
     * - `markFailedIfOwned(lease, ctx, error)`：失败时更新状态
     * - `markSkippedIfOwned(lease, ctx, fetchResult)`：跳过时更新状态
     * - `disableIfOwned(lease, reason)`：禁用任务
     *
     * 这些方法更新 schedule 主表时都会检查 `lockOwner` 是否匹配当前 `lockToken`。如果不匹配（锁已失效），schedule 主表更新失败，但仍会更新 exec 记录，并在 message 中附加（调度锁已失效，未写回调度状态）标记。
     *
     * 这个组件的设计思路是：状态更新必须是锁感知的，不能盲目覆盖其他实例的状态。
     * @return true 表示调度主表仍由当前租约持有且写入成功；false 表示锁已丢失
     */
    public boolean markSkippedIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult) {
        // 主表更新带 token 条件；失败不代表 exec 明细不能留痕。
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, fetchResult.message())
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            // 执行记录不写 lockOwner 条件：它属于本次运行本身，失锁后仍应说明实际发生了什么。
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.SKIPPED.getCode());
            execUpdate.setMessage(withLeaseNote(fetchResult.message(), scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /** 将无须下载/重建的业务性跳过原因写入调度主表和执行明细。 */
    public boolean markSkippedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String message) {
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, message)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SKIPPED.getCode())
                    .message(withLeaseNote(message, scheduleUpdated))
                    .endTime(new Date())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 刷新、分块、索引与文件切换全部成功后写入 SUCCESS。
     * 同时推进下一次运行时间和变更检测快照，清空上次错误信息。
     */
    public boolean markSuccessIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult,
                                      StoredFileDTO stored) {
        // 统一使用同一个结束时间写主表成功时间和执行记录结束时间。
        Date endTime = new Date();
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastSuccessTime, endTime)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SUCCESS.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, null)
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SUCCESS.getCode())
                    .message(withLeaseNote("刷新成功", scheduleUpdated))
                    .endTime(endTime)
                    .fileName(stored.getOriginalFilename())
                    .fileSize(stored.getSize())
                    .contentHash(fetchResult.contentHash())
                    .etag(fetchResult.etag())
                    .lastModified(fetchResult.lastModified())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /** 将本次失败摘要写入主调度和执行明细；错误文案会截断避免数据库字段膨胀。 */
    public boolean markFailedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String errorMessage) {
        String truncatedErrorMessage = truncate(errorMessage);
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncatedErrorMessage)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
            execUpdate.setMessage(withLeaseNote(truncatedErrorMessage, scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 当 URL、Cron 或关联文档永久失效时禁用调度任务。
     * 仍要求 token 所有权，避免旧工作者误禁用新实例已恢复的任务。
     */
    public boolean disableIfOwned(ScheduleLockLease lease, String reason) {
        return updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getEnabled, 0)
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, null)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncate(reason))
        );
    }

    /**
     * 标记“因失锁主动终止”的执行明细。
     * 不更新主调度表，因为它可能已由接管实例继续推进。
     */
    public void markLeaseLost(ScheduleStateContext ctx, String stage) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        String message = "调度锁已失效，终止执行";
        if (StringUtils.hasText(stage)) {
            message += ": " + stage;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
        execUpdate.setId(ctx.getExecId());
        execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
        execUpdate.setMessage(truncate(message));
        execUpdate.setEndTime(new Date());
        execMapper.updateById(execUpdate);
    }

    /**
     * 仅补写执行记录成功状态，不触碰调度主表。
     * 用于文件与向量已完成，但最后写主状态时发现租约丢失的边界情形。
     */
    public void markSuccessExecOnly(ScheduleStateContext ctx,
                                    StoredFileDTO stored,
                                    String contentHash,
                                    String etag,
                                    String lastModified,
                                    String message) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                .id(ctx.getExecId())
                .status(ScheduleRunStatus.SUCCESS.getCode())
                .message(truncate(message))
                .endTime(new Date())
                .fileName(stored != null ? stored.getOriginalFilename() : null)
                .fileSize(stored != null ? stored.getSize() : null)
                .contentHash(contentHash)
                .etag(etag)
                .lastModified(lastModified)
                .build();
        execMapper.updateById(execUpdate);
    }

    /**
     * 将 lockToken 追加到任意调度主表更新的 WHERE 条件。
     * 更新结果为 0 即表示当前实例已失去所有权，调用者必须停止主表状态写回。
     */
    private boolean updateScheduleIfOwned(ScheduleLockLease lease,
                                          LambdaUpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper) {
        /* 租约所有权保护：只有 lockOwner 仍等于当前 token 才更新；被接管时返回 false，不能覆盖新状态。 */
        if (lease == null || updateWrapper == null) {
            return false;
        }
        updateWrapper.eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken());
        return scheduleMapper.update(updateWrapper) > 0;
    }

    /** 主表未更新时给执行明细附加失锁说明，避免运维误以为状态已被成功推进。 */
    private String withLeaseNote(String message, boolean scheduleUpdated) {
        if (scheduleUpdated) {
            return truncate(message);
        }
        String baseMessage = StringUtils.hasText(message) ? message.trim() : "执行完成";
        return truncate(baseMessage + LEASE_LOST_NOTE);
    }

    /** 将错误或说明限制在 512 字符，保护调度状态表字段和页面展示。 */
    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 512) {
            return trimmed;
        }
        return trimmed.substring(0, 512);
    }
}
