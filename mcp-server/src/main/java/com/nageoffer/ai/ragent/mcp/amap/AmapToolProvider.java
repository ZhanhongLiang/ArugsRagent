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

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将高德地理编码、驾车路线和实时天气封装为 MCP 工具。
 *
 * <p>这里定义的是模型可见的工具契约与文本结果格式；实际 HTTP 调用、参数校验和供应商响应解析由
 * {@link AmapWebServiceClient} 负责，从而避免工具协议绑定到具体高德 REST 实现。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmapToolProvider {

    /** 地理编码工具 ID；意图树的 mcpToolId 必须与此名称一致。 */
    private static final String GEOCODE_TOOL = "amap_geocode";
    /** 驾车路线工具 ID；Bootstrap 自动发现后按此 ID 路由调用。 */
    private static final String DRIVING_ROUTE_TOOL = "amap_driving_route";
    /** 实时天气工具 ID。 */
    private static final String WEATHER_TOOL = "amap_weather_query";

    /** 高德 Web 服务客户端，负责真正的 REST 调用与响应校验。 */
    private final AmapWebServiceClient amapWebServiceClient;

    /**
     * 注册地理编码 MCP 工具。
     *
     * @return 包含 Tool 描述、输入 Schema 和同步处理器的工具规格
     */
    @Bean
    public McpServerFeatures.SyncToolSpecification amapGeocodeToolSpecification() {
        return toolSpecification(geocodeTool(), this::handleGeocode);
    }

    /**
     * 注册驾车路线 MCP 工具。
     *
     * @return Bootstrap tools/list 可发现的路线工具规格
     */
    @Bean
    public McpServerFeatures.SyncToolSpecification amapDrivingRouteToolSpecification() {
        return toolSpecification(drivingRouteTool(), this::handleDrivingRoute);
    }

    /**
     * 注册实时天气 MCP 工具。
     *
     * @return Bootstrap tools/list 可发现的天气工具规格
     */
    @Bean
    public McpServerFeatures.SyncToolSpecification amapWeatherToolSpecification() {
        return toolSpecification(weatherTool(), this::handleWeather);
    }

    /** 将工具元数据和对应请求处理函数组合为 SDK 可注册的同步工具规格。 */
    private McpServerFeatures.SyncToolSpecification toolSpecification(Tool tool, ToolHandler handler) {
        // SDK 调用 tools/call 时会把请求交给此 lambda，再委托到具体领域处理器。
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> handler.handle(request));
    }

    /** 声明地址文本到经纬度坐标的工具能力及其必填地址参数。 */
    private Tool geocodeTool() {
        // description 与 inputSchema 会被 Bootstrap 拉取，并交给 LLM 参数提取器理解工具能力。
        return tool(GEOCODE_TOOL,
                "使用高德地图查询地址的标准化名称和经纬度坐标。",
                schema(Map.of(
                        "address", stringProperty("待查询的详细地址或地点名称"),
                        "city", stringProperty("可选城市，用于消除同名地点歧义")
                ), List.of("address")));
    }

    /** 声明驾车路线能力；起点和终点必填，城市仅用于同名地点消歧。 */
    private Tool drivingRouteTool() {
        // 起点和终点是路线规划必填参数；city 用于同名地点消歧。
        return tool(DRIVING_ROUTE_TOOL,
                "使用高德地图规划两地之间的驾车路线，返回距离、预计时长、收费和关键导航步骤。",
                schema(Map.of(
                        "origin", stringProperty("出发地详细地址或地点名称"),
                        "destination", stringProperty("目的地详细地址或地点名称"),
                        "city", stringProperty("可选城市，用于消除同名地点歧义")
                ), List.of("origin", "destination")));
    }

    /** 声明实时天气能力；城市名称或行政区划编码是唯一必填参数。 */
    private Tool weatherTool() {
        // 城市是天气接口唯一必填参数。
        return tool(WEATHER_TOOL,
                "使用高德地图查询指定城市的实时天气，包括天气、温度、湿度、风向和发布时间。",
                schema(Map.of("city", stringProperty("城市名称或城市行政区划编码，例如杭州")), List.of("city")));
    }

    /** 执行地理编码并把标准地址、经纬度转换为 MCP 文本结果。 */
    private CallToolResult handleGeocode(CallToolRequest request) {
        // MCP SDK 传入的是通用参数 Map，先读取并交给领域客户端校验。
        Map<String, Object> arguments = arguments(request);
        try {
            AmapLocation location = amapWebServiceClient.geocode(stringArg(arguments, "address"), stringArg(arguments, "city"));
            return success("【高德地图地理编码】\n"
                    + "标准地址：" + location.formattedAddress() + "\n"
                    + "经度：" + location.longitude() + "\n"
                    + "纬度：" + location.latitude());
        } catch (AmapClientException exception) {
            // 业务可预期异常转换为 MCP isError=true，让上游模型知道结果不可用。
            return error(exception.getMessage());
        }
    }

    /** 执行路线查询，展示已标准化的起终点、距离、时长、收费和关键导航步骤。 */
    private CallToolResult handleDrivingRoute(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        try {
            AmapDrivingRoute route = amapWebServiceClient.drivingRoute(
                    stringArg(arguments, "origin"),
                    stringArg(arguments, "destination"),
                    stringArg(arguments, "city")
            );
            return success("【高德地图驾车路线】\n"
                    + "起点：" + route.origin().formattedAddress() + "\n"
                    + "终点：" + route.destination().formattedAddress() + "\n"
                    + "距离：" + formatDistance(route.distanceMeters()) + "\n"
                    + "预计时长：" + formatDuration(route.durationSeconds()) + "\n"
                    + "路线策略：" + route.strategy() + "\n"
                    + "预估收费：" + route.tollsYuan() + " 元\n"
                    + "关键步骤：\n- " + String.join("\n- ", route.steps()));
        } catch (AmapClientException exception) {
            // 不把高德失败伪造成路线文本，明确作为工具错误返回。
            return error(exception.getMessage());
        }
    }

    /** 执行实时天气查询，并将高德观测字段整理为用户易读文本。 */
    private CallToolResult handleWeather(CallToolRequest request) {
        try {
            AmapWeather weather = amapWebServiceClient.weather(stringArg(arguments(request), "city"));
            return success("【高德地图实时天气】\n"
                    + "城市：" + weather.province() + weather.city() + "\n"
                    + "天气：" + weather.weather() + "\n"
                    + "温度：" + weather.temperature() + "°C\n"
                    + "湿度：" + weather.humidity() + "%\n"
                    + "风向风力：" + weather.windDirection() + " " + weather.windPower() + "级\n"
                    + "发布时间：" + weather.reportTime());
        } catch (AmapClientException exception) {
            // Key 缺失、城市无数据、网络失败都会在这里收敛为工具错误结果。
            return error(exception.getMessage());
        }
    }

    /** 按统一方式创建 MCP Tool；name 同时是工具路由与意图树关联的稳定键。 */
    private Tool tool(String name, String description, JsonSchema schema) {
        // Tool 是 MCP 对外暴露的元数据，名称是客户端调用和意图树关联的唯一标识。
        return Tool.builder().name(name).description(description).inputSchema(schema).build();
    }

    /** 创建 object 类型 JSON Schema，其中 required 约束模型必须提取的参数。 */
    private JsonSchema schema(Map<String, ? extends Object> properties, List<String> required) {
        // 构造 JSON Schema object；properties 描述字段，required 约束 LLM 必须提取的参数。
        return new JsonSchema("object", new LinkedHashMap<>(properties), required, null, null, null);
    }

    /** 创建字符串类型的 Schema 字段并保留中文语义描述，供参数提取提示词使用。 */
    private Map<String, Object> stringProperty(String description) {
        // 每个参数以 JSON Schema string 类型描述，并将中文说明提供给 LLM 参数提取提示词。
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    /** 将 null 参数归一为空 Map，让所有处理器以统一方式读取字段。 */
    private Map<String, Object> arguments(CallToolRequest request) {
        // SDK 允许 arguments 为 null；统一返回空 Map 可减少处理器空指针分支。
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    /** 将 MCP 动态值转换为去首尾空格的字符串，字段缺失时返回 null。 */
    private String stringArg(Map<String, Object> arguments, String name) {
        // MCP 参数经 LLM 提取后是 Object，转换为去除首尾空格的字符串。
        Object value = arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    /** 将米转换为米或一位小数公里，避免工具结果直接暴露难读的原始单位。 */
    private String formatDistance(long meters) {
        // 距离大于等于一公里时用公里显示，否则保留米级精度。
        return meters >= 1000 ? String.format("%.1f 公里", meters / 1000D) : meters + " 米";
    }

    /** 将高德返回的秒数转换为至少一分钟的用户可读时长。 */
    private String formatDuration(long seconds) {
        // 路线接口返回秒；最少显示一分钟，超过一小时拆成小时和分钟。
        long minutes = Math.max(1L, Math.round(seconds / 60D));
        return minutes >= 60 ? minutes / 60 + " 小时" + minutes % 60 + " 分钟" : minutes + " 分钟";
    }

    /** 构造成功的文本内容，供 Bootstrap 后续格式化进 MCP 上下文。 */
    private CallToolResult success(String text) {
        // 成功结果以 TextContent 返回，后续会被 Bootstrap 格式化进 MCP 上下文。
        log.info("高德 MCP 工具调用成功, resultLength={}", text.length());
        return CallToolResult.builder().content(List.of(new TextContent(text))).isError(false).build();
    }

    /** 将可预期的高德错误转换为 isError=true 的 MCP 响应。 */
    private CallToolResult error(String message) {
        // isError=true 让上游 Prompt 区分“工具无结果”与“正常查询文本”。
        log.warn("高德 MCP 工具调用失败, reason={}", message);
        return CallToolResult.builder().content(List.of(new TextContent(message))).isError(true).build();
    }

    @FunctionalInterface
    private interface ToolHandler {
        /** 统一四个工具处理器的函数签名，便于通过 method reference 注册。 */
        CallToolResult handle(CallToolRequest request);
    }
}
