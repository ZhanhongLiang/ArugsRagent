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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;

/**
 * 文档来源抓取策略端口。
 *
 * <p>FetcherNode 只根据 SourceType 查找实现，不依赖 HTTP、S3、飞书等具体协议；新增来源时新增实现即可，
 * 不需要改动节点编排逻辑。</p>
 */
public interface DocumentFetcher {

    /** @return 当前策略可处理的来源类型，也是 FetcherNode 策略注册表的键。 */
    SourceType supportedType();

    /**
     * 从来源读取原始字节并补齐 MIME 类型、文件名等后续解析所需元数据。
     *
     * @param source 文档数据源
     * @return 原始内容和元数据快照；异常由调用方转换为任务节点失败
     */
    FetchResult fetch(DocumentSource source);
}
