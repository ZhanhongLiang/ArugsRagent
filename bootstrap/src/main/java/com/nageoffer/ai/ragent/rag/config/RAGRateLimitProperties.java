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

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 全局并发限流配置。
 *
 * <p>这些参数决定全局排队限流行为：max-concurrent 是集群总坑位，max-wait-seconds 是用户愿意等待的预算，
 * lease-seconds 是 permit 崩溃兜底回收时间，poll-interval-ms 是 Pub/Sub 之外的轮询保险。</p>
 */
@Data
@Configuration
public class RAGRateLimitProperties {

    /**
     * 是否启用全局限流
     */
    @Value("${rag.rate-limit.global.enabled:true}")
    private Boolean globalEnabled;

    /**
     * 最大并发数，也就是整个集群同时允许跑多少个 RAG 流式任务。
     */
    @Value("${rag.rate-limit.global.max-concurrent:50}")
    private Integer globalMaxConcurrent;

    /**
     * 最大等待秒数，排队超过该时间会走 reject/finish/done 拒绝路径。
     */
    @Value("${rag.rate-limit.global.max-wait-seconds:20}")
    private Integer globalMaxWaitSeconds;

    /**
     * 许可自动释放时间（兜底用），单位秒；必须大于一次业务最长可能执行时间。
     */
    @Value("${rag.rate-limit.global.lease-seconds:600}")
    private Integer globalLeaseSeconds;

    /**
     * 排队轮询间隔（毫秒），Pub/Sub 丢失时靠它兜底重新尝试抢占。
     */
    @Value("${rag.rate-limit.global.poll-interval-ms:200}")
    private Integer globalPollIntervalMs;
}
