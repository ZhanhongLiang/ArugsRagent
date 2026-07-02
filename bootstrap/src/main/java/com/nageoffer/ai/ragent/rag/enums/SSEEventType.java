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

package com.nageoffer.ai.ragent.rag.enums;

import lombok.RequiredArgsConstructor;

/**
 * SSE 事件类型枚举。
 *
 * <p>事件顺序约定：</p>
 * <ul>
 *     <li>正常生成：meta -> message... -> finish -> done。</li>
 *     <li>用户停止：meta -> message... -> cancel -> done。</li>
 *     <li>排队超时/拒绝：meta -> reject -> finish -> done。</li>
 * </ul>
 */
@RequiredArgsConstructor
public enum SSEEventType {

    /**
     * 会话与任务的元信息事件，前端拿 taskId 后才能发起停止生成。
     */
    META("meta"),

    /**
     * 增量消息事件，承载 response 或 think 类型的 token 片段。
     */
    MESSAGE("message"),

    /**
     * 模型回复完成事件，携带最终 messageId 和可选标题。
     */
    FINISH("finish"),

    /**
     * 连接关闭前的最终哨兵事件，前端收到后可以关闭 EventSource。
     */
    DONE("done"),

    /**
     * 用户停止生成事件，携带已保存的部分回复 messageId。
     */
    CANCEL("cancel"),

    /**
     * 排队等待超时或系统繁忙时的拒绝事件。
     */
    REJECT("reject");

    private final String value;

    /**
     * SSE 事件名称（与前端约定一致）
     */
    public String value() {
        return value;
    }
}
