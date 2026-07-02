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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型健康状态存储器，也是项目里的三态熔断器实现。
 *
 * <p>它解决的问题是：当某个模型连续失败时，不要让后续每个用户请求都继续撞向这个模型、等待超时，
 * 而是先把它标记为不可用；冷却时间结束后，再只放行一个真实请求做恢复探测。</p>
 *
 * <p>本类维护每个模型自己的健康状态，key 是模型 id，value 是 {@link ModelHealth}：</p>
 * <ul>
 *     <li>{@link State#CLOSED}：闭合状态，表示模型健康，请求正常放行。</li>
 *     <li>{@link State#OPEN}：打开状态，表示模型已熔断，冷却期内直接拒绝调用。</li>
 *     <li>{@link State#HALF_OPEN}：半开状态，表示冷却结束后正在探测恢复，同一时间只允许一个探测请求。</li>
 * </ul>
 *
 * <p>它和故障转移执行器 {@link ModelRoutingExecutor} 的协作方式是：</p>
 * <ol>
 *     <li>{@link ModelSelector} 构建候选列表时调用 {@link #isUnavailable(String)}，提前过滤明显不可用的模型。</li>
 *     <li>{@link ModelRoutingExecutor} 真正调用前调用 {@link #allowCall(String)}，做最终放行检查。</li>
 *     <li>模型调用成功后调用 {@link #markSuccess(String)}，把状态恢复为 CLOSED。</li>
 *     <li>模型调用失败后调用 {@link #markFailure(String)}，累计失败或重新熔断。</li>
 * </ol>
 *
 * <p>所有会修改状态的方法都使用 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}。
 * 这样可以保证同一个模型 id 的状态检查和状态修改是原子的，同时不同模型之间仍然可以并发执行，
 * 不需要用全局 synchronized 锁降低吞吐。</p>
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    /**
     * AI 模型配置。
     *
     * <p>熔断器主要读取其中 selection 配置：</p>
     * <ul>
     *     <li>failureThreshold：CLOSED 状态下连续失败多少次后触发 OPEN。</li>
     *     <li>openDurationMs：OPEN 状态持续多久后允许进入 HALF_OPEN 探测。</li>
     * </ul>
     */
    private final AIModelProperties properties;

    /**
     * 每个模型 id 对应一份独立健康状态。
     *
     * <p>使用 ConcurrentHashMap 的原因不是只为了并发读写安全，更重要的是可以借助 compute
     * 对单个 key 做原子状态转换：同一个模型不会同时被两个线程从 OPEN 转成 HALF_OPEN，
     * 不同模型之间也不会互相阻塞。</p>
     */
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /**
     * 判断模型在“选择阶段”是否应当被视为不可用。
     *
     * <p>这个方法给 {@link ModelSelector} 使用，只做轻量只读判断，不负责推进状态转换。
     * 真正的 OPEN 到 HALF_OPEN 转换发生在 {@link #allowCall(String)} 中，因为只有实际调用前
     * 才应该占用那一次宝贵的探测机会。</p>
     *
     * @param id 模型稳定 id
     * @return true 表示本次候选列表应跳过该模型
     */
    public boolean isUnavailable(String id) {
        // 没有健康记录说明模型从未失败过，默认视为健康可用。
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }

        // OPEN 且冷却时间还没到：模型处于熔断期，选择阶段直接过滤掉，避免用户继续等待它超时。
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }

        // HALF_OPEN 且已有探测请求在飞：为了避免恢复探测雪崩，本次也认为不可用。
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    /**
     * 调用前的最终放行检查。
     *
     * <p>这个方法由 {@link ModelRoutingExecutor} 在真正调用模型前执行。它不只是读状态，
     * 还负责在冷却到期时完成 OPEN -> HALF_OPEN 的状态转换，并抢占唯一的探测请求名额。</p>
     *
     * <p>返回值含义：</p>
     * <ul>
     *     <li>true：允许本次调用继续执行。</li>
     *     <li>false：当前模型仍在熔断期，或 HALF_OPEN 已有探测请求在飞，应切换下一个候选模型。</li>
     * </ul>
     *
     * @param id 模型稳定 id
     * @return 是否允许调用该模型
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        // 没有模型 id 无法记录熔断状态，也无法安全调用，直接拒绝。
        if (id == null) {
            return false;
        }

        // 用当前时间判断 OPEN 冷却期是否结束。
        long now = System.currentTimeMillis();

        // compute 的 lambda 必须返回 ModelHealth，不能直接返回 boolean；用 AtomicBoolean 把放行结果带到外层。
        AtomicBoolean allowed = new AtomicBoolean(false);

        // 对同一个模型 id，compute 内部的检查和状态修改是原子的。
        healthById.compute(id, (k, v) -> {
            // 没有历史状态说明模型第一次被调用，创建默认 CLOSED 状态。
            if (v == null) {
                v = new ModelHealth();
            }

            // OPEN：模型已熔断，需要看冷却时间是否已经结束。
            if (v.state == State.OPEN) {
                // 冷却未结束：拒绝本次调用，让执行器 fallback 到下一个模型。
                if (v.openUntil > now) {
                    return v;
                }

                // 冷却已结束：进入 HALF_OPEN，并立即占用唯一探测名额。
                // 这样并发请求里只有第一个线程可以试探供应商是否恢复。
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }

            // HALF_OPEN：模型正在恢复探测阶段，同一时间只允许一个真实请求通过。
            if (v.state == State.HALF_OPEN) {
                // 已经有探测请求在飞：拒绝其它请求，避免一批用户同时承担超时探测成本。
                if (v.halfOpenInFlight) {
                    return v;
                }

                // 没有探测在飞：占用探测名额，允许本次调用。
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }

            // CLOSED：健康状态，正常放行。
            allowed.set(true);
            return v;
        });

        // 返回 compute 内部记录的最终放行结果。
        return allowed.get();
    }

    /**
     * 标记模型调用成功。
     *
     * <p>成功意味着模型当前可用，所以无论之前处于 CLOSED、OPEN 还是 HALF_OPEN，
     * 都重置为初始 CLOSED 状态。最关键的场景是 HALF_OPEN 探测成功：模型恢复正常服务。</p>
     *
     * @param id 模型稳定 id
     */
    public void markSuccess(String id) {
        // 没有模型 id 时无法定位健康状态，直接忽略。
        if (id == null) {
            return;
        }

        // 成功标记同样用 compute，保证和 allowCall、markFailure 对同一个模型的状态修改互斥。
        healthById.compute(id, (k, v) -> {
            // 如果之前没有健康记录，创建一个默认 CLOSED 状态即可。
            if (v == null) {
                return new ModelHealth();
            }

            // 任意成功调用都把状态重置为 CLOSED。
            v.state = State.CLOSED;

            // 清空连续失败次数，避免偶发失败跨成功调用继续累计。
            v.consecutiveFailures = 0;

            // CLOSED 状态不需要熔断截止时间。
            v.openUntil = 0L;

            // CLOSED 状态不处于探测阶段，释放 HALF_OPEN 探测占用标记。
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 标记模型调用失败。
     *
     * <p>失败处理分两种：</p>
     * <ul>
     *     <li>HALF_OPEN 失败：探测失败，说明模型还没恢复，直接重新 OPEN，并重新计算冷却时间。</li>
     *     <li>CLOSED 失败：累计 consecutiveFailures，达到 failureThreshold 后触发 OPEN。</li>
     * </ul>
     *
     * <p>OPEN 状态理论上不会有真实调用进入，因为 {@link #allowCall(String)} 会拒绝。
     * 即使极端情况下出现 OPEN 下的 markFailure，也只会走默认累计分支，不影响核心状态机设计。</p>
     *
     * @param id 模型稳定 id
     */
    public void markFailure(String id) {
        // 没有模型 id 时无法定位健康状态，直接忽略。
        if (id == null) {
            return;
        }

        // 失败发生的时间点，用于计算新的 OPEN 冷却截止时间。
        long now = System.currentTimeMillis();

        // 对同一个模型的失败计数和状态转换必须原子执行，避免并发失败时计数丢失或重复探测。
        healthById.compute(id, (k, v) -> {
            // 没有历史状态时创建默认 CLOSED 状态，然后按一次失败处理。
            if (v == null) {
                v = new ModelHealth();
            }

            // HALF_OPEN 下失败是一票否决：探测请求失败，说明供应商还没恢复。
            if (v.state == State.HALF_OPEN) {
                // 重新进入 OPEN 熔断状态。
                v.state = State.OPEN;

                // 从当前失败时刻重新计算冷却截止时间。
                v.openUntil = now + properties.getSelection().getOpenDurationMs();

                // HALF_OPEN -> OPEN 不依赖累计失败次数，所以清零，等待以后恢复后重新开始统计。
                v.consecutiveFailures = 0;

                // 探测请求已经结束，释放 in-flight 标记；但 OPEN 状态下仍不会放行新请求。
                v.halfOpenInFlight = false;
                return v;
            }

            // CLOSED 下失败：先累计连续失败次数。
            v.consecutiveFailures++;

            // 达到阈值后触发熔断，默认配置通常是连续失败 2 次。
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                // 进入 OPEN，后续请求在冷却期内会被 isUnavailable/allowCall 跳过。
                v.state = State.OPEN;

                // 设置本轮熔断截止时间，例如 now + 30000ms。
                v.openUntil = now + properties.getSelection().getOpenDurationMs();

                // 进入 OPEN 后本轮失败计数完成使命，清零等待下一轮 CLOSED 后重新累计。
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 单个模型的健康状态快照。
     *
     * <p>这个对象只放在 {@link #healthById} 中，由 compute 内部修改。
     * 字段没有使用 volatile，是因为状态读写主要通过 ConcurrentHashMap 的原子操作完成；
     * {@link ModelHealthStore#isUnavailable(String)} 是选择阶段的轻量预过滤，允许它作为近似实时判断，
     * 调用前仍会由 {@link ModelHealthStore#allowCall(String)} 做最终原子校验。</p>
     */
    private static class ModelHealth {

        /**
         * CLOSED 状态下的连续失败次数。
         *
         * <p>达到 failureThreshold 后触发 OPEN；成功调用会清零；进入 OPEN 后也会清零。</p>
         */
        private int consecutiveFailures;

        /**
         * OPEN 状态的冷却截止时间戳，单位毫秒。
         *
         * <p>当前时间小于 openUntil 时继续拒绝调用；当前时间达到或超过 openUntil 时，
         * {@link ModelHealthStore#allowCall(String)} 会把状态推进到 HALF_OPEN。</p>
         */
        private long openUntil;

        /**
         * HALF_OPEN 状态下是否已有探测请求正在执行。
         *
         * <p>它保证同一个模型同一时刻只放行一个真实用户请求去探测恢复情况，避免故障恢复时大量请求同时打向不稳定供应商。</p>
         */
        private boolean halfOpenInFlight;

        /**
         * 当前熔断状态：CLOSED、OPEN 或 HALF_OPEN。
         */
        private State state;

        /**
         * 新模型默认健康：CLOSED、失败次数为 0、没有冷却时间、没有探测请求。
         */
        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.state = State.CLOSED;
        }
    }

    /**
     * 三态熔断器状态枚举。
     */
    private enum State {
        /**
         * 闭合：模型健康，请求正常通过。
         */
        CLOSED,

        /**
         * 打开：模型已熔断，冷却期内跳过调用。
         */
        OPEN,

        /**
         * 半开：冷却结束后允许一个探测请求验证模型是否恢复。
         */
        HALF_OPEN
    }
}