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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * IDEA 演示用的手机锂电池电芯厂实时数据适配器。
 *
 * <p>它是 {@link EquipmentOperationsService} 的一个 profile 化实现。生产环境应以 MES、EAM、WMS、
 * SCADA 或 Historian 适配器替换本类，并在适配器内部按服务端身份范围再次校验组织权限。</p>
 */
public class CellFactoryEquipmentOperationsService implements EquipmentOperationsService {

    private final List<EquipmentModel> models = List.of(
            model("WINDER-W3", "高速卷绕机 W3", "卷绕机", "中段装配 L03 卷绕线", "ASSEMBLY", "ASSEMBLY-WIND", "双工位；张力闭环；EPC 纠偏；视觉对位", "WIND-201,WIND-318", "SP-WIND-TENSION-01,SP-WIND-EPC-01"),
            model("COATER-X1", "双面挤压涂布机 X1", "涂布机", "前段极片 L02 涂布线", "ELECTRODE", "ELECTRODE-COAT", "双面涂布；多区烘箱；供料闭环", "COAT-102,COAT-225", "SP-COAT-SLOT-01,SP-COAT-PUMP-01"),
            model("FILLER-F2", "真空注液机 F2", "注液机", "后段 L02 注液线", "FORMATION", "FORMATION-FILL", "质量流量计；真空注液；在线称重", "FILL-110,FILL-206", "SP-FILL-VALVE-01,SP-FILL-SEAL-01"),
            model("FORMATION-C8", "高压化成柜 C8", "化成柜", "后段 L03 化成线", "FORMATION", "FORMATION-FORM", "96 通道；高压互锁；温控夹具", "FORM-301,FORM-417", "SP-FORM-CLAMP-01,SP-FORM-CONTACT-01"),
            model("DRYROOM-D2", "干燥房除湿系统 D2", "除湿机", "公辅 L01 干燥房", "UTILITY", "UTILITY-DRYROOM", "露点 -45℃；转轮除湿；氮气联锁", "DRY-101,DRY-220", "SP-DRY-ROTOR-01,SP-DEW-SENSOR-01"),
            model("TESTER-Q3", "OCV/IR 自动测试机 Q3", "电性能测试机", "质量中心 L03 终检线", "QUALITY", "QUALITY-FQC", "OCV；内阻；耐压；条码追溯", "TEST-121,TEST-305", "SP-TEST-PROBE-01,SP-TEST-FIXTURE-01")
    );
    private final Map<String, EquipmentModel> modelsByCode = models.stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.model()), item -> item));
    private final Map<String, EquipmentStatus> statuses = List.of(
            status("GZ01-A01-L03-WIND-WINDER-02", "WINDER-W3", "ASSEMBLY", "ASSEMBLY-WIND", "报警待修", 7824, "WIND-318 张力波动且 EPC 到达纠偏极限", "2026-07-20 10:12"),
            status("GZ01-E01-L02-COAT-01", "COATER-X1", "ELECTRODE", "ELECTRODE-COAT", "运行中", 5360, "无", "2026-07-20 10:13"),
            status("GZ01-F01-L02-FILL-FILLER-01", "FILLER-F2", "FORMATION", "FORMATION-FILL", "降速运行", 4198, "FILL-206 真空保持时间超限", "2026-07-20 10:11"),
            status("GZ01-F01-L03-FORM-CABINET-08", "FORMATION-C8", "FORMATION", "FORMATION-FORM", "运行中", 9142, "无", "2026-07-20 10:13"),
            status("GZ01-U01-L01-DRY-DEHUMID-02", "DRYROOM-D2", "UTILITY", "UTILITY-DRYROOM", "预警", 12034, "DRY-101 露点接近预警阈值", "2026-07-20 10:10"),
            status("GZ01-Q01-L03-FQC-TESTER-01", "TESTER-Q3", "QUALITY", "QUALITY-FQC", "运行中", 3680, "无", "2026-07-20 10:13")
    ).stream().collect(Collectors.toUnmodifiableMap(item -> normalize(item.deviceId()), item -> item));
    private final Map<String, FaultCode> faults = List.of(
            fault("WIND-318", "张力波动与纠偏极限", "卷绕机", "中", "张力传感器零点漂移,收放卷制动响应滞后,EPC 边缘信号异常", "停止自动卷绕并保持安全状态,记录张力曲线和报警时刻,检查传感器/EPC/制动器后再复机"),
            fault("COAT-225", "涂布供料压力波动", "涂布机", "中", "浆料粘度波动,供料泵气蚀,过滤器压差升高", "停止继续放大涂布缺陷,确认供料与过滤状态,由工艺和设备人员联合确认后复机"),
            fault("FILL-206", "真空保持时间超限", "注液机", "高", "真空密封件泄漏,阀组响应异常,腔体残液污染", "暂停注液并隔离在制品,禁止带真空拆卸,确认泄漏点与残液清理后执行复验"),
            fault("FORM-417", "化成夹具温度偏差", "化成柜", "高", "夹具接触不良,温控回路异常,热电偶漂移", "停止受影响通道,隔离异常电芯,执行高压 LOTO 后由授权人员检查"),
            fault("DRY-101", "干燥房露点升高", "除湿机", "中", "除湿转轮性能下降,再生温度不足,门禁频繁开启", "核对露点趋势与再生参数,限制非必要进出,必要时按工艺规则暂停受影响工序"),
            fault("TEST-305", "OCV/IR 探针接触异常", "电性能测试机", "低", "探针污染,治具定位偏移,接触压力不足", "暂停自动判定,清洁探针并使用标准件复测,确认量测系统后恢复")
    ).stream().collect(Collectors.toUnmodifiableMap(item -> normalize(item.code()), item -> item));
    private final Map<String, MaintenanceWorkOrder> workOrders = List.of(
            workOrder("WO-CELL-20260720-001", "GZ01-A01-L03-WIND-WINDER-02", "WINDER-W3", "ASSEMBLY", "ASSEMBLY-WIND", "处理中", "设备工程师-王工", "2026-07-20 12:00", "检查张力传感器零点、EPC 边缘传感器和收放卷制动响应。"),
            workOrder("WO-CELL-20260720-002", "GZ01-F01-L02-FILL-FILLER-01", "FILLER-F2", "FORMATION", "FORMATION-FILL", "待备件", "设备技术员-李工", "2026-07-20 16:30", "更换真空阀密封件并完成真空保持与注液称重复验。"),
            workOrder("WO-CELL-20260720-003", "GZ01-U01-L01-DRY-DEHUMID-02", "DRYROOM-D2", "UTILITY", "UTILITY-DRYROOM", "处理中", "公辅工程师-陈工", "2026-07-20 14:00", "复核再生温度、转轮压差和露点仪标定状态。")
    ).stream().collect(Collectors.toUnmodifiableMap(item -> normalize(item.workOrderNo()), item -> item));
    private final Map<String, SparePartInventory> parts = List.of(
            part("SP-WIND-TENSION-01", "卷绕张力传感器", "ASSEMBLY", "ASSEMBLY-WIND", 2, "装配备件库 A-03-02", "WINDER-W3", "SP-WIND-TENSION-02"),
            part("SP-WIND-EPC-01", "卷绕 EPC 边缘传感器", "ASSEMBLY", "ASSEMBLY-WIND", 1, "装配备件库 A-03-05", "WINDER-W3", "无"),
            part("SP-FILL-VALVE-01", "注液真空阀组件", "FORMATION", "FORMATION-FILL", 3, "后段备件库 F-02-01", "FILLER-F2", "SP-FILL-VALVE-02"),
            part("SP-DRY-ROTOR-01", "除湿转轮密封组件", "UTILITY", "UTILITY-DRYROOM", 1, "公辅备件库 U-01-04", "DRYROOM-D2", "无")
    ).stream().collect(Collectors.toUnmodifiableMap(item -> normalize(item.partNo()), item -> item));
    private final List<MaintenancePlan> plans = List.of(
            plan("MP-WIND-W3", "WINDER-W3", "ASSEMBLY", "ASSEMBLY-WIND", "每运行 250 小时", "2026-07-23 08:00", "检查张力传感器,检查 EPC 纠偏行程,检查收放卷制动器"),
            plan("MP-FILL-F2", "FILLER-F2", "FORMATION", "FORMATION-FILL", "每运行 200 小时", "2026-07-22 08:00", "检查真空保持,清洁阀组,校验称重模块"),
            plan("MP-FORM-C8", "FORMATION-C8", "FORMATION", "FORMATION-FORM", "每运行 500 小时", "2026-07-26 08:00", "检查高压互锁,校验夹具温控,检查通道接触电阻"),
            plan("MP-DRY-D2", "DRYROOM-D2", "UTILITY", "UTILITY-DRYROOM", "每周", "2026-07-21 08:00", "检查露点趋势,检查再生温度,检查转轮压差")
    );

    @Override
    public List<EquipmentModel> findModels(String model, String category, String workshopId, String teamId) {
        return models.stream().filter(item -> matchesText(item.model(), model) || matchesText(item.name(), model))
                .filter(item -> blank(category) || normalize(item.category()).contains(normalize(category)))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId)).toList();
    }

    @Override
    public Optional<EquipmentStatus> findDeviceStatus(String deviceId, String workshopId, String teamId) {
        return Optional.ofNullable(statuses.get(normalize(deviceId))).filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<FaultCode> findFaultCode(String faultCode, String model) {
        return Optional.ofNullable(faults.get(normalize(faultCode))).filter(item -> blank(model) || modelMatches(model, item.applicableCategory()));
    }

    @Override
    public Optional<MaintenanceWorkOrder> findWorkOrder(String workOrderNo, String workshopId, String teamId) {
        return Optional.ofNullable(workOrders.get(normalize(workOrderNo))).filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<SparePartInventory> findSparePart(String partNo, String workshopId, String teamId) {
        return Optional.ofNullable(parts.get(normalize(partNo))).filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<MaintenancePlan> findMaintenancePlan(String model, String deviceId, String workshopId, String teamId) {
        String expectedModel = blank(model) ? Optional.ofNullable(statuses.get(normalize(deviceId))).map(EquipmentStatus::model).orElse("") : model;
        return plans.stream().filter(item -> blank(expectedModel) || normalize(item.model()).equals(normalize(expectedModel)))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId)).findFirst();
    }

    private boolean modelMatches(String model, String category) {
        return Optional.ofNullable(modelsByCode.get(normalize(model))).map(item -> normalize(item.category()).equals(normalize(category))).orElse(true);
    }

    private boolean matchesText(String value, String keyword) {
        return blank(keyword) || normalize(value).contains(normalize(keyword));
    }

    private boolean matchesScope(String itemWorkshop, String itemTeam, String workshop, String team) {
        return (blank(workshop) || normalize(itemWorkshop).equals(normalize(workshop))) && (blank(team) || normalize(itemTeam).equals(normalize(team)));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private EquipmentModel model(String model, String name, String category, String line, String workshop, String team, String specs, String faultCodes, String spareParts) {
        return new EquipmentModel(model, name, category, line, workshop, team, specs, List.of(faultCodes.split(",")), List.of(spareParts.split(",")));
    }

    private EquipmentStatus status(String deviceId, String model, String workshop, String team, String status, long hours, String alarm, String updatedAt) {
        return new EquipmentStatus(deviceId, model, workshop, team, status, hours, alarm, updatedAt);
    }

    private FaultCode fault(String code, String name, String category, String level, String causes, String actions) {
        return new FaultCode(code, name, category, level, List.of(causes.split(",")), List.of(actions.split(",")));
    }

    private MaintenanceWorkOrder workOrder(String number, String deviceId, String model, String workshop, String team, String status, String assignee, String eta, String summary) {
        return new MaintenanceWorkOrder(number, deviceId, model, workshop, team, status, assignee, eta, summary);
    }

    private SparePartInventory part(String number, String name, String workshop, String team, int quantity, String location, String models, String alternate) {
        return new SparePartInventory(number, name, workshop, team, quantity, location, List.of(models.split(",")), alternate);
    }

    private MaintenancePlan plan(String id, String model, String workshop, String team, String cycle, String nextTime, String checklist) {
        return new MaintenancePlan(id, model, workshop, team, cycle, nextTime, List.of(checklist.split(",")));
    }
}
