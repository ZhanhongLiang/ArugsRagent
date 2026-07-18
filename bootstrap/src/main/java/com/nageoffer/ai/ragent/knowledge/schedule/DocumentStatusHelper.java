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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 定时同步场景下的文档状态 CAS 与故障恢复组件。
 *
 * <p>调度租约锁防止同一 schedule 被多实例同时执行；本组件额外以文档状态 RUNNING 做文档级保护，
 * 防止手动分块和定时刷新同时重建同一份文档。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    /** 定时任务写入审计字段时使用的系统操作人。 */
    private static final String SYSTEM_USER = "system";

    /** 文档状态与文件元数据持久化入口。 */
    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 负责文档状态的 CAS 更新和恢复。
     *
     * 这个组件解决的核心问题是：防止定时刷新和手动分块同时跑同一份文档。
     *
     * 它提供了四个核心方法：
     *
     * - `tryMarkRunning(docId)`：CAS 抢占文档运行权，条件 `ne(status, RUNNING)`
     * - `markFailedIfRunning(docId)`：失败时恢复文档状态
     * - `applyRefreshedFileMetadata(docId, stored)`：成功后应用新文件元数据
     * - `recoverStuckRunning(timeoutMinutes)`：恢复卡住的 RUNNING 文档
     *
     * 这个组件提供了第二层并发控制。第一层是任务级别的数据库租约锁（防止同一个 schedule 被多个实例同时执行），第二层是文档级别的 CAS 抢占（防止定时刷新和手动分块同时跑同一份文档）。
     *
     * 这个组件的设计思路是：用 CAS 操作确保文档状态的原子性更新。
     *
     */

    /**
     * 尝试将文档从非 RUNNING 原子切换到 RUNNING。
     * 仅未删除、已启用文档可以被抢占；返回 true 表示本次执行者获得文档级运行权。
     */
    public boolean tryMarkRunning(String docId) {
        // 受影响行数为 1 才说明当前执行者抢占成功，0 表示已被手动或其它调度任务占用。
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        ) > 0;
    }

    /** 仅当文档仍归属 RUNNING 状态时标记失败，避免覆盖后来成功任务的状态。 */
    public void markFailedIfRunning(String docId) {
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
    }

    /**
     * 在新文件已成功分块和索引后，才将文档主表切换到新对象存储元数据。
     * 这样下载或重建失败时旧文件 URL 仍然可用于回滚和继续服务。
     */
    public void applyRefreshedFileMetadata(String docId, StoredFileDTO stored) {
        KnowledgeDocumentDO update = KnowledgeDocumentDO.builder()
                .id(docId)
                .docName(stored.getOriginalFilename())
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .updatedBy(SYSTEM_USER)
                .build();
        int updated = documentMapper.updateById(update);
        if (updated == 0) {
            throw new ClientException("文档不存在");
        }
    }

    /**
     * 将超过安全超时且仍为 RUNNING 的文档重置为 FAILED。
     * 最小 10 分钟避免短暂网络波动或大文件处理被过早误判为卡死。
     */
    public int recoverStuckRunning(long timeoutMinutes) {
        long safeTimeout = Math.max(timeoutMinutes, 10);
        Date threshold = new Date(System.currentTimeMillis() - safeTimeout * 60 * 1000);
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .lt(KnowledgeDocumentDO::getUpdateTime, threshold)
        );
    }
}
