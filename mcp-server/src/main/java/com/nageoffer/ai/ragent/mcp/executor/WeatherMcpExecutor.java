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

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 基于固定城市坐标和确定性随机数的天气演示 MCP 工具。
 *
 * <p>这是离线演示实现，不访问真实气象服务；同一城市、同一天会得到相同结果，便于测试与复现。</p>
 */
@Slf4j
@Component
public class WeatherMcpExecutor {

    /** 工具在 MCP 协议中的唯一名称。 */
    private static final String TOOL_ID = "weather_query";

    /** 支持城市到纬度、经度的映射；当前模拟算法主要使用纬度调整气温。 */
    private static final Map<String, double[]> CITY_COORDINATES = new LinkedHashMap<>();

    /** 初始化可查询的演示城市及其坐标。 */
    static {
        CITY_COORDINATES.put("北京", new double[]{39.9, 116.4});
        CITY_COORDINATES.put("上海", new double[]{31.2, 121.5});
        CITY_COORDINATES.put("广州", new double[]{23.1, 113.3});
        CITY_COORDINATES.put("深圳", new double[]{22.5, 114.1});
        CITY_COORDINATES.put("杭州", new double[]{30.3, 120.2});
        CITY_COORDINATES.put("成都", new double[]{30.6, 104.1});
        CITY_COORDINATES.put("武汉", new double[]{30.6, 114.3});
        CITY_COORDINATES.put("南京", new double[]{32.1, 118.8});
        CITY_COORDINATES.put("西安", new double[]{34.3, 108.9});
        CITY_COORDINATES.put("重庆", new double[]{29.6, 106.5});
        CITY_COORDINATES.put("长沙", new double[]{28.2, 112.9});
        CITY_COORDINATES.put("天津", new double[]{39.1, 117.2});
        CITY_COORDINATES.put("苏州", new double[]{31.3, 120.6});
        CITY_COORDINATES.put("郑州", new double[]{34.7, 113.6});
        CITY_COORDINATES.put("青岛", new double[]{36.1, 120.4});
        CITY_COORDINATES.put("大连", new double[]{38.9, 121.6});
        CITY_COORDINATES.put("厦门", new double[]{24.5, 118.1});
        CITY_COORDINATES.put("昆明", new double[]{25.0, 102.7});
        CITY_COORDINATES.put("哈尔滨", new double[]{45.8, 126.5});
        CITY_COORDINATES.put("三亚", new double[]{18.3, 109.5});
    }

    /** 四季各自的天气候选池，避免所有月份都出现不合理的天气描述。 */
    private static final List<String> WEATHER_TYPES_SPRING = List.of("晴", "多云", "阴", "小雨", "阵雨", "多云转晴");
    private static final List<String> WEATHER_TYPES_SUMMER = List.of("晴", "多云", "雷阵雨", "大雨", "暴雨", "多云转阴");
    private static final List<String> WEATHER_TYPES_AUTUMN = List.of("晴", "多云", "阴", "小雨", "晴转多云", "多云转晴");
    private static final List<String> WEATHER_TYPES_WINTER = List.of("晴", "多云", "阴", "小雪", "中雪", "晴转多云", "雾");

