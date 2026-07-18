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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 高德 Web 服务的本地运行配置。
 *
 * <p>Key 应由被 Git 忽略的 {@code application-local.yml} 或环境变量提供，不能写入版本控制文件。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {

    /** 高德 Web 服务 Key，对应配置项 {@code amap.web-service-key}。 */
    private String webServiceKey;
    /** 与高德建立 TCP 连接的最长等待时间，默认 3 秒。 */
    private int connectionTimeoutMs = 3000;
    /** 单次 HTTP 请求的整体最长等待时间，默认 5 秒。 */
    private int requestTimeoutMs = 5000;
}
