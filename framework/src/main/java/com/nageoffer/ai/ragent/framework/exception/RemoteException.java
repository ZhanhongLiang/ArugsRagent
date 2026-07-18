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
 * 调用模型、MCP、对象存储等外部依赖失败时使用的领域异常。
 * 上层可将其与本地服务异常区分，决定告警、降级或重试策略。
 */
public class RemoteException extends AbstractException {

    /** 使用默认远程调用错误码创建异常。 */
    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    /** 使用指定错误码标记不同类型的远程失败。 */
    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /** 保留供应商或网络层原始异常，方便排查调用失败根因。 */
    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    /** 输出紧凑的远程错误信息，避免日志中重复堆栈前缀。 */
    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
