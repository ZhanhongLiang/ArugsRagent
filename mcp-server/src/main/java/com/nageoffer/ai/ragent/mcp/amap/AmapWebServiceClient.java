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
 * 高德 Web 服务的领域访问端口。
 *
 * <p>MCP 工具层只依赖这个接口；需要替换供应商、添加缓存或在测试中模拟高德响应时，无需修改工具定义。</p>
 */
public interface AmapWebServiceClient {

    /** @return 指定地址的标准地址和经纬度。 */
    AmapLocation geocode(String address, String city);

    /** @return 两地之间的驾车路线摘要。 */
    AmapDrivingRoute drivingRoute(String origin, String destination, String city);

    /** @return 指定城市的实时天气快照。 */
    AmapWeather weather(String city);
}
