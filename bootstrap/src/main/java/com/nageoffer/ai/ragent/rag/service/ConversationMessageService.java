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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;

import java.util.List;

/**
 * 会话消息及记忆摘要的读写契约。
 */
public interface ConversationMessageService {

    /**
     * 新增一条已完成持久化的对话消息，并返回其数据库主键。
     *
     * @param conversationMessage 消息内容
     */
    String addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取当前用户有权访问的消息列表；倒序取最近数据时实现仍应返回自然时间顺序。
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param limit          限制数量
     * @param order          排序方式
     * @return 对话消息列表
     */
    List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要，供长会话记忆压缩后代替早期原文使用。
     *
     * @param conversationSummary 对话摘要内容
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);
}