    /** 注册天气工具和请求处理器，供 MCP Server 在 tools/list 中公布。 */
    @Bean
    public McpServerFeatures.SyncToolSpecification weatherToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    /** 构建工具元数据和参数 Schema，使 LLM 能自动提取城市、查询类型和天数。 */
    private Tool buildTool() {
        // 使用有序 Map 保持工具字段在调试输出与提示词中的顺序稳定。
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("city", Map.of(
                "type", "string",
                "description", "城市名称，如北京、上海、广州等"
        ));

        properties.put("queryType", Map.of(
                "type", "string",
                "description", "查询类型：current(当前天气)、forecast(未来预报)",
                "enum", List.of("current", "forecast"),
                "default", "current"
        ));

        properties.put("days", Map.of(
                "type", "integer",
                "description", "预报天数，仅forecast模式有效，默认3天，最多7天",
                "default", 3
        ));

        // city 是唯一必填参数，其他字段由模型不提供时使用默认值。
        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("city"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("查询城市天气信息，支持查看当前实时天气和未来多天天气预报，包含温度、湿度、风力、天气状况等信息")
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * 解析 MCP 参数、应用默认值与上限、选择当前天气或预报结果，并统一转换异常。
     */
    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            // MCP SDK 允许 arguments 为空，统一为空 Map 避免空指针。
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String city = stringArg(args, "city");
            String queryType = stringArg(args, "queryType");
            Integer days = intArg(args, "days");

            if (city == null || city.isBlank()) {
                return errorResult("请提供城市名称");
            }
            // 省略可选参数时采用 Schema 中声明的默认语义。
            if (queryType == null || queryType.isBlank()) queryType = "current";
            if (days == null || days <= 0) days = 3;
            if (days > 7) days = 7;

            // 离线数据只覆盖固定城市，防止没有坐标时生成误导性的结果。
            if (!CITY_COORDINATES.containsKey(city)) {
                return errorResult("暂不支持查询该城市，当前支持：" + String.join("、", CITY_COORDINATES.keySet()));
            }

            // forecast 走多日预测，其余值按当前天气处理，保证未知 queryType 仍有可用降级结果。
            String result = switch (queryType) {
                case "forecast" -> buildForecastResult(city, days);
                default -> buildCurrentResult(city);
            };

            log.info("MCP 工具调用完成, toolId={}, city={}, queryType={}, elapsed={}ms",
                    TOOL_ID, city, queryType, System.currentTimeMillis() - startMs);
            return successResult(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    /** 生成指定城市当天的天气摘要及必要的出行提示。 */
    private String buildCurrentResult(String city) {
        LocalDate today = LocalDate.now();
        WeatherData weather = generateWeatherForDate(city, today);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 今日天气】\n\n", city));
        sb.append(String.format("日期: %s\n", today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))));
        sb.append(String.format("天气: %s\n", weather.weatherType));
        sb.append(String.format("当前温度: %d°C\n", weather.currentTemp));
        sb.append(String.format("最高温度: %d°C\n", weather.highTemp));
        sb.append(String.format("最低温度: %d°C\n", weather.lowTemp));
        sb.append(String.format("相对湿度: %d%%\n", weather.humidity));
        sb.append(String.format("风向: %s\n", weather.windDirection));
        sb.append(String.format("风力: %s\n", weather.windLevel));
        sb.append(String.format("空气质量: %s\n", weather.airQuality));

        // 优先提示降水，其次提示极端高低温。
        if (weather.weatherType.contains("雨") || weather.weatherType.contains("雪")) {
            sb.append("\n提示: 今日有降水，出行请携带雨具。");
        } else if (weather.highTemp >= 35) {
            sb.append("\n提示: 今日高温，注意防暑降温。");
        } else if (weather.lowTemp <= 0) {
            sb.append("\n提示: 今日气温较低，注意防寒保暖。");
        }

        return sb.toString().trim();
    }

    /** 逐日生成未来天气，并在温差明显时补充升温或降温趋势。 */
    private String buildForecastResult(String city, int days) {
        LocalDate today = LocalDate.now();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 未来%d天天气预报】\n\n", city, days));

        // 从今天开始连续生成指定天数的预测快照。
        for (int d = 0; d < days; d++) {
            LocalDate date = today.plusDays(d);
            WeatherData weather = generateWeatherForDate(city, date);
            String dayLabel = d == 0 ? "今天" : d == 1 ? "明天" : d == 2 ? "后天" : date.format(DateTimeFormatter.ofPattern("MM月dd日"));

            sb.append(String.format("📅 %s（%s）\n", dayLabel, date.format(DateTimeFormatter.ofPattern("MM-dd"))));
            sb.append(String.format("   天气: %s | 温度: %d°C ~ %d°C\n", weather.weatherType, weather.lowTemp, weather.highTemp));
            sb.append(String.format("   湿度: %d%% | %s %s\n\n", weather.humidity, weather.windDirection, weather.windLevel));
        }

        WeatherData todayWeather = generateWeatherForDate(city, today);
        WeatherData lastDayWeather = generateWeatherForDate(city, today.plusDays(days - 1));
        // 仅在首尾最高温差达到 5 摄氏度时输出趋势，避免无意义噪声。
        int tempTrend = lastDayWeather.highTemp - todayWeather.highTemp;
        if (Math.abs(tempTrend) >= 5) {
            sb.append(String.format("趋势: 未来%d天气温%s，注意%s。",
                    days,
                    tempTrend > 0 ? "逐渐升高" : "逐渐下降",
                    tempTrend > 0 ? "防暑" : "保暖"));
        }

        return sb.toString().trim();
    }

    /**
     * 根据城市纬度、季节和固定随机种子生成天气数据。
     * 使用日期和城市作为种子，保证同一输入在多次调用中结果一致。
     */
    private WeatherData generateWeatherForDate(String city, LocalDate date) {
        double[] coords = CITY_COORDINATES.get(city);
        double latitude = coords[0];
        long seed = date.toEpochDay() * 31 + city.hashCode();
        Random random = new Random(seed);

        int month = date.getMonthValue();
        // 以 0 到 3 表示春夏秋冬，后续统一用于温度、湿度和天气候选池选择。
        int season = (month >= 3 && month <= 5) ? 0 : (month >= 6 && month <= 8) ? 1 : (month >= 9 && month <= 11) ? 2 : 3;

        // 纬度越高，基础温度越低；不同季节使用不同下降系数。
        double baseTemp = switch (season) {
            case 0 -> 15 - (latitude - 25) * 0.5;
            case 1 -> 30 - (latitude - 25) * 0.3;
            case 2 -> 18 - (latitude - 25) * 0.5;
            default -> 5 - (latitude - 25) * 0.8;
        };

        int highTemp = (int) (baseTemp + 3 + random.nextInt(6));
        int lowTemp = (int) (baseTemp - 3 - random.nextInt(5));
        int currentTemp = lowTemp + random.nextInt(Math.max(1, highTemp - lowTemp));

        // 按季节选取天气类型后再随机抽取，避免夏天出现小雪等明显失真情况。
        List<String> weatherTypes = switch (season) {
            case 0 -> WEATHER_TYPES_SPRING;
            case 1 -> WEATHER_TYPES_SUMMER;
            case 2 -> WEATHER_TYPES_AUTUMN;
            default -> WEATHER_TYPES_WINTER;
        };
        String weatherType = weatherTypes.get(random.nextInt(weatherTypes.size()));

        // 夏季湿度较高、冬季较低；雨雪天气再额外提高湿度。
        int humidity = switch (season) {
            case 1 -> 60 + random.nextInt(30);
            case 3 -> 20 + random.nextInt(30);
            default -> 40 + random.nextInt(30);
        };
        if (weatherType.contains("雨") || weatherType.contains("雪")) humidity = Math.min(95, humidity + 20);

        String[] directions = {"东风", "南风", "西风", "北风", "东南风", "西北风", "东北风", "西南风"};
        String windDirection = directions[random.nextInt(directions.length)];

        int windForce = 1 + random.nextInt(5);
        String windLevel = windForce + "-" + (windForce + 1) + "级";

        // 简化 AQI 模型：北方城市增加基础值，再映射为用户可读等级。
        int aqiBase = 30 + random.nextInt(120);
        if (latitude > 35) aqiBase += 20;
        String airQuality;
        if (aqiBase <= 50) airQuality = "优";
        else if (aqiBase <= 100) airQuality = "良";
        else if (aqiBase <= 150) airQuality = "轻度污染";
        else airQuality = "中度污染";

        // 将离散计算结果收敛成内部 DTO，供当前和预报两种格式化逻辑复用。
        WeatherData data = new WeatherData();
        data.weatherType = weatherType;
        data.currentTemp = currentTemp;
        data.highTemp = highTemp;
        data.lowTemp = lowTemp;
        data.humidity = humidity;
        data.windDirection = windDirection;
        data.windLevel = windLevel;
        data.airQuality = airQuality;
        return data;
    }

    /** 将 MCP 动态参数转换为字符串；不存在时保留 null 供上层应用默认值。 */
    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    /** 仅接受 JSON 数字类型的整数参数，其他类型视为未传入。 */
    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    /** 构造正常文本结果，供 Bootstrap 合并到 MCP 上下文。 */
    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    /** 构造协议级错误结果，让上游模型明确本次查询不可用。 */
    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    /** 天气模拟算法的内部数据载体，不直接暴露为 MCP 工具输出。 */
    private static class WeatherData {
        String weatherType;
        int currentTemp;
        int highTemp;
        int lowTemp;
        int humidity;
        String windDirection;
        String windLevel;
        String airQuality;
    }
}
