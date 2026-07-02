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

package com.nageoffer.ai.ragent.rag.dto;

/**
 * SSE message 事件载荷。
 *
 * <p>模型输出会被拆成多个 message 事件推给前端。type 用来区分正式回答 response
 * 和深度思考 think，delta 是本次增量片段，不代表完整答案。</p>
 */
public record MessageDelta(String type, String delta) {

    /**
     * 消息类型
     */
    public String type() {
        return type;
    }

    /**
     * 增量数据
     */
    public String delta() {
        return delta;
    }
}
