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

/**
 * 抓取阶段与解析阶段之间的标准数据契约。
 *
 * @param content 抓取到的原始内容字节，后续 ParserNode 以此解析文本
 * @param mimeType 经响应头或内容探测得到的 MIME 类型
 * @param fileName 用于 MIME 推断、日志展示和文档命名的文件名
 */
public record FetchResult(byte[] content, String mimeType, String fileName) {
}
