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

package com.nageoffer.ai.ragent.infra.http;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import okhttp3.MediaType;

/**
 * OkHttp 请求使用的媒体类型常量。
 * 显式声明 UTF-8，确保中文提示词和检索文本按一致编码发送到模型供应商。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HttpMediaTypes {

    /** 供应商 JSON 请求体的 Content-Type。 */
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
}
