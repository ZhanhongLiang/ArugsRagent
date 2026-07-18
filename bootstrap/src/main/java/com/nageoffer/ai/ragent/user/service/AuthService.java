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

package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;

/**
 * 用户认证业务端口。
 * 控制器只调用此接口，Sa-Token 登录状态与数据库校验细节由实现类封装。
 */
public interface AuthService {

    /** @return 登录成功后供前端保存的 token、角色和头像信息。 */
    LoginVO login(LoginRequest requestParam);

    /** 注销当前请求上下文关联的 Sa-Token 会话。 */
    void logout();
}
