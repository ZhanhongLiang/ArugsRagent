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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 基于数据库用户表和 Sa-Token 的认证实现。
 *
 * <p>登录成功后只把用户 id 写入 Sa-Token；每个请求再由鉴权层加载用户上下文，
 * 避免把完整用户对象放入令牌造成过期数据与泄露风险。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 用户没有配置头像时返回的展示默认值。 */
    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    /** 用户持久化入口，用于登录时查询未逻辑删除的账号。 */
    private final UserMapper userMapper;

    @Override
    /** 校验用户名密码后创建 Sa-Token 会话，并返回前端建立登录态所需字段。 */
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        // 空凭证在查库前拒绝，避免无意义数据库请求和含糊错误。
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        // 用户不存在和密码不匹配使用同一提示，避免账号枚举。
        if (user == null || !passwordMatches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        // Sa-Token 以用户主键作为登录 id，业务侧保持字符串形式便于上下文透传。
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    @Override
    /** 清除当前请求对应的 Sa-Token 会话；没有登录态时由 Sa-Token 自行按幂等语义处理。 */
    public void logout() {
        StpUtil.logout();
    }

    /** 只查询未逻辑删除用户，空用户名直接返回 null 供登录入口统一处理。 */
    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }

    /**
     * 当前项目按既有数据库字段做直接比对。
     * 这是认证边界，生产环境应将存储值替换为强哈希并使用相应校验器，调用方无需改变。
     */
    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }
}
