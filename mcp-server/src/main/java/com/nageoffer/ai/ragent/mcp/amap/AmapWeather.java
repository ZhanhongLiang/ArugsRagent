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

/**
 * 高德实时天气接口返回的观测快照。
 *
 * @param province 所属省份
 * @param city 城市名称
 * @param weather 天气现象，例如晴、多云
 * @param temperature 当前温度，单位摄氏度
 * @param humidity 相对湿度百分比
 * @param windDirection 风向
 * @param windPower 风力等级
 * @param reportTime 气象观测发布时间
 */
public record AmapWeather(String province,
                          String city,
                          String weather,
                          String temperature,
                          String humidity,
                          String windDirection,
                          String windPower,
                          String reportTime) {
}
