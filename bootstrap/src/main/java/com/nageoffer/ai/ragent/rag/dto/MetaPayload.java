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
 * SSE meta 事件载荷。
 *
 * <p>流式对话建立后后端会先推送 meta，前端拿到 conversationId 用于后续会话归属，
 * 拿到 taskId 用于用户点击“停止生成”时调用停止接口。</p>
 *
 * @param conversationId 当前会话 id
 * @param taskId         当前流式生成任务 id
 */
public record MetaPayload(String conversationId, String taskId) {
}
