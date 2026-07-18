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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档来源描述，是“如何取到原文件”的数据，而不是解析后的文档内容。
 *
 * <p>它可以被持久化到任务中，在定时同步时再次交给 FetcherNode；credentials 不应写入公开日志或前端响应。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSource {

    /** 来源协议类型，决定 FetcherNode 选择哪个 DocumentFetcher。 */
    private SourceType type;

    /** 来源位置，例如 s3:// URI、HTTP URL、飞书文档链接或兼容的本地路径。 */
    private String location;

    /** 可选展示文件名；缺失时抓取器会尝试从响应头、URI 或对象键推断。 */
    private String fileName;

    /** 访问来源所需的临时凭证；仅供对应 Fetcher 使用，不得进入日志或模型上下文。 */
    private Map<String, String> credentials;
}
