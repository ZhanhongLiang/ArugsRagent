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

/**
 * 模型供应商调用失败的标准分类。
 *
 * <p>统一分类使日志、熔断、监控告警和将来可选的重试策略不必依赖各供应商不同的错误正文。</p>
 */
public enum ModelClientErrorType {

    /** 鉴权失败，通常是 Key 缺失、过期或无权限。 */
    UNAUTHORIZED,

    /** 请求频率或配额超过供应商限制。 */
    RATE_LIMITED,

    /** 供应商服务端 5xx 或等价内部错误。 */
    SERVER_ERROR,

    /** 本地请求参数、模型名或协议格式错误。 */
    CLIENT_ERROR,

    /** DNS、连接、读写超时等网络层错误。 */
    NETWORK_ERROR,

    /** HTTP 成功但响应 JSON 缺字段、非 JSON 或结构不符合协议。 */
    INVALID_RESPONSE,

    /** HTTP 成功但供应商业务字段明确返回错误。 */
    PROVIDER_ERROR;

    /**
     * 根据 HTTP 状态码推断错误类别。
     *
     * @param status HTTP 状态码
     * @return 对应的错误类型
     */
    public static ModelClientErrorType fromHttpStatus(int status) {
        // 认证、限流和服务端错误有明确策略语义，其余 4xx 统一归为本地请求问题。
        if (status == 401 || status == 403) {
            return UNAUTHORIZED;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return CLIENT_ERROR;
    }
}
