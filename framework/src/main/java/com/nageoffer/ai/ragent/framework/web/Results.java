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

package com.nageoffer.ai.ragent.framework.web;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.AbstractException;

import java.util.Optional;

/**
 * 构建全局返回对象构造器，方便开发者构建全局返回对象
 */
public final class Results {

    /**
     * 构造成功响应
     */
    public static Result<Void> success() {
        // 无业务数据的成功响应，例如删除、更新等只返回状态码的接口。
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE);
    }

    /**
     * 构造带返回数据的成功响应
     */
    public static <T> Result<T> success(T data) {
        // 携带业务数据的成功响应；泛型使 Controller 无需手写强制转换。
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setData(data);
    }

    /**
     * 构建服务端失败响应
     */
    public static Result<Void> failure() {
        // 未被精细分类的异常统一收敛为服务端错误，避免泄露内部堆栈给前端。
        return new Result<Void>()
                .setCode(BaseErrorCode.SERVICE_ERROR.code())
                .setMessage(BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 通过 {@link AbstractException} 构建失败响应
     */
    static Result<Void> failure(AbstractException abstractException) {
        // 业务异常可携带自定义错误码和消息；为空时回退为通用服务异常。
        String errorCode = Optional.ofNullable(abstractException.getErrorCode())
                .orElse(BaseErrorCode.SERVICE_ERROR.code());
        String errorMessage = Optional.ofNullable(abstractException.getErrorMessage())
                .orElse(BaseErrorCode.SERVICE_ERROR.message());
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 通过 errorCode、errorMessage 构建失败响应
     */
    static Result<Void> failure(String errorCode, String errorMessage) {
        // 参数校验、权限等场景可直接传入已确定的错误码和可展示消息。
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }
}
