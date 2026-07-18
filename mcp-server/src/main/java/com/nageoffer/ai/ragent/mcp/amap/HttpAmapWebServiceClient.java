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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDK {@link HttpClient} 的高德 Web 服务客户端。
 *
 * <p>本实现集中处理 URL 编码、超时、HTTP 状态码、高德业务状态码和 JSON 结构校验，向工具层只返回
 * 经过标准化的领域对象或 {@link AmapClientException}。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpAmapWebServiceClient implements AmapWebServiceClient {

    /** 高德 Web 服务 REST API 的统一根地址。 */
    private static final String BASE_URL = "https://restapi.amap.com";

    /** 读取本地私有配置中的 Web 服务 Key 和超时参数。 */
    private final AmapProperties properties;
    /** 将高德响应 JSON 转为可按字段读取的 JsonNode。 */
    private final ObjectMapper objectMapper;

    /**
     * 调用高德地理编码接口，将地址文本解析为标准地址与经纬度。
     *
     * @param address 详细地址或地点名称
     * @param city 可选城市，用于消除同名地点歧义
     * @return 标准化地址和坐标
     */
    @Override
    public AmapLocation geocode(String address, String city) {
        // 公共查询参数中已包含 API Key 与 JSON 输出格式。
        Map<String, String> query = baseQuery();
        query.put("address", requireText(address, "地址"));
        putIfPresent(query, "city", city);

        // 请求地理编码接口，失败会由 get 转成领域异常。
        JsonNode response = get("/v3/geocode/geo", query);
        JsonNode geocodes = response.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            // 高德没有返回匹配地点时，不能虚构坐标。
            throw new AmapClientException("未查询到该地址的坐标信息");
        }
        JsonNode first = geocodes.get(0);
        String location = requiredField(first, "location", "地址坐标");
        String[] coordinate = location.split(",", 2);
        if (coordinate.length != 2) {
            // 期待 longitude,latitude 两段格式，异常格式不能继续传给路线接口。
            throw new AmapClientException("高德地图返回的坐标格式无效");
        }
        return new AmapLocation(requiredField(first, "formatted_address", "格式化地址"), coordinate[0], coordinate[1]);
    }

    /**
     * 先对起点和终点地理编码，再调用高德驾车路径规划接口。
     *
     * @param origin 起点地址文本
     * @param destination 终点地址文本
     * @param city 可选城市
     * @return 距离、时长、策略与关键导航步骤
     */
    @Override
    public AmapDrivingRoute drivingRoute(String origin, String destination, String city) {
        // 路线 API 接收坐标，因此先复用地理编码逻辑标准化两个地点。
        AmapLocation originLocation = geocode(origin, city);
        AmapLocation destinationLocation = geocode(destination, city);

        Map<String, String> query = baseQuery();
        query.put("origin", originLocation.coordinate());
        query.put("destination", destinationLocation.coordinate());
        query.put("extensions", "base");

        // extensions=base 返回基础路线信息，足以生成用户可读摘要。
        JsonNode response = get("/v3/direction/driving", query);
        JsonNode paths = response.path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            // 无可用路线时明确失败，而不是返回距离为零的误导性结果。
            throw new AmapClientException("未查询到可用驾车路线");
        }
        JsonNode path = paths.get(0);
        List<String> steps = new ArrayList<>();
        for (JsonNode step : path.path("steps")) {
            // 高德每个 step 是一段可读导航指令；空指令跳过。
            String instruction = step.path("instruction").asText();
            if (!instruction.isBlank()) {
                steps.add(instruction);
            }
        }
        return new AmapDrivingRoute(
                originLocation,
                destinationLocation,
                parseLong(path.path("distance").asText()),
                parseLong(path.path("duration").asText()),
                path.path("strategy").asText("默认策略"),
                path.path("tolls").asText("0"),
                List.copyOf(steps)
        );
    }

    /**
     * 调用高德实时天气接口。
     *
     * @param city 城市名称或行政区划编码
     * @return 当前天气观测值
     */
    @Override
    public AmapWeather weather(String city) {
        Map<String, String> query = baseQuery();
        query.put("city", requireText(city, "城市"));
        query.put("extensions", "base");

        JsonNode response = get("/v3/weather/weatherInfo", query);
        JsonNode lives = response.path("lives");
        if (!lives.isArray() || lives.isEmpty()) {
            // 没有 live 记录代表该城市未查询到实时观测数据。
            throw new AmapClientException("未查询到该城市的实时天气");
        }
        JsonNode live = lives.get(0);
        return new AmapWeather(
                live.path("province").asText(),
                live.path("city").asText(),
                live.path("weather").asText(),
                live.path("temperature").asText(),
                live.path("humidity").asText(),
                live.path("winddirection").asText(),
                live.path("windpower").asText(),
                live.path("reporttime").asText()
        );
    }

    /**
     * 发送一次高德 GET 请求并统一校验 HTTP 与业务状态码。
     *
     * @param path API 相对路径
     * @param query 已编码前的查询参数
     * @return 高德原始 JSON 根节点
     */
    private JsonNode get(String path, Map<String, String> query) {
        // 先编码所有参数，避免中文地址和特殊字符破坏 URI。
        URI uri = URI.create(BASE_URL + path + "?" + encodeQuery(query));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .GET()
                .build();
        try {
            // 每次调用创建带连接超时的 JDK HTTP 客户端，避免无限等待网络建立。
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getConnectionTimeoutMs()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // HTTP 层失败不进入 JSON 解析，直接抛出可读异常。
                throw new AmapClientException("高德地图请求失败，HTTP 状态码：" + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (!"1".equals(body.path("status").asText())) {
                // 高德会用 HTTP 200 返回业务失败，必须继续检查 status 字段。
                throw new AmapClientException("高德地图查询失败：" + body.path("info").asText("未知错误"));
            }
            return body;
        } catch (InterruptedException exception) {
            // 恢复中断标记，让上层线程池或取消逻辑能正确感知中断。
            Thread.currentThread().interrupt();
            throw new AmapClientException("高德地图请求被中断", exception);
        } catch (IOException exception) {
            throw new AmapClientException("高德地图网络请求失败", exception);
        }
    }

    /** 创建所有高德请求共享的 Key 与 JSON 输出格式参数。 */
    private Map<String, String> baseQuery() {
        // 所有高德请求共用 Key 与输出格式；Key 缺失时 requireText 会阻止无意义网络调用。
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", requireText(properties.getWebServiceKey(), "高德 Web 服务 Key（AMAP_WEB_SERVICE_KEY）"));
        query.put("output", "JSON");
        return query;
    }

    /** 对查询参数的键和值分别进行 UTF-8 URL 编码，防止中文地址破坏请求 URI。 */
    private String encodeQuery(Map<String, String> query) {
        // 对 key/value 分别 URL 编码，再按 & 拼接为标准 query string。
        return query.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    /** 从 JSON 节点读取供应商必填字段，并复用统一的非空校验。 */
    private String requiredField(JsonNode node, String field, String label) {
        // 取字段后复用非空校验，统一错误提示格式。
        return requireText(node.path(field).asText(), label);
    }

    /** 校验用户输入、配置或供应商字段为非空文本，否则抛出可被工具层处理的异常。 */
    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            // 地址、城市、Key 等关键字段为空时停止后续调用，避免产生难定位的远程错误。
            throw new AmapClientException(label + "未配置或为空");
        }
        return value.trim();
    }

    /** 仅当可选参数有实际内容时才写入请求，保留高德接口的默认行为。 */
    private void putIfPresent(Map<String, String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            // 可选城市等字段只有存在时才发送，保持接口默认行为。
            query.put(name, value.trim());
        }
    }

    /** 将供应商以字符串返回的距离或时长转换为 long，异常格式时安全降级为零。 */
    private long parseLong(String value) {
        try {
            // 距离和时长在高德响应中是字符串，转换为 long 供展示层格式化。
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            // 供应商缺失或格式异常时返回 0，避免单个展示字段中断整个路线结果。
            return 0L;
        }
    }
}
