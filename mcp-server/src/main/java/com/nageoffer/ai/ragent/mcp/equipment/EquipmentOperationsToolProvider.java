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

package com.nageoffer.ai.ragent.mcp.equipment;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class EquipmentOperationsToolProvider {

    private static final String DEVICE_MODEL_QUERY = "device_model_query";
    private static final String DEVICE_STATUS_QUERY = "device_status_query";
    private static final String FAULT_CODE_QUERY = "fault_code_query";
    private static final String WORK_ORDER_QUERY = "work_order_query";
    private static final String SPARE_PART_INVENTORY_QUERY = "spare_part_inventory_query";
    private static final String MAINTENANCE_PLAN_QUERY = "maintenance_plan_query";

    private final EquipmentOperationsService equipmentOperationsService;

    @Bean
    public McpServerFeatures.SyncToolSpecification deviceModelQueryToolSpecification() {
        return specification(deviceModelTool(), this::handleDeviceModelQuery);
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification deviceStatusQueryToolSpecification() {
        return specification(deviceStatusTool(), this::handleDeviceStatusQuery);
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification faultCodeQueryToolSpecification() {
        return specification(faultCodeTool(), this::handleFaultCodeQuery);
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification workOrderQueryToolSpecification() {
        return specification(workOrderTool(), this::handleWorkOrderQuery);
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification sparePartInventoryQueryToolSpecification() {
        return specification(sparePartInventoryTool(), this::handleSparePartInventoryQuery);
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification maintenancePlanQueryToolSpecification() {
        return specification(maintenancePlanTool(), this::handleMaintenancePlanQuery);
    }

    private McpServerFeatures.SyncToolSpecification specification(Tool tool, ToolHandler handler) {
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> handler.handle(request));
    }

    private Tool deviceModelTool() {
        return tool(DEVICE_MODEL_QUERY,
                "查询设备型号、技术参数和适用产线。型号或设备类别至少提供一个；可附带 workshopId、teamId 限定组织范围。",
                schema(Map.of(
                        "model", stringProperty("设备型号，例如 CNC-MT-1060 或 ROB-IR-20"),
                        "category", stringProperty("设备类别，例如 数控机床、工业机器人、变频输送线"),
                        "workshopId", stringProperty("车间编码，例如 MACHINING 或 ASSEMBLY"),
                        "teamId", stringProperty("班组编码，例如 TEAM-M1 或 TEAM-A")
                ), List.of()));
    }

    private Tool deviceStatusTool() {
        return tool(DEVICE_STATUS_QUERY,
                "查询指定设备的实时状态、累计运行时长和当前告警。deviceId 必填；组织范围参数用于校验数据归属。",
                schema(Map.of(
                        "deviceId", stringProperty("设备实例编码，例如 CNC-02、ROB-02 或 CONV-02"),
                        "workshopId", stringProperty("车间编码，可选"),
                        "teamId", stringProperty("班组编码，可选")
                ), List.of("deviceId")));
    }

    private Tool faultCodeTool() {
        return tool(FAULT_CODE_QUERY,
                "查询故障码的可能原因、危险等级和立即处置动作。faultCode 必填；可提供 model 做适用范围校验。",
                schema(Map.of(
                        "faultCode", stringProperty("故障码，例如 CNC-203、ROB-302 或 CONV-101"),
                        "model", stringProperty("设备型号，可选，例如 CNC-MT-1060")
                ), List.of("faultCode")));
    }

    private Tool workOrderTool() {
        return tool(WORK_ORDER_QUERY,
                "查询维修工单的状态、处理人、预计完成时间和处理摘要。workOrderNo 必填。",
                schema(Map.of(
                        "workOrderNo", stringProperty("工单号，例如 WO-20260719-001"),
                        "workshopId", stringProperty("车间编码，可选"),
                        "teamId", stringProperty("班组编码，可选")
                ), List.of("workOrderNo")));
    }

    private Tool sparePartInventoryTool() {
        return tool(SPARE_PART_INVENTORY_QUERY,
                "查询备件可用库存、库位、适配型号和替代型号。partNo 必填。",
                schema(Map.of(
                        "partNo", stringProperty("备件编码，例如 SP-CNC-SERVO-01 或 SP-CONV-VFD-02"),
                        "workshopId", stringProperty("车间编码，可选"),
                        "teamId", stringProperty("班组编码，可选")
                ), List.of("partNo")));
    }

    private Tool maintenancePlanTool() {
        return tool(MAINTENANCE_PLAN_QUERY,
                "查询设备保养周期、下次保养时间和点检清单。model 或 deviceId 至少提供一个。",
                schema(Map.of(
                        "model", stringProperty("设备型号，例如 ROB-IR-20"),
                        "deviceId", stringProperty("设备实例编码，例如 ROB-02"),
                        "workshopId", stringProperty("车间编码，可选"),
                        "teamId", stringProperty("班组编码，可选")
                ), List.of()));
    }

    private CallToolResult handleDeviceModelQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        String model = stringArg(arguments, "model");
        String category = stringArg(arguments, "category");
        if (isBlank(model) && isBlank(category)) {
            return error("请提供设备型号或设备类别，例如 model=CNC-MT-1060 或 category=数控机床。");
        }
        List<EquipmentModel> models = equipmentOperationsService.findModels(model, category,
                stringArg(arguments, "workshopId"), stringArg(arguments, "teamId"));
        if (models.isEmpty()) {
            return error("未找到匹配的设备型号，请核对型号、类别和组织范围。");
        }
        String text = models.stream().map(item -> "【设备型号】\n"
                        + "型号：" + item.model() + "\n"
                        + "名称：" + item.name() + "\n"
                        + "类别：" + item.category() + "\n"
                        + "适用产线：" + item.applicableLine() + "\n"
                        + "组织范围：" + item.workshopId() + " / " + item.teamId() + "\n"
                        + "关键参数：" + item.keySpecifications() + "\n"
                        + "关联故障码：" + String.join("、", item.supportedFaultCodes()) + "\n"
                        + "推荐备件：" + String.join("、", item.recommendedSpareParts()))
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        return success(text);
    }

    private CallToolResult handleDeviceStatusQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        return findOrError(equipmentOperationsService.findDeviceStatus(stringArg(arguments, "deviceId"),
                        stringArg(arguments, "workshopId"), stringArg(arguments, "teamId")),
                item -> "【设备实时状态】\n"
                        + "设备：" + item.deviceId() + "（" + item.model() + "）\n"
                        + "组织范围：" + item.workshopId() + " / " + item.teamId() + "\n"
                        + "状态：" + item.status() + "\n"
                        + "累计运行时长：" + item.runningHours() + " 小时\n"
                        + "当前告警：" + item.currentAlarm() + "\n"
                        + "数据更新时间：" + item.lastUpdatedAt(),
                "未查询到该设备状态，请核对 deviceId 和组织范围。");
    }

    private CallToolResult handleFaultCodeQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        return findOrError(equipmentOperationsService.findFaultCode(stringArg(arguments, "faultCode"), stringArg(arguments, "model")),
                item -> "【故障码诊断】\n"
                        + "故障码：" + item.code() + "\n"
                        + "名称：" + item.name() + "\n"
                        + "适用设备：" + item.applicableCategory() + "\n"
                        + "危险等级：" + item.dangerLevel() + "\n"
                        + "可能原因：" + String.join("；", item.possibleCauses()) + "\n"
                        + "立即处置：" + String.join("；", item.immediateActions()),
                "未查询到匹配的故障码，或该故障码不适用于指定设备型号。");
    }

    private CallToolResult handleWorkOrderQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        return findOrError(equipmentOperationsService.findWorkOrder(stringArg(arguments, "workOrderNo"),
                        stringArg(arguments, "workshopId"), stringArg(arguments, "teamId")),
                item -> "【维修工单】\n"
                        + "工单号：" + item.workOrderNo() + "\n"
                        + "设备：" + item.deviceId() + "（" + item.model() + "）\n"
                        + "组织范围：" + item.workshopId() + " / " + item.teamId() + "\n"
                        + "状态：" + item.status() + "\n"
                        + "处理人：" + item.assignee() + "\n"
                        + "预计完成时间：" + item.estimatedCompletionAt() + "\n"
                        + "处理摘要：" + item.summary(),
                "未查询到该维修工单，请核对工单号和组织范围。");
    }

    private CallToolResult handleSparePartInventoryQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        return findOrError(equipmentOperationsService.findSparePart(stringArg(arguments, "partNo"),
                        stringArg(arguments, "workshopId"), stringArg(arguments, "teamId")),
                item -> "【备件库存】\n"
                        + "备件：" + item.name() + "\n"
                        + "备件编码：" + item.partNo() + "\n"
                        + "组织范围：" + item.workshopId() + " / " + item.teamId() + "\n"
                        + "可用库存：" + item.availableQuantity() + "\n"
                        + "库位：" + item.location() + "\n"
                        + "适配型号：" + String.join("、", item.compatibleModels()) + "\n"
                        + "替代型号：" + item.alternativePartNo(),
                "未查询到该备件库存，请核对备件编码和组织范围。");
    }

    private CallToolResult handleMaintenancePlanQuery(CallToolRequest request) {
        Map<String, Object> arguments = arguments(request);
        String model = stringArg(arguments, "model");
        String deviceId = stringArg(arguments, "deviceId");
        if (isBlank(model) && isBlank(deviceId)) {
            return error("请提供设备型号或设备实例编码，例如 model=ROB-IR-20 或 deviceId=ROB-02。");
        }
        return findOrError(equipmentOperationsService.findMaintenancePlan(model, deviceId,
                        stringArg(arguments, "workshopId"), stringArg(arguments, "teamId")),
                item -> "【保养计划】\n"
                        + "计划编号：" + item.planId() + "\n"
                        + "设备型号：" + item.model() + "\n"
                        + "组织范围：" + item.workshopId() + " / " + item.teamId() + "\n"
                        + "保养周期：" + item.cycle() + "\n"
                        + "下次保养时间：" + item.nextMaintenanceAt() + "\n"
                        + "点检清单：" + String.join("；", item.checklist()),
                "未查询到该设备的保养计划，请核对型号、设备实例和组织范围。");
    }

    private <T> CallToolResult findOrError(Optional<T> value, Function<T, String> formatter, String errorMessage) {
        return value.map(formatter).map(this::success).orElseGet(() -> error(errorMessage));
    }

    private Tool tool(String name, String description, JsonSchema schema) {
        return Tool.builder().name(name).description(description).inputSchema(schema).build();
    }

    private JsonSchema schema(Map<String, ? extends Object> properties, List<String> required) {
        return new JsonSchema("object", new LinkedHashMap<>(properties), required, null, null, null);
    }

    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    private String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private CallToolResult success(String text) {
        return CallToolResult.builder().content(List.of(new TextContent(text))).isError(false).build();
    }

    private CallToolResult error(String message) {
        return CallToolResult.builder().content(List.of(new TextContent(message))).isError(true).build();
    }

    @FunctionalInterface
    private interface ToolHandler {
        CallToolResult handle(CallToolRequest request);
    }
}
