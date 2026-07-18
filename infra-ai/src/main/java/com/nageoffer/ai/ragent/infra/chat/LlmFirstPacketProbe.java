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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 首包等待探测器。
 *
 * <p>将 awaitFirstPacket 拆为独立 Spring Bean，便于 AOP 采集 TTFT Trace。
 * Spring AOP 不拦截类内自调用，因此 RoutingLLMService 必须通过外部 Bean
 * 调用本方法，{@code @RagTraceNode} 才会生效。</p>
 */
@Component
public class LlmFirstPacketProbe {

    @RagTraceNode(name = "llm-first-packet", type = "LLM_TTFT")
    public ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                          long timeout,
                                                          TimeUnit unit) throws InterruptedException {
        // 实际阻塞逻辑由桥接器完成；本类只提供可被 AOP 拦截的 Trace 边界。
        return bridge.awaitFirstPacket(timeout, unit);
    }
}
