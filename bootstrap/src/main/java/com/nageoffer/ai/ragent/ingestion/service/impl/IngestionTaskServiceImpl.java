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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可配置摄取任务的同步执行与审计持久化实现。
 *
 * <p>它是 Controller 与 IngestionEngine 之间的编排层：创建任务主记录、构造 IngestionContext、调用引擎，
 * 再把节点日志、最终状态和元数据分别落入任务主表和节点明细表。</p>
 */
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    /** 按 PipelineDefinition 执行节点链的核心引擎。 */
    private final IngestionEngine engine;
    /** 加载并校验流水线定义的服务。 */
    private final IngestionPipelineService pipelineService;
    /** 摄取任务主表持久化入口。 */
    private final IngestionTaskMapper taskMapper;
    /** 任务逐节点明细表持久化入口。 */
    private final IngestionTaskNodeMapper taskNodeMapper;
    /** 序列化任务日志摘要、扩展元数据和节点输出的 JSON 工具。 */
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    /**
     * JSON 来源的同步摄取入口。
     * 请求已描述 URL、S3、飞书等来源时不携带字节，由流水线中的 Fetcher 节点按 DocumentSource 拉取。
     */
    @Override
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        /*
         * 普通采集入口：
         * 这个入口用于调用方已经用 JSON 描述好了文档来源的场景，例如 URL、S3、本地路径等。
         * 这里不会携带上传文件的字节数组，所以后面的流水线通常要靠 Fetcher 节点根据
         * source.location 再去拉取真实文档内容。
         */
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        // 将接口层 DTO 转成采集引擎内部使用的 DocumentSource。
        // 从这里开始，流水线节点只认识 DocumentSource，不再依赖 Controller 的请求对象。
        DocumentSource source = toSource(request.getSource());
        // 普通 JSON 采集不是 multipart 上传，所以 rawBytes/mimeType 传 null。
        // vectorSpaceId 继续向下透传，最终由 Indexer 节点决定写入哪个向量空间。
        return executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * Multipart 文件摄取入口。
     * 上传字节直接写入上下文，因此 FetcherNode 会识别到 rawBytes 已存在并跳过重复读取。
     */
    @Override
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        /*
         * 文件上传采集入口：
         * 这个入口用于浏览器/API 直接上传文件的场景。服务层会先读取文件字节、识别 MIME 类型，
         * 再把上传文件包装成 FILE 类型的 DocumentSource。之后仍然复用 executeInternal，
         * 这样上传采集和普通采集可以共用同一套任务表、流水线引擎、节点日志和结果回写逻辑。
         */
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            // 读取上传文件内容，作为 rawBytes 直接交给后续 Fetcher/Parser 节点使用。
            // 文件大小限制应该在 multipart 配置或上传限流 Filter 阶段提前完成。
            byte[] bytes = file.getBytes();
            // 保留原始文件名：一方面用于任务列表展示，另一方面可作为 MIME 识别和解析器选择的辅助信息。
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                // 如果客户端没有传文件名，给一个稳定兜底名，避免后续日志和 MIME 检测缺少名称。
                fileName = "upload.bin";
            }
            // MIME 类型同时参考文件字节和文件名，比直接相信 MultipartFile#getContentType 更可靠；
            // 因为 getContentType 可能来自客户端声明，存在不准确或伪造的可能。
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            // 直接上传没有远程 URL，source.location 记录上传文件名即可；
            // 真正的文件内容通过 rawBytes 参数继续向 executeInternal 传递。
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            // 当前上传接口没有显式传 vectorSpaceId，因此这里传 null。
            // 后续由流水线节点配置或默认向量空间配置决定最终写入位置。
            //
            return executeInternal(pipelineId, source, bytes, mimeType, null);
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    /** 根据任务主键读取任务摘要；不存在时抛出客户端可理解的异常。 */
    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    /** 按创建时间倒序分页查询任务，可用状态过滤缩小排查范围。 */
    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        // 兼容前端传枚举名或历史状态字符串，先规范化后再查询数据库。
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /** 按配置执行顺序返回任务节点明细，前端可用于渲染摄取调试链路。 */
    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * 两个入口共用的任务执行内核。
     * rawBytes 为空时依赖 Fetcher；有值时直接进入 Parser；最后无论成功失败均持久化可查询任务记录。
     */
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        /*
         * 采集任务核心执行方法：
         * execute() 和 upload() 最终都会进入这里。它是一次采集任务的事务边界，
         * 完整流程是：解析流水线 -> 创建任务主表记录 -> 构建上下文 -> 执行节点链路
         * -> 保存节点日志 -> 回写任务结果 -> 返回接口结果。
         */
        // pipelineId 为空时会解析为系统默认流水线 ID。
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        // 先加载流水线定义，避免 pipelineId 无效时已经创建了任务记录。
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        // 先创建任务主表记录，让后续每个节点执行日志都能关联到稳定的 taskId。
        // 这样任务详情页和排查问题时都可以按 taskId 追踪完整采集过程。
        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                // 这里只保存来源元数据，方便任务列表展示；上传文件字节不会放到任务主表里。
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                // 引擎执行前先标记 RUNNING；执行结束后会根据 context 再回写 COMPLETED/FAILED。
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                // 记录当前操作人，方便后台审计和问题追踪。
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        // IngestionContext 是一次采集任务的内存“黑板”。
        // Fetcher、Parser、Chunker、Enhancer、Indexer 等节点都会从这里读写中间结果。
        IngestionContext context = IngestionContext.builder()
                // MyBatis 插入后会回填 task.id；上下文和返回结果使用 String 类型，所以这里转成字符串。
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                // rawBytes 只在 multipart 上传路径存在。
                // URL/S3 等普通来源这里为 null，需要 Fetcher 节点根据 DocumentSource 拉取内容。
                .rawBytes(rawBytes)
                // mimeType 主要作为解析器选择提示。
                // 上传路径会提前识别，远程来源也可以在 Fetcher/Parser 节点中设置或覆盖。
                .mimeType(mimeType)
                // 可选的目标向量空间 ID。真正消费它并写入向量库的是 Indexer 节点。
                .vectorSpaceId(vectorSpaceId)
                // 节点执行日志先累积在内存里，引擎跑完后统一落库。
                .logs(new ArrayList<>())
                .build();
        // 执行配置好的链式流水线。引擎会不断修改 context，
        // 例如写入解析文本、分块结果、元数据、最终状态和异常信息。
        IngestionContext result = engine.execute(pipeline, context);
        // 节点日志单独保存到 task_node 表，方便前端展示逐节点 Debug 链路：
        // fetcher -> parser -> chunker -> enhancer -> indexer。
        saveNodeLogs(task, pipeline, result.getLogs());
        // 将最终上下文状态回写到任务主表：状态、Chunk 数量、错误信息、日志摘要和元数据。
        updateTaskFromContext(task, result);
        // 接口只返回简要结果；完整节点明细可以再通过 taskId 查询。
        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    /** 将引擎最终上下文压缩后回写任务主表，避免主表存储节点大输出。 */
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        // 主表只留摘要日志，完整节点输出保存在 task_node 表，减少重复大字段。
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 将内存 NodeLog 逐条落库。
     * 每条输出会做大小截断，节点执行顺序由管道连线推导而非依赖列表物理顺序。
     */
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            // 引擎只有 success 布尔值，任务节点表额外区分 skipped，便于前端展示条件分支。
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    /**
     * 根据 nextNodeId 连线推导节点展示顺序。
     * 优先沿所有起始节点遍历，剩余未访问节点作为容错处理，保证异常配置下日志仍有稳定顺序。
     */
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        // 被其它节点引用的节点不是入口；未被引用节点是链式遍历起点。
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    /** 将引擎日志转换为任务节点表的 success/failed/skipped 状态字符串。 */
    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    /** 汇总可保存的扩展元数据、关键词与示例问题，避免序列化整个 IngestionContext。 */
    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    /** 当前接口要求显式指定 pipelineId，避免隐式默认管道导致结果不可预测。 */
    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    /** 将外部状态输入规范成 IngestionStatus 值；无法识别时保留原值以兼容历史数据。 */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    /** 将 RAG Controller 共用的 DocumentSourceRequest 转为摄取内部领域对象。 */
    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    /** 将任务持久化对象转为前端视图，并反序列化日志摘要。 */
    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(BeanUtil.beanToMap(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    /** 将节点持久化对象转为前端视图，并规范类型与状态展示值。 */
    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(BeanUtil.beanToMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    /** 尝试序列化 JSON；审计字段序列化失败不应覆盖已经完成的主业务结果。 */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 复制轻量日志摘要并主动清空 output。
     * 完整输出可能包含大文本、Chunk 列表或向量，只应保存在节点明细表且受截断限制。
     */
    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    /** 从任务主表日志 JSON 反序列化摘要；损坏历史 JSON 安全降级为空列表。 */
    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 兼容来源类型的历史写法并转换为当前枚举值。 */
    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    /** 兼容节点类型的历史写法并转换为当前枚举值。 */
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    /** 将节点状态统一为小写下划线形式，兼容历史的连字符分隔值。 */
    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的节点输出 JSON，避免任务详情日志超过数据库字段或传输限制。
     * 限制以字符数近似 1 MB，尾部附加截断说明，保留排查时最有价值的开头内容。
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
