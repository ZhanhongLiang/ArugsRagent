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

package com.nageoffer.ai.ragent.framework.idempotent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 防止用户重复提交表单信息切面控制器
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    /** Redisson 提供跨实例可见的分布式锁，防止同一请求落到不同节点仍被重复执行。 */
    private final RedissonClient redissonClient;
    /** 将方法入参稳定序列化后计算摘要，用于构造默认幂等键。 */
    private final Gson gson = new Gson();

    @Value("${app.eval.enabled:false}")
    private boolean evalEnabled;

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        if (evalEnabled) {
            // 评测会对同一问题多次调用，跳过 Web 幂等锁避免干扰批量测试。
            return joinPoint.proceed();
        }
        // 读取目标方法声明的幂等策略，包括自定义 key 和重复提示语。
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 由显式 SpEL key 或“接口路径 + 用户 + 入参摘要”构造锁标识。
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        RLock lock = redissonClient.getLock(lockKey);
        // tryLock 不等待：同一语义请求正在处理就立即拒绝，避免重复写库或重复发消息。
        if (!lock.tryLock()) {
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 拿到锁后执行原业务逻辑。
            result = joinPoint.proceed();
        } finally {
            // 无论成功或抛异常都释放锁，避免用户后续请求被永久阻塞。
            lock.unlock();
        }
        return result;
    }

    /**
     * @return 返回自定义防重复提交注解
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 切面签名可能来自接口，需在目标实现类上重新定位方法以读取实际注解。
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * @return 获取当前线程上下文 ServletPath
     */
    private String getServletPath() {
        // 默认幂等键包含请求路径，防止不同接口但入参相同互相误伤。
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * @return 当前操作用户 ID
     */
    private String getCurrentUserId() {
        // 将用户 ID 纳入键空间，不同用户的相同操作不应互相抢锁。
        return UserContext.getUserId();
    }

    /**
     * @return joinPoint md5
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        // 入参 JSON 取 MD5，既稳定区分请求又避免把大对象直接写进 Redis key。
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            // 优先尊重业务显式声明的 SpEL key，例如按会话、订单或用户维度去重。
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        // 未配置 key 时退化为请求路径、当前用户和全部入参的组合键。
        return String.format(
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}
