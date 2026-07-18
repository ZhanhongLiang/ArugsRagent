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

import lombok.Getter;

/**
 * 模型客户端的结构化异常。
 *
 * <p>除了消息外还携带错误类别与 HTTP 状态码，让 {@code ModelRoutingExecutor} 能记录失败、触发熔断，
 * 并判断是否继续尝试备用模型。</p>
 */
@Getter
public class ModelClientException extends RuntimeException {

    /** 错误类别，例如鉴权、限流、网络或非法响应。 */
    private final ModelClientErrorType errorType;

    /** 若失败来自 HTTP 层则记录状态码；网络异常等场景为 null。 */
    private final Integer statusCode;

    /**
     * 构造带原始原因的模型客户端异常。
     *
     * @param message    异常消息
     * @param errorType  错误类型
     * @param statusCode HTTP状态码
     * @param cause      原始异常
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * 构造没有下层 cause 的协议或 HTTP 异常。
     *
     * @param message    异常消息
     * @param errorType  错误类型
     * @param statusCode HTTP状态码
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode) {
        super(message);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }
}
