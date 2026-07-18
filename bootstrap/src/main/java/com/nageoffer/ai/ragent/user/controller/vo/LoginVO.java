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

package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录成功响应，前端据此保存会话令牌并初始化当前用户界面。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /** Sa-Token 登录主体 id。 */
    private String userId;

    /** 当前用户角色代码。 */
    private String role;

    /** Sa-Token 生成的访问令牌。 */
    private String token;

    /** 头像展示地址。 */
    private String avatar;
}
