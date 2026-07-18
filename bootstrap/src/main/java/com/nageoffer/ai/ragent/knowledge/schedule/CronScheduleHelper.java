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

package com.nageoffer.ai.ragent.knowledge.schedule;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 知识库定时同步使用的 Cron 计算工具。
 * 统一使用系统时区把 Spring CronExpression 的 LocalDateTime 结果转换回 Date，避免不同调用点各自处理时区。
 */
public final class CronScheduleHelper {

    /** 工具类不允许实例化。 */
    private CronScheduleHelper() {
    }

    /**
     * 计算给定起点之后的下一次执行时间。
     * 空 Cron、空起点或无下一次触发时间均返回 null，由上层决定禁用或报错。
     */
    public static Date nextRunTime(String cron, Date from) {
        if (!StringUtils.hasText(cron) || from == null) {
            return null;
        }
        // CronExpression.parse 会在表达式不合法时抛 IllegalArgumentException，交给调用方转换为业务提示。
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime next = expression.next(fromTime);
        if (next == null) {
            return null;
        }
        return Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 通过连续两次触发时间计算实际周期，判断是否小于系统允许的最短秒数。
     * 这比仅检查 Cron 字符串可靠，能够识别复杂表达式的真实触发频率。
     */
    public static boolean isIntervalLessThan(String cron, Date from, long minSeconds) {
        if (!StringUtils.hasText(cron) || from == null) {
            return true;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime first = expression.next(fromTime);
        if (first == null) {
            return true;
        }
        LocalDateTime second = expression.next(first);
        if (second == null) {
            return true;
        }
        // 取相邻两次触发的间隔，避免高频 Cron 压垮远程站点和本地分块线程池。
        long diffSeconds = Duration.between(first, second).getSeconds();
        return diffSeconds < minSeconds;
    }
}
