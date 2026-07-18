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

import com.nageoffer.ai.ragent.rag.controller.request.ConversationUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationVO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationCreateBO;

import java.util.List;

/**
 * 会话主记录的应用服务契约，定义列表、创建/续期、重命名和级联删除操作。
 */
public interface ConversationService {

    /**
     * 根据用户ID获取会话列表
     *
     * @param userId 用户ID
     * @return 会话视图对象列表
     */
    List<ConversationVO> listByUserId(String userId);

    /**
     * 以用户和会话 ID 为范围创建或续期会话；存在时仅刷新最后活跃时间。
     *
     * @param request 创建请求对象
     */
    void createOrUpdate(ConversationCreateBO request);

    /**
     * 重命名会话
     *
     * @param conversationId 会话 ID
     * @param request        更新请求对象
     */
    void rename(String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话及其关联的消息、摘要；实现必须保证操作原子性。
     *
     * @param conversationId 会话 ID
     */
    void delete(String conversationId);
}
