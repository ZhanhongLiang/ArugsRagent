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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，
 * 并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    /**
     * 聊天、嵌入和重排共用的故障转移执行器。
     *
     * <p>ModelSelector 决定候选模型及其顺序；本类负责逐个实际调用、更新健康状态，
     * 并在当前候选失败时继续尝试下一个候选。</p>
     */

    /** 保存熔断器状态，控制候选模型是否允许再次发起远程调用。 */
    private final ModelHealthStore healthStore;

    /**
     * 按候选顺序调用模型并在失败时执行回退。
     *
     * @param capability 本次调用能力，用于日志和最终错误提示
     * @param targets 已按优先级排序的模型候选
     * @param clientResolver 根据候选提供方解析对应客户端
     * @param caller 对具体客户端执行实际远程调用的函数
     * @return 首个成功候选的调用结果
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        // 能力类型仅用于可读日志和错误信息；回退算法对聊天、嵌入和重排一致。
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        for (ModelTarget target : targets) {
            // 从 Spring 自动发现的客户端映射中解析当前提供方实现。
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            // 最后一次熔断判断：选择后到实际调用前健康状态仍可能发生变化。
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            try {
                // 真实调用成功后，关闭或半开状态都会被标记为健康。
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                // 任意提供方异常都视为当前候选失败，记录健康状态后继续回退。
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
