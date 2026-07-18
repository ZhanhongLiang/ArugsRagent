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

package com.nageoffer.ai.ragent.framework.exception;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.errorcode.IErrorCode;

/**
 * 调用方输入、状态或权限不满足业务要求时抛出的异常。
 * 此类异常通常应返回给用户可修正的提示，而不是暴露服务器堆栈。
 */
public class ClientException extends AbstractException {

    /** 使用错误码默认提示创建客户端异常。 */
    public ClientException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    /** 使用默认客户端错误码和自定义业务提示创建异常。 */
    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    /** 使用指定错误码和自定义提示创建异常。 */
    public ClientException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /** 保留根因，供日志查看，同时向上层暴露统一错误码。 */
    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    /** 避免 RuntimeException 默认格式，便于日志快速阅读错误码和错误消息。 */
    @Override
    public String toString() {
        return "ClientException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
