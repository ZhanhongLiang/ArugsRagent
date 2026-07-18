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

package com.nageoffer.ai.ragent.ingestion.domain.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个摄取节点向执行引擎返回的控制结果。
 * success 表示节点是否正常完成，shouldContinue 独立表达成功后是否需要停止整条流水线。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeResult {

    /** false 时引擎将任务置为 FAILED 并停止后续节点。 */
    private boolean success;

    /** success=true 时仍可为 false，用于业务性提前终止。 */
    private boolean shouldContinue;

    /** 面向任务日志和排查的简短执行说明。 */
    private String message;

    /** 失败根因；引擎会将其消息和堆栈记录到任务日志。 */
    private Throwable error;

    /** @return 无说明的成功结果，执行链继续。 */
    public static NodeResult ok() {
        return NodeResult.builder().success(true).shouldContinue(true).build();
    }

    /**
     * 创建带日志说明的成功结果。
     *
     * @param message 结果消息
     * @return 表示执行成功且应继续执行的结果对象
     */
    public static NodeResult ok(String message) {
        return NodeResult.builder().success(true).shouldContinue(true).message(message).build();
    }

    /**
     * 创建“正常跳过”结果；跳过不是失败，执行链仍继续。
     *
     * @param reason 跳过原因
     * @return 表示节点被跳过但应继续执行的结果对象
     */
    public static NodeResult skip(String reason) {
        return NodeResult.builder().success(true).shouldContinue(true).message("Skipped: " + reason).build();
    }

    /**
     * 创建失败结果；引擎收到后会结束本次摄取任务。
     *
     * @param error 异常信息
     * @return 表示执行失败且不应继续执行的结果对象
     */
    public static NodeResult fail(Throwable error) {
        return NodeResult.builder()
                .success(false)
                .shouldContinue(false)
                .error(error)
                .message(error == null ? null : error.getMessage())
                .build();
    }

    /**
     * 创建成功但终止后续节点的结果，例如条件已满足无需再处理。
     *
     * @param reason 终止原因
     * @return 表示执行成功但应终止管道执行的结果对象
     */
    public static NodeResult terminate(String reason) {
        return NodeResult.builder().success(true).shouldContinue(false).message(reason).build();
    }
}
