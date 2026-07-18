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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.access.domain.KnowledgeAccessScope;
import com.nageoffer.ai.ragent.knowledge.access.service.KnowledgeAccessService;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ProcessMode;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.schedule.CronScheduleHelper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库文档从上传登记、异步分块到向量重建的核心业务实现。
 *
 * <p>上传阶段只保存原文件和 PENDING 文档记录；点击分块后通过 RocketMQ 事务消息异步执行解析、切片、向量化，
 * 最后以“删除旧数据、写入新数据、更新文档状态”为一个逻辑重建单元。这样既避免大文件阻塞 HTTP 请求，
 * 又避免重复点击产生并发重建任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    /**
     * 文档重建全链路：upload 创建 PENDING 记录；startChunk 用 CAS 更新为 RUNNING 并发送半事务消息；
     * 消费者调用 executeChunk/runChunkTask 完成解析、切片、向量化；最终以重建事务替换旧 Chunk 与向量。
     * 解析大文件和调用 Embedding 模型均耗时、占内存，因此必须放在 HTTP 上传请求之外。
     */

    /** 知识库主表，用于校验知识库存在并获取目标向量集合名。 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /** 文档主表，维护文档来源、状态、分块数量和处理配置。 */
    private final KnowledgeDocumentMapper documentMapper;
    /** 固定模式下选择 Tika 等文档解析器。 */
    private final DocumentParserSelector parserSelector;
    /** 固定模式下选择分块策略。 */
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    /** 保存上传文件、读取 S3 对象和删除原文件的存储端口。 */
    private final FileStorageService fileStorageService;
    /** 删除及写入向量的抽象端口。 */
    private final VectorStoreService vectorStoreService;
    /** 持久化业务层 Chunk 记录的服务。 */
    private final KnowledgeChunkService knowledgeChunkService;
    /** 解析文档分块 JSON 配置。 */
    private final ObjectMapper objectMapper;
    /** 为启用定时同步的 URL 文档创建或更新调度记录。 */
    private final KnowledgeDocumentScheduleService scheduleService;
    /** 加载用户配置的摄取流水线定义。 */
    private final IngestionPipelineService ingestionPipelineService;
    /** 查询流水线持久化数据，用于校验和反序列化。 */
    private final IngestionPipelineMapper ingestionPipelineMapper;
    /** 执行自定义 PIPELINE 模式的节点引擎。 */
    private final IngestionEngine ingestionEngine;
    /** 为固定模式的 Chunk 批量调用 Embedding。 */
    private final ChunkEmbeddingService chunkEmbeddingService;
    /** 保存每次重建尝试的阶段耗时和失败原因。 */
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    /** 直接查询和删除某文档的旧业务 Chunk。 */
    private final KnowledgeChunkMapper chunkMapper;
    /** 手动控制重建事务边界，协调旧数据删除、新数据写入与文档状态更新。 */
    private final TransactionOperations transactionOperations;
    /** 发送分块事务消息的业务生产端口。 */
    private final MessageQueueProducer messageQueueProducer;
    /** 定时同步功能的开关和默认配置。 */
    private final KnowledgeScheduleProperties scheduleProperties;
    /** URL 文档同步时下载远程文件的组件。 */
    private final RemoteFileFetcher remoteFileFetcher;
    /** 知识数据范围的统一授权入口，文档管理和检索共用同一语义。 */
    private final KnowledgeAccessService knowledgeAccessService;

    /** 文档分块事务消息 topic，必须和消费者及回查器保持一致。 */
    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    @Override
    /**
     * 登记一份文档，但不立即解析和建向量。
     * 本地上传文件保存到对象存储；URL 来源保存可复用的远程地址，待分块任务或定时同步时再下载。
     */
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file) {
        knowledgeAccessService.requireManageKnowledgeBase(kbId);
        // 上传只登记文档并保存来源文件，不在 HTTP 请求中执行高成本向量构建。
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));
        // 标准化来源类型，兼容接口传入的大小写或历史值。
        SourceType sourceType = SourceType.normalize(requestParam.getSourceType());
        // URL 与 FILE 的必填项、定时同步开关和 Cron 在此统一校验。
        validateSourceAndSchedule(sourceType, requestParam);
        // 本地文件写对象存储；URL 来源则解析远程元数据为 StoredFileDTO。
        StoredFileDTO stored = resolveStoredFile(kbDO.getCollectionName(), sourceType, requestParam.getSourceLocation(), file);
        // 固定 CHUNK 模式或自定义 PIPELINE 模式的配置在创建文档时固化，方便以后稳定重建。
        ProcessModeConfig modeConfig = resolveProcessModeConfig(requestParam);

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? StrUtil.trimToNull(requestParam.getSourceLocation()) : null)
                .scheduleEnabled(isScheduleEnabled(sourceType, requestParam) ? 1 : 0)
                .scheduleCron(isScheduleEnabled(sourceType, requestParam) ? StrUtil.trimToNull(requestParam.getScheduleCron()) : null)
                .processMode(modeConfig.processMode().getValue())
                .chunkStrategy(modeConfig.chunkingMode() != null ? modeConfig.chunkingMode().getValue() : null)
                .chunkConfig(modeConfig.chunkConfig())
                .pipelineId(modeConfig.pipelineId())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        documentMapper.insert(documentDO);
        // 返回不含内部存储细节的视图对象。
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    /**
     * 通过 RocketMQ 事务消息启动一次异步分块重建。
     * 本地事务先 CAS 更新状态，再由 Broker 决定提交半消息，避免“状态未改但消费者已执行”或重复点击并发执行。
     */
    public void startChunk(String docId) {
        knowledgeAccessService.requireManageDocument(docId);
        // 建立消息事件，操作人会在消费者线程重建到 UserContext。
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId(docId)
                .operator(UserContext.getUsername())
                .build();
        /**
         * CAS事务更新
         *                 UPDATE t_knowledge_document
         * SET status = 'running', updated_by = 'xxx'
         * WHERE id = ? AND status != 'running'
         *
         * 关键在于 `WHERE status != 'running'` 这个条件。如果文档当前状态已经是 `running`，这条 UPDATE 不会匹配到任何记录，`updated` 返回 0。
         *
         * 为什么需要这个条件？因为用户可能手抖点了两次执行分块按钮，或者多个用户同时点击，或者前端因为网络问题重复发送了请求。如果没有这个条件，两个请求都会成功，会触发两次分块任务，导致重复处理。
         *
         * 有了这个条件，第一个请求把状态改成 `running`，第二个请求发现状态已经是 `running`，UPDATE 不会匹配到记录，`updated` 返回 0，方法抛出异常告诉用户文档正在处理中。
         *
         * 这就是 CAS 的核心思想：只有当前值符合预期时才更新，否则更新失败。这是一种乐观并发控制机制，不需要加锁，性能很好。
         */
        // lambda 是生产者本地事务：Broker 收到 half 消息后才执行此段数据库状态变更。
        messageQueueProducer.sendInTransaction(
                chunkTopic, // RocketMQ topic
                docId,
                "文档分块",
                event, // 事件载荷
                arg -> {
                    int updated = documentMapper.update(
                            new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                    .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                                    .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                                    .eq(KnowledgeDocumentDO::getId, docId)
                                    .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                    );
                    if (updated == 0) { // 条件更新失败表示文档不存在或已处于 RUNNING。
                        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                        throw new ClientException("文档分块操作正在进行中，请稍后再试");
                    }
                    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                    event.setKbId(documentDO.getKbId());
                    // 若 URL 文档配置了定时同步，此时同步创建或更新调度记录。
                    scheduleService.upsertSchedule(documentDO);
                }
        );
    }

    @Override
    /**
     * MQ 消费端的业务入口。
     * 消息晚于删除操作到达时视为幂等跳过，而不是抛错触发无意义重试。
     */
    public void executeChunk(String docId) {
        // 消息可能在文档删除后才到达；不存在时按幂等语义安全跳过。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        if (documentDO == null) {
            log.warn("文档不存在，跳过分块任务, docId={}", docId);
            return;
        }

        runChunkTask(documentDO);
    }

    /**
     * 执行一次完整文档重建并写入独立日志记录。
     * 文档可以多次因策略调整、定时同步或人工重试而重建，故每次尝试都要保留阶段耗时和结果历史。
     */
    private void runChunkTask(KnowledgeDocumentDO documentDO) {
        // 每次重建单独建日志行，保留同一文档的历史尝试。
        String docId = documentDO.getId();
        ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());
        // 创建分块日志
        /**
         * 分块日志记录在 `t_knowledge_document_chunk_log` 表中，每次执行分块任务都会创建一条新记录。
         * 这个表的作用是记录分块任务的执行情况，包括处理模式、分块策略、各阶段耗时、成功或失败等信息。
         *
         * 为什么要单独记录日志，而不是直接更新文档表的状态？因为文档可能会多次重新分块（比如调整分块参数后重新处理），
         * 每次执行的情况都不一样。单独的日志表可以保留完整的历史记录，方便排查问题和性能分析。
         */
        KnowledgeDocumentChunkLogDO chunkLog = KnowledgeDocumentChunkLogDO.builder()
                .docId(docId)
                .status(DocumentStatus.RUNNING.getCode())
                .processMode(processMode.getValue())
                .chunkStrategy(documentDO.getChunkStrategy())
                .pipelineId(documentDO.getPipelineId())
                .startTime(new Date())
                .build();
        chunkLogMapper.insert(chunkLog);

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;

        try {
            // 根据文档配置选择固定 CHUNK 流程或用户定义的 PIPELINE 流程。
            /**
             * 项目支持两种处理模式：
             *
             * **CHUNK 模式**：调用 `runChunkProcess` 方法，执行固定的处理流程：
             *
             * - 1.
             *   从对象存储读取文件
             * - 2.
             *   用 Apache Tika 解析文件，提取纯文本
             * - 3.
             *   根据文档配置的分块策略（`chunkStrategy`）和分块参数（`chunkConfig`）执行分块
             * - 4.
             *   调用 Embedding API 为每个 chunk 生成向量
             * - 5.
             *   返回 `List<VectorChunk>`，每个 `VectorChunk` 包含文本内容、向量、元数据
             *
             * 这是最常用的模式，适合大部分文档。
             *
             * **PIPELINE 模式**：调用 `runPipelineProcess` 方法，执行用户自定义的 Pipeline 流程：
             *
             * - 1.
             *   读取文件内容
             * - 2.
             *   构建 `IngestionContext`，设置 `skipIndexerWrite=true`（关键参数，后面会讲）
             * - 3.
             *   执行 Pipeline 定义的节点序列（可能包括：获取节点 → 解析节点 → 清洗节点 → 分块节点 → 增强节点等）
             * - 4.
             *   从 context 中提取 chunks
             * - 5.
             *   返回 `List<VectorChunk>`
             */
            List<VectorChunk> chunkResults;
            if (ProcessMode.PIPELINE == processMode) {
                long start = System.currentTimeMillis();
                chunkResults = runPipelineProcess(documentDO);
                chunkDuration = System.currentTimeMillis() - start;
            } else {
                ChunkProcessResult result = runChunkProcess(documentDO);
                extractDuration = result.extractDuration();
                chunkDuration = result.chunkDuration();
                embedDuration = result.embedDuration();
                chunkResults = result.chunks();
            }
            // 将业务 Chunk、向量和文档状态作为一次逻辑重建统一提交。
            long persistStart = System.currentTimeMillis();
            String collectionName = resolveCollectionName(documentDO.getKbId());
            // 原子性写入：任何一步失败都不能让文档处于“新旧数据混杂”的状态。
            int savedCount = persistChunksAndVectorsAtomically(collectionName, docId, chunkResults);
            persistDuration = System.currentTimeMillis() - persistStart;

            // 重建成功后记录各阶段耗时，便于分析解析、切片、Embedding 或写库瓶颈。
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, null);
        } catch (Exception e) {
            log.error("文档分块任务执行失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, e.getMessage());
        }
    }

    /**
     * 原子替换某文档已有的 Chunk 与向量。
     * 方法用 `TransactionTemplate` 手动开启事务，在事务中执行五个操作：
     *
     * - 1.
     *   **DELETE 旧 chunks**：删除文档的所有旧 chunk 记录
     * - 2.
     *   **INSERT 新 chunks**：批量插入新 chunk 记录
     * - 3.
     *   **DELETE 旧 vectors**：删除文档的所有旧向量
     * - 4.
     *   **INSERT 新 vectors**：批量插入新向量
     * - 5.
     *   **UPDATE 文档状态**：更新文档状态为 `success`，更新 chunk 数量
     *
     * 这五个操作在同一个事务中，要么全部成功，要么全部失败。
     * @param collectionName 目标向量集合名
     * @param docId 待重建文档 id
     * @param chunkResults 已含向量的新 Chunk 列表
     * @return 成功保存的 Chunk 数量
     */
    private int persistChunksAndVectorsAtomically(String collectionName, String docId, List<VectorChunk> chunkResults) {
        // 重建策略：删除旧业务 Chunk、插入新 Chunk、删除旧向量、插入新向量，最后标记文档成功；逻辑上必须同成同败。
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();
        // 重建采用先删后插，而不是按序号 UPDATE，避免新旧 Chunk 数量变化时残留脏数据。
        /**
         * 你可能会问：为什么不用 UPDATE 更新 chunk，而要先删后插？
         *
         * 假设旧文档有 100 个 chunk，新文档只有 50 个 chunk。如果用 UPDATE：
         *
         * - 前 50 个 chunk 可以更新
         * - 后 50 个旧 chunk 怎么办？UPDATE 语句无法删除它们
         *
         * 如果用 DELETE + INSERT：
         *
         * - 先删除所有旧 chunk（100 个）
         * - 再插入所有新 chunk（50 个）
         * - 数据完全替换，不会留下脏数据
         *
         * 先删后插是一种简单可靠的数据替换策略，不需要考虑新旧数据的数量差异。
         */
        transactionOperations.executeWithoutResult(status -> {
            // 业务表和向量库的删除、写入、状态回写必须在同一重建单元内完成。
            knowledgeChunkService.deleteByDocId(docId);
            knowledgeChunkService.batchCreate(docId, chunks);
            vectorStoreService.deleteDocumentVectors(collectionName, docId);
            vectorStoreService.indexDocumentChunks(collectionName, docId, chunkResults);
            KnowledgeDocumentDO updateDocumentDO = KnowledgeDocumentDO.builder()
                    .id(docId)
                    .chunkCount(chunks.size())
                    .status(DocumentStatus.SUCCESS.getCode())
                    .updatedBy(UserContext.getUsername())
                    .build();
            documentMapper.updateById(updateDocumentDO);
        });
        return chunks.size();
    }

    /**
     * 结束一次分块尝试的日志，并写入四阶段耗时、总耗时、最终状态与错误摘要。
     */
    private void updateChunkLog(String logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embedDuration, long persistDuration,
                                long totalDuration, String errorMessage) {
        KnowledgeDocumentChunkLogDO update = KnowledgeDocumentChunkLogDO.builder()
                .id(logId)
                .status(status)
                .chunkCount(chunkCount)
                .extractDuration(extractDuration)
                .chunkDuration(chunkDuration)
                .embedDuration(embedDuration)
                .persistDuration(persistDuration)
                .totalDuration(totalDuration)
                .errorMessage(errorMessage)
                .endTime(new Date())
                .build();
        chunkLogMapper.updateById(update);
    }

    /**
     * 固定 CHUNK 模式的前三阶段：从对象存储提取文本、按策略切片、批量生成向量。
     * 失败直接抛出，让 runChunkTask 统一写失败文档状态与分块日志。
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        // 固定模式是标准流程：读源文件 -> 解析文本 -> 切分 Chunk -> 生成 Embedding。
        ChunkingMode chunkingMode = ChunkingMode.fromValue(documentDO.getChunkStrategy());
        // 知识库级 Embedding 模型决定本次重建的向量空间语义，不能随意混用。
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO);

        // 每个阶段单独计时，后续在 ChunkLog 中定位性能瓶颈。
        long extractStart = System.currentTimeMillis();
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = parserSelector.select(ParserType.TIKA.getType()).extractText(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            // 分块策略由文档配置决定，例如固定窗口或结构感知分块。
            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            long chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            long chunkDuration = System.currentTimeMillis() - chunkStart;

            long embedStart = System.currentTimeMillis();
            // 批量向量化会原地填充每个 VectorChunk.embedding，Indexer/持久化阶段无需再次调用模型。
            chunkEmbeddingService.embed(chunks, embeddingModel);
            long embedDuration = System.currentTimeMillis() - embedStart;

            return new ChunkProcessResult(chunks, extractDuration, chunkDuration, embedDuration);
        } catch (Exception e) {
            throw new RuntimeException("文档内容提取或分块失败", e);
        }
    }

    /** 固定 CHUNK 模式的阶段产物和耗时快照。 */
    private record ChunkProcessResult(List<VectorChunk> chunks, long extractDuration, long chunkDuration,
                                      long embedDuration) {
    }

    /** 上传/更新时解析出的处理模式配置：固定分块使用 strategy/config，Pipeline 模式使用 pipelineId。 */
    private record ProcessModeConfig(ProcessMode processMode, ChunkingMode chunkingMode, String chunkConfig,
                                     String pipelineId) {
    }

    /**
     * 执行用户配置的摄取流水线。
     * Pipeline 内的 IndexerNode 被设为 skipIndexerWrite=true，因为本服务统一负责最终业务 Chunk 与向量的原子替换。
     */
    private List<VectorChunk> runPipelineProcess(KnowledgeDocumentDO documentDO) {
        // Pipeline 负责解析/清洗/切片，最终业务表与向量库持久化仍由本服务统一控制。
        String docId = String.valueOf(documentDO.getId());
        String pipelineId = documentDO.getPipelineId();

        // 文档创建时已校验 Pipeline 模式必须绑定流水线，这里作为运行期兜底。
        if (pipelineId == null) {
            throw new IllegalStateException("Pipeline模式下Pipeline ID为空：docId=" + docId);
        }

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());

        // 加载并反序列化当前流水线定义，支持后续节点按配置执行。
        PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(pipelineId);

        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件内容失败：docId=" + docId, e);
        }

        // 预置 rawBytes 让 FetcherNode 跳过重复下载；预置向量空间供 Indexer 校验；关键是跳过真实写入。
        IngestionContext context = IngestionContext.builder()
                .taskId(docId)
                .pipelineId(pipelineId)
                .rawBytes(fileBytes)
                .mimeType(documentDO.getFileType())
                .vectorSpaceId(VectorSpaceId.builder()
                        .logicalName(kbDO.getCollectionName())
                        .build())
                .skipIndexerWrite(true)
                .build();

        IngestionContext result = ingestionEngine.execute(pipelineDef, context);

        // 引擎将节点异常收敛在 Context，转换为异常让外层统一标记本次重建失败。
        if (result.getError() != null) {
            throw new RuntimeException("Pipeline执行失败：" + result.getError().getMessage(), result.getError());
        }

        List<VectorChunk> chunks = result.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
            return List.of();
        }

        return chunks;
    }

    /**
     * 定时同步入口。
     * 调度器会先下载并准备运行时文档对象，再复用相同重建流程，不需要再经过 MQ 发送步骤。
     */
    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        // 定时同步已准备好最新来源文件，直接进入完整分块任务。
        if (documentDO == null) {
            return;
        }
        runChunkTask(documentDO);
    }

    /** 在独立事务中将文档状态标记为 FAILED，确保异常路径也能留下可见终态。 */
    private void markChunkFailed(String docId) {
        transactionOperations.executeWithoutResult(status -> {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(update);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /**
     * 删除文档、业务 Chunk、向量和调度记录。
     * 分块运行中禁止删除，避免消费者在删除过程中写回新数据；对象存储删除失败只告警，不回滚数据库清理。
     */
    public void delete(String docId) {
        knowledgeAccessService.requireManageDocument(docId);
        // 先阻止运行中文档，再清理业务表和向量；对象存储失败不应回滚数据库清理。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 分块运行中删除会和消费者写入竞争，必须拒绝。
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        knowledgeChunkService.deleteByDocId(docId);
        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        String collectionName = resolveCollectionName(documentDO.getKbId());
        vectorStoreService.deleteDocumentVectors(collectionName, docId);
        deleteStoredFileQuietly(documentDO);
    }

    @Override
    /** 读取单个文档的展示信息，不加载大字段 Chunk 或向量。 */
    public KnowledgeDocumentVO get(String docId) {
        knowledgeAccessService.requireReadableDocument(docId);
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /**
     * 更新文档名称、处理配置和 URL 调度配置。
     * 更新配置只影响下一次分块；已有向量不会在此同步重建，避免一次编辑阻塞管理接口。
     */
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        knowledgeAccessService.requireManageDocument(docId);
        // 修改 processMode/chunkConfig 只决定下一次重建行为，不会立即重建已有向量。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 运行中修改配置会让同一次任务前后语义不一致，必须拒绝。
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        LambdaUpdateWrapper<KnowledgeDocumentDO> updateWrapper = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, documentDO.getId())
                .set(KnowledgeDocumentDO::getDocName, docName.trim())
                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername());

        // processMode 非空时才修改处理配置，允许单独改名称或调度信息。
        if (StringUtils.hasText(requestParam.getProcessMode())) {
            ProcessMode processMode = ProcessMode.normalize(requestParam.getProcessMode());
            updateWrapper.set(KnowledgeDocumentDO::getProcessMode, processMode.getValue());

            if (ProcessMode.CHUNK == processMode) {
                ChunkingMode chunkingMode = ChunkingMode.fromValue(requestParam.getChunkStrategy());
                String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, requestParam.getChunkConfig());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, chunkingMode.getValue());
                // JSONB 显式转换让 PostgreSQL 按结构化字段保存分块选项。
                updateWrapper.setSql("chunk_config = CAST({0} AS jsonb)", chunkConfig);
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, null);
            } else {
                if (!StringUtils.hasText(requestParam.getPipelineId())) {
                    throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
                }
                try {
                    ingestionPipelineService.get(requestParam.getPipelineId());
                } catch (Exception e) {
                    throw new ClientException("指定的Pipeline不存在: " + requestParam.getPipelineId());
                }
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, requestParam.getPipelineId());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, null);
                updateWrapper.set(KnowledgeDocumentDO::getChunkConfig, null);
            }
        }

        // 仅 URL 文档存在可重复下载的来源，才允许配置定时同步。
        boolean scheduleChanged = false;
        if (SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            String newSourceLocation = requestParam.getSourceLocation();
            Integer newScheduleEnabled = requestParam.getScheduleEnabled();
            String newScheduleCron = requestParam.getScheduleCron();

            if (StringUtils.hasText(newSourceLocation)) {
                updateWrapper.set(KnowledgeDocumentDO::getSourceLocation, newSourceLocation.trim());
                scheduleChanged = true;
            }
            if (newScheduleEnabled != null) {
                updateWrapper.set(KnowledgeDocumentDO::getScheduleEnabled, newScheduleEnabled);
                scheduleChanged = true;
            }
            if (StringUtils.hasText(newScheduleCron)) {
                try {
                    CronScheduleHelper.nextRunTime(newScheduleCron, new Date());
                    // 验证 Cron 且限制最短周期，避免错误配置对远程站点和本系统造成压力。
                    if (CronScheduleHelper.isIntervalLessThan(newScheduleCron, new Date(), 60)) {
                        throw new ClientException("定时周期不能小于 60 秒");
                    }
                } catch (IllegalArgumentException e) {
                    throw new ClientException("定时表达式不合法: " + e.getMessage());
                }
                updateWrapper.set(KnowledgeDocumentDO::getScheduleCron, newScheduleCron.trim());
                scheduleChanged = true;
            }

            // 启用定时拉取时，最终生效状态必须同时具有 Cron 和来源地址。
            if (scheduleChanged) {
                KnowledgeDocumentDO willBe = documentMapper.selectById(docId);
                Integer finalEnabled = newScheduleEnabled != null ? newScheduleEnabled : willBe.getScheduleEnabled();
                String finalCron = StringUtils.hasText(newScheduleCron) ? newScheduleCron.trim() : willBe.getScheduleCron();
                String finalLocation = StringUtils.hasText(newSourceLocation) ? newSourceLocation.trim() : willBe.getSourceLocation();

                if (finalEnabled != null && finalEnabled == 1) {
                    if (!StringUtils.hasText(finalCron)) {
                        throw new ClientException("启用定时拉取时必须设置定时表达式");
                    }
                    if (!StringUtils.hasText(finalLocation)) {
                        throw new ClientException("启用定时拉取时必须设置来源地址");
                    }
                }
            }
        }

        documentMapper.update(updateWrapper);

        if (scheduleChanged) {
            // 保存文档后再读取最新状态，使调度服务基于最终合并结果创建/停用任务。
            KnowledgeDocumentDO updated = documentMapper.selectById(docId);
            scheduleService.upsertSchedule(updated);
        }
    }

    @Override
    /** 按知识库、名称关键字和状态分页查询文档，同时标记是否存在人工编辑过的 Chunk。 */
    public IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam) {
        knowledgeAccessService.requireReadableKnowledgeBase(kbId);
        KnowledgeAccessScope accessScope = knowledgeAccessService.currentAccessScope();
        if (!accessScope.unrestricted() && accessScope.readableDocumentIds().isEmpty()) {
            return new Page<KnowledgeDocumentVO>(requestParam.getCurrent(), requestParam.getSize());
        }
        Page<KnowledgeDocumentDO> pageParam = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(requestParam.getKeyword() != null && !requestParam.getKeyword().isBlank(), KnowledgeDocumentDO::getDocName, requestParam.getKeyword())
                .eq(requestParam.getStatus() != null && !requestParam.getStatus().isBlank(), KnowledgeDocumentDO::getStatus, requestParam.getStatus())
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);
        if (!accessScope.unrestricted()) {
            queryWrapper.in(KnowledgeDocumentDO::getId, accessScope.readableDocumentIds());
        }

        IPage<KnowledgeDocumentVO> result = documentMapper.selectPage(pageParam, queryWrapper)
                .convert(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class));

        List<String> docIds = result.getRecords().stream()
                .map(KnowledgeDocumentVO::getId)
                .collect(Collectors.toList());
        // 手工编辑过的 Chunk 需要前端标识，提示再次自动分块可能覆盖人工调整。
        Set<String> editedDocIds = findEditedDocIds(docIds);
        result.getRecords().forEach(vo -> vo.setChunksEdited(editedDocIds.contains(vo.getId())));

        return result;
    }

    /** 通过 update_time 晚于 create_time 一秒的经验规则找出被人工编辑过的文档。 */
    private Set<String> findEditedDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Collections.emptySet();
        }
        QueryWrapper<KnowledgeChunkDO> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT doc_id")
                .in("doc_id", docIds)
                .apply("update_time > create_time + INTERVAL '1 second'");
        return chunkMapper.selectObjs(wrapper).stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }

    @Override
    /** 全局按文档名称模糊搜索，最多 20 条，并批量补齐知识库名称避免 N+1 查询。 */
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        // 限制搜索结果，防止自动补全或 MCP 调用一次返回过多文档。
        int size = Math.min(Math.max(limit, 1), 20);
        KnowledgeAccessScope accessScope = knowledgeAccessService.currentAccessScope();
        if (!accessScope.unrestricted() && accessScope.readableDocumentIds().isEmpty()) {
            return Collections.emptyList();
        }
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);
        if (!accessScope.unrestricted()) {
            qw.in(KnowledgeDocumentDO::getId, accessScope.readableDocumentIds());
        }

        IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        // 收集所有知识库 id 后一次查询并回填名称，避免按记录逐条查库。
        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = knowledgeBaseMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    @Override
    /**
     * 启用或禁用文档，并同步其 Chunk 与向量可见性。
     * 启用需重建已有 Chunk 的 Embedding，耗时模型调用放在数据库事务外；真正状态和向量更新再一起提交。
     */
    public void enable(String docId, boolean enabled) {
        knowledgeAccessService.requireManageDocument(docId);
        // 启停状态要同步到业务 Chunk 和向量库；启用的 Embedding 放事务外，避免长事务。
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        // 如果已经是目标状态，直接返回
        int targetEnabled = enabled ? 1 : 0;
        if (documentDO.getEnabled() != null && documentDO.getEnabled() == targetEnabled) {
            return;
        }

        // 两个分支都需集合名和 Embedding 模型，提前查询一次。
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        // 启用时需为持久化 Chunk 重建向量；模型调用耗时，必须放在事务外。
        List<VectorChunk> vectorChunks = null;
        if (enabled) {
            List<KnowledgeChunkVO> chunks = knowledgeChunkService.listByDocId(docId);
            vectorChunks = chunks.stream().map(each ->
                    VectorChunk.builder()
                            .chunkId(each.getId())
                            .content(each.getContent())
                            .index(each.getChunkIndex())
                            .build()
            ).toList();
            if (CollUtil.isEmpty(vectorChunks)) {
                log.warn("启用文档时未找到任何 Chunk，跳过向量重建，docId={}", docId);
                return;
            }
            chunkEmbeddingService.embed(vectorChunks, kbDO.getEmbeddingModel());
        }

        final List<VectorChunk> finalVectorChunks = vectorChunks;
        transactionOperations.executeWithoutResult(status -> {
            // 数据库启停、调度启停、Chunk 可见性与向量删除/写入保持一致。
            documentDO.setEnabled(targetEnabled);
            documentDO.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(documentDO);
            scheduleService.syncScheduleIfExists(documentDO);
            knowledgeChunkService.updateEnabledByDocId(docId, String.valueOf(kbDO.getId()), enabled);

            if (!enabled) {
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
            } else {
                vectorStoreService.indexDocumentChunks(collectionName, docId, finalVectorChunks);
            }
        });
    }

    @Override
    /** 查询文档全部重建历史，并补齐 Pipeline 名称和未归类耗时。 */
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        knowledgeAccessService.requireReadableDocument(docId);
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        // Pipeline 名称批量加载，日志表只保存 pipelineId。
        Map<String, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<String> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    /**
     * 计算未被显式拆分的耗时。
     * 固定模式扣除提取、切片、Embedding、持久化；Pipeline 模式的内部节点耗时聚合在 chunkDuration，因此只扣除它和持久化。
     */
    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embed = each.getEmbedDuration() == null ? 0 : each.getEmbedDuration();
        long persist = each.getPersistDuration() == null ? 0 : each.getPersistDuration();
        return pipelineMode
                ? totalDuration - chunk - persist
                : totalDuration - extract - chunk - embed - persist;
    }

    /** 从知识库主表获取文档向量所在集合名。 */
    private String resolveCollectionName(String kbId) {
        return knowledgeBaseMapper.selectById(kbId).getCollectionName();
    }

    /** 仅 URL 来源且请求明确开启时才启用定时同步。 */
    private boolean isScheduleEnabled(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        return SourceType.URL == sourceType && Boolean.TRUE.equals(request.getScheduleEnabled());
    }

    /** 校验 URL 来源位置、Cron 格式及最短执行间隔。 */
    private void validateSourceAndSchedule(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        String sourceLocation = StrUtil.trimToNull(request.getSourceLocation());
        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (!isScheduleEnabled(sourceType, request)) {
            return;
        }
        String scheduleCron = StrUtil.trimToNull(request.getScheduleCron());
        if (!StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        try {
            if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new java.util.Date(), scheduleProperties.getMinIntervalSeconds())) {
                throw new ClientException("定时周期不能小于 " + scheduleProperties.getMinIntervalSeconds() + " 秒");
            }
        } catch (IllegalArgumentException e) {
            throw new ClientException("定时表达式不合法");
        }
    }

    /** 将请求处理模式归一为固定分块配置或 Pipeline 配置，并在上传时提前校验引用存在。 */
    private ProcessModeConfig resolveProcessModeConfig(KnowledgeDocumentUploadRequest request) {
        ProcessMode processMode = ProcessMode.normalize(request.getProcessMode());
        if (ProcessMode.CHUNK == processMode) {
            ChunkingMode chunkingMode = ChunkingMode.fromValue(request.getChunkStrategy());
            String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, request.getChunkConfig());
            return new ProcessModeConfig(processMode, chunkingMode, chunkConfig, null);
        } else {
            if (!StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
            return new ProcessModeConfig(processMode, null, null, request.getPipelineId());
        }
    }
    /**
     * 解析文档最终可读取的存储对象。
     * FILE 直接上传到对象存储；URL 由 RemoteFileFetcher 下载后保存，确保后续异步分块不会依赖短期外链。
     */
    private StoredFileDTO resolveStoredFile(String bucketName, SourceType sourceType, String sourceLocation, MultipartFile file) {
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, file);
        }
        // URL 来源先下载再存储，后续重建可稳定读取对象存储中的快照。
        return remoteFileFetcher.fetchAndStore(bucketName, sourceLocation);
    }

    /** 反序列化持久化分块配置，并交给模式生成其专属 Options。 */
    private ChunkingOptions buildChunkingOptions(ChunkingMode mode, KnowledgeDocumentDO documentDO) {
        Map<String, Object> config = parseChunkConfig(documentDO.getChunkConfig());
        return mode.createOptions(config);
    }

    /**
     * 校验分块 JSON 可解析且包含该模式所有默认必填键。
     * 返回原始去空白 JSON，以保持前端配置结构不被无谓改写。
     */
    private String validateAndNormalizeChunkConfig(ChunkingMode mode, String chunkConfigJson) {
        if (!StringUtils.hasText(chunkConfigJson)) {
            return null;
        }
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        String json = chunkConfigJson.trim();
        Map<String, Object> config;
        try {
            config = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("分块参数JSON格式不合法");
        }
        for (String key : mode.getDefaultConfig().keySet()) {
            if (!config.containsKey(key)) {
                throw new ClientException("分块参数缺少必要字段: " + key);
            }
        }
        return json;
    }

    /** 解析历史配置；解析失败时仅降级为空 Map，由策略默认值兜底并记录警告。 */
    private Map<String, Object> parseChunkConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("分块参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    @Override
    /** 仅以 UTF-8 读取 Markdown 原文预览，其他格式应交给解析器转换后查看。 */
    public String preview(String docId) {
        knowledgeAccessService.requireReadableDocument(docId);
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (!"markdown".equalsIgnoreCase(documentDO.getFileType())) {
            throw new ClientException("仅支持预览 markdown 格式文档");
        }
        try (InputStream in = fileStorageService.openStream(documentDO.getFileUrl())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("读取文档内容失败: " + e.getMessage());
        }
    }

    /** 删除对象存储源文件；失败只记录告警，避免已完成的数据库删除被反向回滚。 */
    private void deleteStoredFileQuietly(KnowledgeDocumentDO documentDO) {
        if (documentDO == null || !StringUtils.hasText(documentDO.getFileUrl())) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        } catch (Exception e) {
            log.warn("删除文档存储文件失败, docId={}, fileUrl={}", documentDO.getId(), documentDO.getFileUrl(), e);
        }
    }
}
