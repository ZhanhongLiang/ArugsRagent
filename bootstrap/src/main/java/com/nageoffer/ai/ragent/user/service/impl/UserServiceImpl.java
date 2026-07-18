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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理端用户管理实现。
 *
 * <p>除 CRUD 外，本类显式保护默认管理员账号，避免演示环境因误操作失去可登录的管理入口。</p>
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 系统初始化管理员账号，不允许被普通管理操作修改或删除。 */
    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;

    /** 按关键词分页查询未删除用户，并将持久化对象转换为前端 VO。 */
    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        // keyword 同时匹配用户名和角色，符合后台用户列表的搜索预期。
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /** 创建普通账号；用户名全局唯一，默认管理员名称被保留。 */
    @Override
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        // 空角色默认赋予 USER，其他值必须归一到枚举允许的代码。
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        return String.valueOf(record.getId());
    }

    /** 按“请求中出现的字段才修改”的语义更新用户，并禁止改动默认管理员。 */
    @Override
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);

        // null 表示前端未请求修改该字段，空字符串则会被校验为非法输入。
        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        userMapper.updateById(record);
    }

    /** 逻辑删除指定用户；默认管理员始终不可删除。 */
    @Override
    public void delete(String id) {
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        userMapper.deleteById(record.getId());
    }

    /** 校验当前登录用户的旧密码后更新新密码。 */
    @Override
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        // 从 TTL 用户上下文读取当前操作者，不能信任客户端传来的用户 id。
        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
    }

    /** 加载未删除用户，不存在时统一抛出客户端可理解的业务异常。 */
    private UserDO loadById(String id) {
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    /** 防止默认管理员被修改或删除，从而保持系统始终存在一个可用管理入口。 */
    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    /** 校验用户名唯一性；更新场景通过 excludeId 排除当前记录自身。 */
    private void ensureUsernameAvailable(String username, String excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    /** 将角色输入归一为系统允许的 ADMIN/USER 代码，空值默认 USER。 */
    private String normalizeRole(String role) {
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    /** 当前兼容既有明文存储字段的比较逻辑；后续切换哈希时应集中替换此处。 */
    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    /** 屏蔽密码等敏感持久化字段，只向管理端返回可展示的用户信息。 */
    private UserVO toVO(UserDO record) {
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
