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

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Date;

/**
 * 单次 URL 文档定时刷新的流程编排器。
 *
 * <p>核心安全规则是：新文件必须完成下载、对象存储上传、解析分块、Embedding 和向量写入后，
 * 才能切换文档主表到新文件元数据。Phase 用于在失败或失锁时判断应删旧文件、删新文件还是保留新文件等待补偿。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRefreshProcessor {

    /** URL 刷新遵循“先构建新版本、成功后切换元数据”的两阶段替换策略。 */

    /** 定时任务写入审计字段和重建日志时使用的系统用户。 */
    private static final String SYSTEM_USER = "system";

    /** 调度主表查询入口。 */
    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    /** 每次调度执行明细写入入口。 */
    private final KnowledgeDocumentScheduleExecMapper execMapper;
    /** 文档主表查询入口。 */
    private final KnowledgeDocumentMapper documentMapper;
    /** 读取知识库集合名和 Embedding 配置的 Mapper。 */
    private final KnowledgeBaseMapper kbMapper;
    /** 复用手动分块的完整重建实现，保证两种入口行为一致。 */
    private final KnowledgeDocumentServiceImpl documentService;
    /** 上传新远程文件、清理旧对象的存储端口。 */
    private final FileStorageService fileStorageService;
    /** 支持 ETag/Last-Modified/内容哈希变更检测的远程抓取组件。 */
    private final RemoteFileFetcher remoteFileFetcher;

    /** 调度任务级的数据库租约锁。 */
    private final ScheduleLockManager lockManager;
    /** 锁感知的调度状态写入器。 */
    private final ScheduleStateManager stateManager;
    /** 文档级 RUNNING CAS 与异常恢复辅助组件。 */
    private final DocumentStatusHelper documentStatusHelper;

    /**
     * 在持有调度租约的前提下执行一次完整刷新。
     *
     * 核心方法 `process(ScheduleLockLease lease)` 管理整个刷新流程：
     *
     * - 1.
     *   启动心跳
     * - 2.
     *   校验任务合法性
     * - 3.
     *   创建执行记录
     * - 4.
     *   变更检测
     * - 5.
     *   抢占文档运行权
     * - 6.
     *   上传新文件
     * - 7.
     *   执行分块
     * - 8.
     *   应用文件元数据
     * - 9.
     *   更新状态
     * - 10.
     *   文件清理
     * - 11.
     *   关闭心跳
     * - 12.
     *   释放锁
     *
     * 它还引入了 `Phase` 枚举追踪执行阶段（INIT → DOC_OCCUPIED → CHUNK_STARTED → CHUNK_COMPLETED → FILE_SWITCHED），在关键阶段检测锁是否失效，根据 Phase 精细化控制文件清理策略。
     *
     * 这个组件的设计思路是：把流程编排和具体实现分离，让主线逻辑清晰可见。
     * @param lease 调度扫描器成功抢到的行级租约；为空时不执行任何操作
     */
    public void process(ScheduleLockLease lease) {
        // lease 保护调度任务，文档 RUNNING 保护人工分块；二者共同防止不同维度的并发冲突。
        if (lease == null) {
            return;
        }
        String scheduleId = lease.scheduleId();
        Date startTime = new Date();

        // 步骤 0：启动前确认租约仍属于当前实例，避免拿着已过期锁继续执行。
        if (shouldAbortForLeaseLoss(lease, null, "任务启动")) {
            log.info("定时刷新任务启动时已失去锁，跳过执行: scheduleId={}, lockToken={}",
                    lease.scheduleId(), lease.lockToken());
            return;
        }

        ScheduleLockManager.ScheduleLockHeartbeat heartbeat;
        try {
            // 步骤 1：启动后台心跳，长时间下载/分块期间持续续租。
            heartbeat = lockManager.startHeartbeat(lease);
        } catch (Exception e) {
            log.error("定时刷新启动锁心跳失败，释放锁并终止执行: scheduleId={}, lockToken={}",
                    lease.scheduleId(), lease.lockToken(), e);
            // 心跳无法启动时不进入业务流程，直接释放刚抢到的锁。
            boolean released = lockManager.release(lease);
            if (!released) {
                log.warn("定时刷新启动锁心跳失败且释放锁失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken());
            }
            return;
        }
        RefreshRunState state = new RefreshRunState();
        try {
            // 步骤 2：校验 schedule 和文档仍有效、已启用且是可同步的 URL 来源。
            KnowledgeDocumentScheduleDO schedule = scheduleMapper.selectById(scheduleId);
            if (schedule == null) {
                return;
            }
            state.document = documentMapper.selectById(schedule.getDocId());
            if (state.document == null || (state.document.getDeleted() != null && state.document.getDeleted() == 1)) {
                disableIfOwnedOrMarkLeaseLost(lease, state, "文档不存在或已删除", "禁用调度: 文档不存在或已删除");
                return;
            }
            if (state.document.getEnabled() != null && state.document.getEnabled() == 0) {
                disableIfOwnedOrMarkLeaseLost(lease, state, "文档已禁用", "禁用调度: 文档已禁用");
                return;
            }

            // 文档配置是调度是否继续有效的事实来源，schedule 表中的历史 Cron 不能单独信任。
            String cron = state.document.getScheduleCron();
            boolean enabled = state.document.getScheduleEnabled() != null && state.document.getScheduleEnabled() == 1;
            if (!StringUtils.hasText(cron) || !SourceType.URL.getValue().equalsIgnoreCase(state.document.getSourceType())) {
                enabled = false;
            }

            schedule.setCronExpr(cron);
            Date nextRunTime;
            if (enabled) {
                try {
                    nextRunTime = CronScheduleHelper.nextRunTime(cron, startTime);
                } catch (IllegalArgumentException e) {
                    disableIfOwnedOrMarkLeaseLost(lease, state, "定时表达式不合法", "禁用调度: 定时表达式不合法");
                    return;
                }
                if (nextRunTime == null) {
                    disableIfOwnedOrMarkLeaseLost(lease, state, "无法计算下次执行时间", "禁用调度: 无法计算下次执行时间");
                    return;
                }
            } else {
                disableIfOwnedOrMarkLeaseLost(lease, state, "定时已关闭", "禁用调度: 定时已关闭");
                return;
            }

            // 步骤 3：创建 RUNNING 执行明细，记录本轮开始、结束、结果和远程文件指纹。
            KnowledgeDocumentScheduleExecDO exec = KnowledgeDocumentScheduleExecDO.builder()
                    .scheduleId(scheduleId)
                    .docId(state.document.getId())
                    .kbId(state.document.getKbId())
                    .status(ScheduleRunStatus.RUNNING.getCode())
                    .startTime(startTime)
                    .build();
            execMapper.insert(exec);

            state.ctx = ScheduleStateContext.builder()
                    .scheduleId(schedule.getId())
                    .execId(exec.getId())
                    .cronExpr(schedule.getCronExpr())
                    .startTime(startTime)
                    .nextRunTime(nextRunTime)
                    .build();

            // 步骤 4：根据上次 ETag、Last-Modified、contentHash 做条件抓取，
            // 未变化则标记 SKIPPED，变化才继续后面的上传和分块流程。
            // 条件抓取避免远程内容未变化时重复重建 Chunk 与向量。
            try (RemoteFileFetcher.RemoteFetchResult fetchResult = remoteFileFetcher.fetchIfChanged(
                    state.document.getSourceLocation(),
                    schedule.getLastEtag(),
                    schedule.getLastModified(),
                    schedule.getLastContentHash(),
                    state.document.getDocName()
            )) {
                state.fetch = FetchSnapshot.from(fetchResult);

                if (!fetchResult.changed()) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, fetchResult, "远程文件未变化");
                    return;
                }

                // 步骤 5：调度锁只保护 schedule，仍需抢占文档 RUNNING 以防与手动分块并发。
                if (DocumentStatus.RUNNING.getCode().equals(state.document.getStatus())) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, "文档正在分块中，跳过本次调度", "文档占用中，跳过调度");
                    return;
                }

                if (shouldAbortForLeaseLoss(lease, heartbeat, "领取文档运行权")) {
                    state.leaseLost = true;
                    stateManager.markLeaseLost(state.ctx, "领取文档运行权");
                    return;
                }
                // 文档状态 CAS 防止当前定时刷新与手动 startChunk 同时获得文档重建权。
                if (!documentStatusHelper.tryMarkRunning(state.document.getId())) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, "文档正在分块中，跳过本次调度", "文档运行权争抢失败");
                    return;
                }
                state.phase = Phase.DOC_OCCUPIED;

                // 步骤 6：远程文件确认变化后上传快照到对象存储，先暂存新 URL，旧 URL 暂不切换。
                KnowledgeBaseDO kbDO = kbMapper.selectById(state.document.getKbId());
                if (kbDO == null) {
                    throw new ClientException("知识库不存在");
                }

                // 新文件完全分块并索引成功前保留旧 URL，以便失败时继续使用旧版本。
                state.oldFileUrl = state.document.getFileUrl();
                try (InputStream tempIn = Files.newInputStream(fetchResult.tempFile())) {
                    state.stored = fileStorageService.upload(
                            kbDO.getCollectionName(),
                            tempIn,
                            fetchResult.size(),
                            fetchResult.fileName(),
                            fetchResult.contentType()
                    );
                }

                KnowledgeDocumentDO runtimeDoc = documentMapper.selectById(state.document.getId());
                if (runtimeDoc == null) {
                    throw new ClientException("文档不存在");
                }
                runtimeDoc.setDocName(state.stored.getOriginalFilename());
                runtimeDoc.setFileUrl(state.stored.getUrl());
                runtimeDoc.setFileType(state.stored.getDetectedType());
                runtimeDoc.setFileSize(state.stored.getSize());
                runtimeDoc.setUpdatedBy(SYSTEM_USER);

                // 步骤 7：复用文档分块主流程，完成解析、切片、Embedding 和向量写入。
                if (shouldAbortForLeaseLoss(lease, heartbeat, "执行文档分块")) {
                    state.leaseLost = true;
                    stateManager.markLeaseLost(state.ctx, "执行文档分块");
                    return;
                }
                // 定时刷新与手动分块复用同一重建实现，避免解析器、策略和向量持久化规则分叉。
                state.phase = Phase.CHUNK_STARTED;
                UserContext.set(LoginUser.builder().username(SYSTEM_USER).build());
                try {
                    documentService.chunkDocument(runtimeDoc);
                } finally {
                    UserContext.clear();
                }

                KnowledgeDocumentDO latest = documentMapper.selectById(state.document.getId());
                if (latest == null || !DocumentStatus.SUCCESS.getCode().equals(latest.getStatus())) {
                    markFailedIfOwnedOrMarkLeaseLost(lease, state, "分块失败", "分块失败写回调度状态");
                    return;
                }

                state.phase = Phase.CHUNK_COMPLETED;
                // 步骤 8：只在分块成功后切换文档文件元数据，失败时旧文件继续作为可见来源。
                documentStatusHelper.applyRefreshedFileMetadata(state.document.getId(), state.stored);
                state.phase = Phase.FILE_SWITCHED;

                // 步骤 9：更新调度状态。写回 SUCCESS、nextRunTime、文件指纹，并补全 exec 执行记录。
                markSuccessIfOwnedOrMarkLeaseLost(lease, state, fetchResult, "刷新成功写回调度状态");
            }
        } catch (Exception e) {
            // 异常收尾：按 Phase 恢复 RUNNING、标记失败，或在已切换成功后仅补写执行明细。
            log.error("定时刷新失败: scheduleId={}, docId={}, kbId={}",
                    scheduleId,
                    state.document != null ? state.document.getId() : null,
                    state.document != null ? state.document.getKbId() : null,
                    e);
            if (state.phase != Phase.FILE_SWITCHED) {
                if (state.hasDocumentOccupied()) {
                    documentStatusHelper.markFailedIfRunning(state.document.getId());
                }
                if (state.ctx != null) {
                    markFailedIfOwnedOrMarkLeaseLost(lease, state, e.getMessage(), "异常失败写回调度状态");
                }
            } else if (state.ctx != null) {
                stateManager.markSuccessExecOnly(
                        state.ctx,
                        state.stored,
                        state.fetch != null ? state.fetch.contentHash() : null,
                        state.fetch != null ? state.fetch.etag() : null,
                        state.fetch != null ? state.fetch.lastModified() : null,
                        "刷新成功（调度状态写回失败）"
                );
                log.error("定时刷新已完成文档切换，但写回调度状态失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken(), e);
            }
        } finally {
            // 步骤 11：主流程结束后关闭心跳，避免后台继续刷新租约。
            heartbeat.close();
            // 步骤 10：按最后安全阶段清理旧/新文件，并对失锁时仍 RUNNING 的文档做恢复。
            if (state.leaseLost && state.phase == Phase.DOC_OCCUPIED && state.document != null) {
                documentStatusHelper.markFailedIfRunning(state.document.getId());
            }
            // FILE_SWITCHED 后删除旧文件；CHUNK_COMPLETED 前失败则删除新临时对象；中间成功但未切换时保留新文件等待处理。
            if (state.phase == Phase.FILE_SWITCHED) {
                deleteOldFileQuietly(state.oldFileUrl, state.stored != null ? state.stored.getUrl() : null);
            } else if (state.stored != null && state.phase.ordinal() < Phase.CHUNK_COMPLETED.ordinal()) {
                deleteOldFileQuietly(state.stored.getUrl(), null);
            } else if (state.stored != null) {
                log.warn("定时刷新分块已完成但未完成文件元数据切换，保留新文件待后续处理: scheduleId={}, docId={}, fileUrl={}",
                        scheduleId, state.document != null ? state.document.getId() : null, state.stored.getUrl());
            }
            // 步骤 12：无论成功、失败或跳过，都尝试释放当前租约。
            boolean released = lockManager.release(lease);
            if (!released && !state.leaseLost && !heartbeat.isLost()) {
                log.warn("定时刷新释放锁失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken());
            }
        }
    }

    /** 禁用调度；主表 token 条件更新失败时标记失锁，后续不得再写主表。 */
    private void disableIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                               RefreshRunState state,
                                               String reason,
                                               String logStage) {
        if (!stateManager.disableIfOwned(lease, reason)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /** 将远程抓取返回的“未变化”结果写为跳过；失锁时只记录日志。 */
    private void markSkippedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   RemoteFileFetcher.RemoteFetchResult fetchResult,
                                                   String logStage) {
        if (!stateManager.markSkippedIfOwned(lease, state.ctx, fetchResult)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /** 将业务原因（例如文档被占用）写为跳过；失锁时停止主状态推进。 */
    private void markSkippedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   String message,
                                                   String logStage) {
        if (!stateManager.markSkippedIfOwned(lease, state.ctx, message)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /** 记录刷新失败；若 token 不再匹配，不能覆盖接管实例的调度主状态。 */
    private void markFailedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                  RefreshRunState state,
                                                  String message,
                                                  String logStage) {
        if (!stateManager.markFailedIfOwned(lease, state.ctx, message)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /** 记录刷新成功及远程文件指纹；若失锁则把状态交给接管实例，只保留执行明细。 */
    private void markSuccessIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   RemoteFileFetcher.RemoteFetchResult fetchResult,
                                                   String logStage) {
        if (!stateManager.markSuccessIfOwned(lease, state.ctx, fetchResult, state.stored)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 在昂贵或不可逆阶段前检查租约。
     * 心跳已标记失锁时直接终止；否则同步续约一次作为最终所有权确认，防止心跳刚好尚未执行。
     */
    private boolean shouldAbortForLeaseLoss(ScheduleLockLease lease,
                                            ScheduleLockManager.ScheduleLockHeartbeat heartbeat,
                                            String stage) {
        if (heartbeat != null && heartbeat.isLost()) {
            log.warn("定时刷新锁已丢失，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                    lease.scheduleId(), stage, lease.lockToken());
            return true;
        }
        try {
            boolean renewed = lockManager.renew(lease);
            if (!renewed) {
                log.warn("定时刷新锁续约失败，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                        lease.scheduleId(), stage, lease.lockToken());
            }
            return !renewed;
        } catch (Exception e) {
            log.warn("定时刷新锁续约异常，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                    lease.scheduleId(), stage, lease.lockToken(), e);
            return true;
        }
    }

    /** 统一记录由于失锁而跳过主调度状态写回的诊断日志。 */
    private void logScheduleStateWriteSkipped(ScheduleLockLease lease, String stage) {
        log.warn("定时刷新锁已失效，未写回调度主状态: scheduleId={}, stage={}, lockToken={}",
                lease.scheduleId(), stage, lease.lockToken());
    }

    /** 删除不再被文档引用的对象存储文件；失败只告警，避免影响已经完成的重建结果。 */
    private void deleteOldFileQuietly(String oldFileUrl, String newFileUrl) {
        if (!StringUtils.hasText(oldFileUrl) || oldFileUrl.equals(newFileUrl)) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(oldFileUrl);
        } catch (Exception e) {
            log.warn("定时刷新文件清理失败: {}", oldFileUrl, e);
        }
    }

    /**
     * 刷新运行的单向阶段状态。
     * finally 块依据它决定是否恢复文档状态、删除新文件或删除旧文件。
     */
    private enum Phase {
        /** 尚未抢占文档。 */
        INIT,
        /** 已将文档 CAS 标为 RUNNING。 */
        DOC_OCCUPIED,
        /** 已开始调用文档重建主流程。 */
        CHUNK_STARTED,
        /** 新文件已成功生成 Chunk 和向量，但元数据尚未切换。 */
        CHUNK_COMPLETED,
        /** 文档主表已切换到新文件，可安全删除旧文件。 */
        FILE_SWITCHED
    }

    /** 单次刷新运行期间的可变状态，集中保存资源句柄和最终清理所需信息。 */
    private static final class RefreshRunState {

        /** 当前被刷新的文档快照。 */
        private KnowledgeDocumentDO document;
        /** 主表/执行明细状态写入所需上下文。 */
        private ScheduleStateContext ctx;
        /** 切换前旧对象 URL，只有新版本成功后才删除。 */
        private String oldFileUrl;
        /** 本轮上传的新对象存储快照。 */
        private StoredFileDTO stored;
        /** 是否已确认失去调度租约。 */
        private boolean leaseLost;
        /** 当前运行到的最后安全阶段。 */
        private Phase phase = Phase.INIT;
        /** 远程文件 ETag、时间与内容哈希快照。 */
        private FetchSnapshot fetch;

        /** @return 是否已成功把文档切换为 RUNNING，异常时需要恢复终态。 */
        private boolean hasDocumentOccupied() {
            return phase.ordinal() >= Phase.DOC_OCCUPIED.ordinal();
        }
    }

    /** 只保留状态写回所需的远程文件指纹，避免持有已关闭的 RemoteFetchResult。 */
    private record FetchSnapshot(String contentHash, String etag, String lastModified) {

        /** 从一次抓取结果复制不可变指纹；空结果安全返回 null。 */
        private static FetchSnapshot from(RemoteFileFetcher.RemoteFetchResult fetchResult) {
            if (fetchResult == null) {
                return null;
            }
            return new FetchSnapshot(fetchResult.contentHash(), fetchResult.etag(), fetchResult.lastModified());
        }
    }
}
