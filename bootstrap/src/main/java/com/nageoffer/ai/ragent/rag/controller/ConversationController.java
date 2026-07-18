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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.rag.controller.request.ConversationUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationVO;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前登录用户的会话 REST 入口。
 *
 * <p>控制器不接收 userId 参数，而是统一从 {@code UserContext} 取得身份，
 * 防止客户端通过替换用户 ID 访问他人的会话或消息。</p>
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    /**
     * 获取会话列表
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> listConversations() {
        // 服务层按用户 ID 和逻辑删除标记过滤，再按最近活跃时间排序。
        return Results.success(conversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 重命名会话
     */
    @PutMapping("/conversations/{conversationId}")
    public Result<Void> rename(@PathVariable String conversationId,
                               @RequestBody ConversationUpdateRequest request) {
        // 会话归属和标题长度由服务层校验，控制器仅负责 HTTP 参数绑定。
        conversationService.rename(conversationId, request);
        return Results.success();
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        // 服务层在事务内级联清理消息和摘要，避免本层拆分多个删除接口。
        conversationService.delete(conversationId);
        return Results.success();
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(@PathVariable String conversationId) {
        // 普通会话页按自然时间顺序展示全部消息；分页场景可由后续接口扩展参数。
        return Results.success(conversationMessageService.listMessages(conversationId, UserContext.getUserId(), null, ConversationMessageOrder.ASC));
    }
}
