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

import java.util.Optional;

/**
 * 服务端内部处理失败时抛出的领域异常。
 * 它与 ClientException 区分开，便于监控和调用方判断是否值得重试。
 */
public class ServiceException extends AbstractException {

    /** 使用默认服务端错误码创建异常。 */
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    /** 使用错误码默认消息创建异常。 */
    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    /** 使用调用点消息覆盖指定错误码默认文案。 */
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /** 将空消息规范化为错误码文案，并保留底层原因。 */
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    /** 输出紧凑的错误码与错误消息，便于服务端日志检索。 */
    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
