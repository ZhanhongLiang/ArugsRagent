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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 防止消息队列消费者重复消费消息切面控制器
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {

    /** 使用 Redis 记录消息处理状态，使多个消费者实例共享同一幂等判断。 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 原子设置消费中状态的 Lua 脚本。
     *
     * <p>SET NX 保证只有第一个消费者能抢到处理权，GET 返回旧值让调用方区分
     * “正在消费”“已消费”和“首次消费”，PX 则防止消费者宕机后键永久残留。</p>
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        // 读取消费者方法上声明的业务唯一键和状态保留时长。
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        // SpEL 从消息体或方法参数中提取业务主键，再加前缀形成 Redis 键。
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 在 Redis 内一次执行“若不存在则写 CONSUMING 并返回旧值”，消除并发竞争窗口。
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 旧值为异常/处理中时抛出，让 MQ 触发延迟重试而不是并发执行业务。
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            // 已成功处理过的消息直接跳过，实现至少一次投递下的幂等消费。
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            return null;
        }

        try {
            // 首次抢到处理权才进入真实消费者业务。
            Object result = joinPoint.proceed();
            // 业务成功后覆盖为已消费状态，并保留 TTL 供重复投递判断。
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 失败时删除 CONSUMING 标记，后续 MQ 重试才有机会重新处理。
            stringRedisTemplate.delete(uniqueKey);
            throw ex;
        }
    }

    /**
     * @return 返回自定义防重复消费注解
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 同样从目标实现方法读取注解，避免接口代理导致取不到声明。
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}
