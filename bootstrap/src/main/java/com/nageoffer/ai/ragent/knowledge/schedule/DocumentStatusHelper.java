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

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    private static final String SYSTEM_USER = "system";

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

    public boolean tryMarkRunning(String docId) {
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

    public void markFailedIfRunning(String docId) {
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
    }

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
