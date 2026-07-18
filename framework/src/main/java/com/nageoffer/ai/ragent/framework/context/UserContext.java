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

package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 用户上下文容器（基于 TTL 传递当前线程的登录用户）
 */
public final class UserContext {

    /**
     * 保存当前请求登录用户的 TTL 容器。
     *
     * <p>与普通 ThreadLocal 相比，在线程池提交任务时会复制父线程上下文，
     * 使意图识别、检索、MCP 调用等异步任务仍能读取同一个用户身份。</p>
     */
    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        // 将鉴权过滤器解析出的用户放入当前请求线程，供后续业务层读取。
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        // 不强制登录的接口使用该方法；未登录时直接返回 null。
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        // 先读取一次，避免后续多次访问 ThreadLocal。
        LoginUser user = CONTEXT.get();
        if (user == null) {
            // 需要身份的业务不允许以匿名状态继续执行。
            throw new ClientException("未获取到当前登录用户");
        }
        // 身份存在时返回完整用户对象，而不仅是用户 ID。
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        // 允许匿名调用方安全读取，避免在公共链路中因空上下文抛出空指针。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        // 用户名主要用于展示、审计日志等非主键场景。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        // 角色由认证链路写入，用于后续权限或操作审计。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 获取当前头像（未登录返回 null）
     */
    public static String getAvatar() {
        // 头像是展示字段，未登录时保持 null。
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getAvatar();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        // 请求结束必须 remove，防止容器线程复用时泄露上一个用户的身份。
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        // 只判断上下文是否存在，不触发 requireUser 的异常分支。
        return CONTEXT.get() != null;
    }
}
