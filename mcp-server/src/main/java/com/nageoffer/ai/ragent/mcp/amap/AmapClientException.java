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

package com.nageoffer.ai.ragent.mcp.amap;

/**
 * 高德 Web 服务调用过程中的可预期业务异常。
 *
 * <p>工具层捕获该异常后会转换为 {@code isError=true} 的 MCP 结果，而不会把 HTTP、JSON 或配置细节直接抛到调用端。</p>
 */
public class AmapClientException extends RuntimeException {

    /** 使用可展示给 MCP 调用方的错误原因创建异常。 */
    public AmapClientException(String message) {
        super(message);
    }

    /** 保留底层异常原因，便于日志排查网络或 JSON 解析问题。 */
    public AmapClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
