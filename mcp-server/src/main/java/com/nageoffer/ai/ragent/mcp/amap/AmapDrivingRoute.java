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

package com.nageoffer.ai.ragent.mcp.amap;

import java.util.List;

/**
 * 高德驾车路径规划的领域结果。
 *
 * @param origin 标准化后的起点坐标与地址
 * @param destination 标准化后的终点坐标与地址
 * @param distanceMeters 路线总距离，单位米
 * @param durationSeconds 预计行驶时间，单位秒
 * @param strategy 高德返回的路线策略说明
 * @param tollsYuan 预估收费金额，单位元
 * @param steps 关键导航指令列表
 */
public record AmapDrivingRoute(AmapLocation origin,
                               AmapLocation destination,
                               long distanceMeters,
                               long durationSeconds,
                               String strategy,
                               String tollsYuan,
                               List<String> steps) {
}
