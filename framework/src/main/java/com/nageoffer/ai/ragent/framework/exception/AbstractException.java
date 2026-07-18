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

import com.nageoffer.ai.ragent.framework.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 项目领域异常的公共基类，统一承载机器可识别的错误码和面向调用方的错误消息。
 *
 * <p>客户端异常、服务端异常与远程调用异常只是在错误码分类上不同，统一继承此类后，
 * 全局异常处理器可以用一套规则生成 Result 响应。</p>
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    /** 稳定错误码，前端或调用方可据此进行机器判断。 */
    public final String errorCode;

    /** 优先使用调用点提供的消息；为空时回退到错误码预置文案。 */
    public final String errorMessage;

    /**
     * 创建领域异常并完成“自定义消息优先、错误码默认消息兜底”的规范化。
     *
     * @param message 调用点提供的业务说明，可为空
     * @param throwable 原始异常，保留给日志链路排查
     * @param errorCode 异常分类与默认提示的来源
     */
    public AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(message) ? message : null).orElse(errorCode.message());
    }
}
