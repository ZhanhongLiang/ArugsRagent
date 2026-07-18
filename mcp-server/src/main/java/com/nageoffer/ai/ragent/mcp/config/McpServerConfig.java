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

package com.nageoffer.ai.ragent.mcp.config;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 配置类
 */
@Configuration
public class McpServerConfig {

    /**
     * 创建 MCP Streamable HTTP 传输提供者。
     *
     * <p>它负责处理 MCP initialize、tools/list 与 tools/call 等协议请求，
     * 不关心具体工具；具体工具由后续 McpSyncServer 注入。</p>
     */
    @Bean
    public HttpServletStreamableServerTransportProvider transportProvider() {
        // 使用 SDK 默认构建器创建 Servlet 传输层。
        return HttpServletStreamableServerTransportProvider.builder()
                .build();
    }

    /**
     * 将 MCP 传输层注册到 Web 容器的固定路径。
     *
     * @param transportProvider MCP HTTP 协议处理器
     * @return 映射到 /mcp 的 Servlet 注册对象
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        // Bootstrap 配置中的 MCP Server URL 指向该路径完成协议通信。
        return new ServletRegistrationBean<>(transportProvider, "/mcp");
    }

    /**
     * 汇总 Spring 自动发现的工具规格并构建同步 MCP Server。
     *
     * @param transportProvider 已注册的 HTTP 传输层
     * @param toolSpecs 所有 @Bean 返回的工具定义与处理器
     * @return 支持 tools/list 和 tools/call 的 MCP 同步服务端
     */
    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                   List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        // SDK 将 Tool Schema 暴露给客户端，并在 tools/call 时路由到对应 handler。
        return McpServer.sync(transportProvider)
                .serverInfo("ragent-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
    }
}
