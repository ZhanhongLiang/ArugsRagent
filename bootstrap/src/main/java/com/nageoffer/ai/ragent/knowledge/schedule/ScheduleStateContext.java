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

import lombok.Builder;
import lombok.Getter;

import java.util.Date;

/**
 * 一次调度刷新运行期间需要写回的稳定上下文。
 * 将 execId、Cron、开始时间和下一次触发时间集中传递，避免状态写入方法依赖可变的 Schedule 实体。
 */
@Getter
@Builder
public class ScheduleStateContext {

    /** 调度主表 id。 */
    private final String scheduleId;
    /** 本次执行明细表 id，可为空表示执行记录尚未创建。 */
    private final String execId;
    /** 本次运行采用的 Cron 表达式。 */
    private final String cronExpr;
    /** 本次运行开始时间。 */
    private final Date startTime;
    /** 根据 Cron 预先计算的下一次执行时间。 */
    private final Date nextRunTime;
}
