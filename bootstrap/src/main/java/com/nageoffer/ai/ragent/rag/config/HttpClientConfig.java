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

package com.nageoffer.ai.ragent.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 区分流式模型调用与普通短请求的 OkHttp 客户端配置。
 *
 * 流式 SSE 不应因持续输出而触发读/调用超时；普通 HTTP 请求则必须设置有限超时，防止远程调用无限占用资源。
 */
@Configuration
public class HttpClientConfig {

    /**
     * 流式 HTTP 客户端（Primary）。零 read/call timeout 表示允许持续读取，取消由底层 Call.cancel 控制。
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                // 仅连接层故障自动重试；业务层仍负责首包失败、模型降级等策略。
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 普通同步 HTTP 客户端，适用于有明确请求预算的远程抓取和工具调用。
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build();
    }
}
