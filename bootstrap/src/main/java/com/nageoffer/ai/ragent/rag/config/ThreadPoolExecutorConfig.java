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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 线程池执行器配置类。
 *
 * <p>流式问答里最重要的隔离点是：Tomcat 入口线程只创建 SseEmitter，RAG 主链路在
 * chatEntryExecutor，模型 SSE 读取在 modelStreamExecutor，检索/意图/MCP 还有各自线程池。
 * 这样长连接不会占满 Web 线程，模型读流也不会阻塞检索线程。</p>
 */
@Configuration
public class ThreadPoolExecutorConfig {

    /**
     * CPU核心数，用于动态计算线程池大小
     */
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * MCP批处理线程池
     */
    @Bean
    public Executor mcpBatchExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("mcp_batch_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG上下文处理线程池（子问题级并行：检索+MCP）
     */
    @Bean
    public Executor ragContextExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 2,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_context_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG 检索线程池（用于通道级别的并行）
     */
    @Bean
    public Executor ragRetrievalExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 2,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * RAG 内部检索线程池
     */
    @Bean
    public Executor innerRetrievalExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT << 1,
                CPU_COUNT << 2,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("rag_inner_retrieval_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 意图识别并行执行线程池
     */
    @Bean
    public Executor intentClassifyExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,
                CPU_COUNT << 1,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("intent_classify_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 对话记忆摘要生成线程池
     */
    @Bean
    public Executor memorySummaryExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                Math.max(2, CPU_COUNT >> 1),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("memory_summary_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 模型流式输出线程池。
     *
     * <p>它承载 OkHttp readUtf8Line 的阻塞读取任务。使用 AbortPolicy 是为了在线程池满时快速失败，
     * StreamAsyncExecutor 会取消底层 Call 并通知 onError，避免连接悬挂。</p>
     */
    @Bean
    public Executor modelStreamExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("model_stream_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * SSE 排队后执行入口线程池。
     *
     * <p>大小与全局 permit 数一致：拿到 permit 的请求才会进入这个线程池，
     * SynchronousQueue + AbortPolicy 可以暴露配置不匹配或系统过载，避免内部再排一层无界队列。</p>
     */
    @Bean
    public Executor chatEntryExecutor(RAGRateLimitProperties rateLimitProperties) {
        int size = rateLimitProperties.getGlobalMaxConcurrent();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size,
                size,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("chat_entry_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 知识库文档分块线程池
     */
    @Bean
    public Executor knowledgeChunkExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("kb_chunk_executor_")
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }

    /**
     * 对话记忆加载线程池（并行加载摘要与历史记录）
     */
    @Bean
    public Executor memoryLoadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                ThreadFactoryBuilder.create()
                        .setNamePrefix("memory_load_executor_")
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return TtlExecutors.getTtlExecutor(executor);
    }
}
