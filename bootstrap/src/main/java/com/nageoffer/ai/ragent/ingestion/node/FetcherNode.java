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

package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.strategy.fetcher.DocumentFetcher;
import com.nageoffer.ai.ragent.ingestion.strategy.fetcher.FetchResult;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 摄取流水线的原始文档获取节点。
 *
 * <p>它将不同来源统一为 {@code rawBytes + mimeType + fileName} 写回 IngestionContext。策略模式将来源协议隔离在
 * DocumentFetcher 实现中；节点本身只负责路由、幂等跳过和上下文字段更新。</p>
 */
@Component
public class FetcherNode implements IngestionNode {

    /** SourceType 到抓取策略的只读索引，应用启动时由 Spring 注入的所有实现构建。 */
    private final Map<SourceType, DocumentFetcher> fetchers;

    /**
     * 收集所有 DocumentFetcher Bean 并按支持的来源类型建立路由表。
     * 新增来源只需新增实现；若两个实现声明同一类型，toMap 会在启动时暴露配置错误。
     */
    public FetcherNode(List<DocumentFetcher> fetchers) {
        this.fetchers = fetchers.stream()
                .collect(Collectors.toMap(DocumentFetcher::supportedType, Function.identity()));
    }

    @Override
    /** @return 管道中与数据库配置对应的 fetcher 节点类型字符串。 */
    public String getNodeType() {
        return IngestionNodeType.FETCHER.getValue();
    }

    @Override
    /**
     * 获取原始字节并更新上下文。
     * 已由上传入口预置 rawBytes 时只补 MIME 类型，不再次访问外部来源，保证重试和上传链路不会重复 I/O。
     */
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 上传文件等入口已将字节放入上下文，直接复用并确保后续 ParserNode 有 MIME 类型可判断。
        if (context.getRawBytes() != null && context.getRawBytes().length > 0) {
            if (!StringUtils.hasText(context.getMimeType())) {
                String fileName = context.getSource() == null ? null : context.getSource().getFileName();
                context.setMimeType(MimeTypeDetector.detect(context.getRawBytes(), fileName));
            }
            return NodeResult.ok("已跳过获取器：原始字节已存在");
        }

        // 非预置字节场景必须有来源类型和位置，才能选择具体抓取策略。
        DocumentSource source = context.getSource();
        if (source == null || source.getType() == null) {
            return NodeResult.fail(new ClientException("文档来源不能为空"));
        }

        // 由 SourceType 找策略，避免在节点内堆积 if/else 协议分支。
        DocumentFetcher fetcher = fetchers.get(source.getType());
        if (fetcher == null) {
            return NodeResult.fail(new ClientException("不支持的来源类型: " + source.getType()));
        }

        // 策略返回统一结果；节点负责写回共享上下文，供 Parser/Chunker/Indexer 连续消费。
        FetchResult result = fetcher.fetch(source);
        context.setRawBytes(result.content());
        if (StringUtils.hasText(result.mimeType())) {
            context.setMimeType(result.mimeType());
        }
        // 抓取器可能从响应头或 URI 推断文件名，写回 Source 供后续日志与文档记录复用。
        if (StringUtils.hasText(result.fileName())) {
            source.setFileName(result.fileName());
        }
        return NodeResult.ok("已获取 " + (result.content() == null ? 0 : result.content().length) + " 字节");
    }
}
