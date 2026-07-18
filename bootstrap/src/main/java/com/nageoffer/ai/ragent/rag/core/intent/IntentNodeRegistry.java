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

package com.nageoffer.ai.ragent.rag.core.intent;

/**
 * 意图节点的运行期查询入口。
 *
 * <p>MCP 路由、Prompt 规划等组件只需按节点 ID 读取配置，
 * 不需要了解节点来自缓存还是数据库。</p>
 */
public interface IntentNodeRegistry {

    /**
     * 根据节点 ID 获取节点；未知或空 ID 返回 {@code null}，由调用方决定如何降级。
     */
    IntentNode getNodeById(String id);
}
