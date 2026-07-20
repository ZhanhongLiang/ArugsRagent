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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class InMemoryEquipmentOperationsService implements EquipmentOperationsService {

    private static final String MACHINING_WORKSHOP = "MACHINING";
    private static final String ASSEMBLY_WORKSHOP = "ASSEMBLY";

    private final List<EquipmentModel> models = buildModels();
    private final Map<String, EquipmentModel> modelsByCode = models.stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.model()), item -> item));
    private final Map<String, EquipmentStatus> statusesByDeviceId = buildStatuses().stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.deviceId()), item -> item));
    private final Map<String, FaultCode> faultsByCode = buildFaultCodes().stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.code()), item -> item));
    private final Map<String, MaintenanceWorkOrder> workOrdersByNo = buildWorkOrders().stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.workOrderNo()), item -> item));
    private final Map<String, SparePartInventory> partsByNo = buildSpareParts().stream()
            .collect(Collectors.toUnmodifiableMap(item -> normalize(item.partNo()), item -> item));
    private final List<MaintenancePlan> maintenancePlans = buildMaintenancePlans();

    @Override
    public List<EquipmentModel> findModels(String model, String category, String workshopId, String teamId) {
        String modelKeyword = normalize(model);
        String categoryKeyword = normalize(category);
        return models.stream()
                .filter(item -> modelKeyword.isEmpty()
                        || normalize(item.model()).contains(modelKeyword)
                        || normalize(item.name()).contains(modelKeyword))
                .filter(item -> categoryKeyword.isEmpty()
                        || normalize(item.category()).contains(categoryKeyword))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId))
                .toList();
    }

    @Override
    public Optional<EquipmentStatus> findDeviceStatus(String deviceId, String workshopId, String teamId) {
        return Optional.ofNullable(statusesByDeviceId.get(normalize(deviceId)))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<FaultCode> findFaultCode(String faultCode, String model) {
        return Optional.ofNullable(faultsByCode.get(normalize(faultCode)))
                .filter(item -> model == null || model.isBlank() || modelMatchesCategory(model, item.applicableCategory()));
    }

    @Override
    public Optional<MaintenanceWorkOrder> findWorkOrder(String workOrderNo, String workshopId, String teamId) {
        return Optional.ofNullable(workOrdersByNo.get(normalize(workOrderNo)))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<SparePartInventory> findSparePart(String partNo, String workshopId, String teamId) {
        return Optional.ofNullable(partsByNo.get(normalize(partNo)))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId));
    }

    @Override
    public Optional<MaintenancePlan> findMaintenancePlan(String model, String deviceId, String workshopId, String teamId) {
        String resolvedModel = normalize(model);
        if (resolvedModel.isEmpty() && !normalize(deviceId).isEmpty()) {
            EquipmentStatus status = statusesByDeviceId.get(normalize(deviceId));
            resolvedModel = status == null ? "" : normalize(status.model());
        }
        String expectedModel = resolvedModel;
        return maintenancePlans.stream()
                .filter(item -> expectedModel.isEmpty() || normalize(item.model()).equals(expectedModel))
                .filter(item -> matchesScope(item.workshopId(), item.teamId(), workshopId, teamId))
                .findFirst();
    }

    private boolean modelMatchesCategory(String model, String category) {
        EquipmentModel equipmentModel = modelsByCode.get(normalize(model));
        return equipmentModel == null || normalize(equipmentModel.category()).equals(normalize(category));
    }

    private boolean matchesScope(String itemWorkshopId, String itemTeamId, String workshopId, String teamId) {
        return (normalize(workshopId).isEmpty() || normalize(itemWorkshopId).equals(normalize(workshopId)))
                && (normalize(teamId).isEmpty() || normalize(itemTeamId).equals(normalize(teamId)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private List<EquipmentModel> buildModels() {
        List<EquipmentModel> result = new ArrayList<>();
        result.add(model("CNC-MT-850", "立式数控加工中心 MT-850", "数控机床", "壳体精加工线", MACHINING_WORKSHOP, "TEAM-M1", "行程 800x500x500 mm；主轴 12000 rpm；定位精度 ±0.008 mm", "CNC-101,CNC-203", "SP-CNC-SPINDLE-01,SP-CNC-SERVO-01"));
        result.add(model("CNC-MT-1060", "立式数控加工中心 MT-1060", "数控机床", "阀体与支架加工线", MACHINING_WORKSHOP, "TEAM-M1", "行程 1000x600x600 mm；主轴 10000 rpm；刀库 24 把", "CNC-101,CNC-203", "SP-CNC-SPINDLE-01,SP-CNC-TOOLMAG-01"));
        result.add(model("CNC-MT-1270", "重载数控加工中心 MT-1270", "数控机床", "大型底座加工线", MACHINING_WORKSHOP, "TEAM-M2", "行程 1200x700x700 mm；主轴 8000 rpm；最大工件 1200 kg", "CNC-101,CNC-305", "SP-CNC-HYD-01,SP-CNC-SERVO-02"));
        result.add(model("CNC-LT-500", "数控车削中心 LT-500", "数控机床", "轴类车削线", MACHINING_WORKSHOP, "TEAM-M2", "最大回转直径 500 mm；主轴 4500 rpm；12 工位刀塔", "CNC-203,CNC-305", "SP-CNC-CHUCK-01,SP-CNC-SERVO-02"));
        result.add(model("CNC-GR-630", "数控外圆磨床 GR-630", "数控机床", "精密磨削线", MACHINING_WORKSHOP, "TEAM-M1", "最大磨削直径 320 mm；砂轮线速度 45 m/s；圆度 0.003 mm", "CNC-101,CNC-407", "SP-CNC-WHEEL-01,SP-CNC-COOLANT-01"));
        result.add(model("ROB-IR-10", "六轴工业机器人 IR-10", "工业机器人", "小型部件上下料线", ASSEMBLY_WORKSHOP, "TEAM-A", "额定负载 10 kg；臂展 1420 mm；重复定位 ±0.03 mm", "ROB-201,ROB-302", "SP-ROB-REDUCER-01,SP-ROB-CABLE-01"));
        result.add(model("ROB-IR-20", "六轴工业机器人 IR-20", "工业机器人", "焊接与搬运线", ASSEMBLY_WORKSHOP, "TEAM-A", "额定负载 20 kg；臂展 1710 mm；防护等级 IP67", "ROB-201,ROB-302", "SP-ROB-REDUCER-02,SP-ROB-SERVO-01"));
        result.add(model("ROB-WL-12", "弧焊工业机器人 WL-12", "工业机器人", "结构件焊接线", ASSEMBLY_WORKSHOP, "TEAM-B", "额定负载 12 kg；焊接电源 500 A；臂展 1468 mm", "ROB-302,ROB-404", "SP-ROB-TORCH-01,SP-ROB-CABLE-02"));
        result.add(model("ROB-PAL-25", "码垛工业机器人 PAL-25", "工业机器人", "成品码垛线", ASSEMBLY_WORKSHOP, "TEAM-B", "额定负载 25 kg；节拍 10 次/分钟；最大垛高 1800 mm", "ROB-201,ROB-503", "SP-ROB-GRIPPER-01,SP-ROB-REDUCER-03"));
        result.add(model("ROB-SC-04", "SCARA 装配机器人 SC-04", "工业机器人", "精密装配线", ASSEMBLY_WORKSHOP, "TEAM-A", "额定负载 4 kg；水平臂长 600 mm；重复定位 ±0.02 mm", "ROB-201,ROB-302", "SP-ROB-BELT-01,SP-ROB-SERVO-01"));
        result.add(model("CONV-VF-200", "变频皮带输送线 VF-200", "变频输送线", "机加工物流输送线", MACHINING_WORKSHOP, "TEAM-M1", "线体宽度 200 mm；速度 3-18 m/min；变频器 1.5 kW", "CONV-101,CONV-204", "SP-CONV-BELT-01,SP-CONV-VFD-01"));
        result.add(model("CONV-VF-300", "变频皮带输送线 VF-300", "变频输送线", "装配物流输送线", ASSEMBLY_WORKSHOP, "TEAM-A", "线体宽度 300 mm；速度 3-20 m/min；变频器 2.2 kW", "CONV-101,CONV-204", "SP-CONV-BELT-02,SP-CONV-VFD-02"));
        result.add(model("CONV-VF-450", "变频滚筒输送线 VF-450", "变频输送线", "重载周转线", MACHINING_WORKSHOP, "TEAM-M2", "线体宽度 450 mm；额定载荷 80 kg/m；变频器 3.0 kW", "CONV-101,CONV-305", "SP-CONV-ROLLER-01,SP-CONV-VFD-04"));
        result.add(model("CONV-AS-600", "自动分拣输送线 AS-600", "变频输送线", "成品分拣线", ASSEMBLY_WORKSHOP, "TEAM-B", "线体宽度 600 mm；分拣能力 1800 件/小时；光电检测 6 点", "CONV-204,CONV-305", "SP-CONV-SENSOR-01,SP-CONV-BELT-03"));
        result.add(model("CONV-AS-800", "自动装箱输送线 AS-800", "变频输送线", "包装装箱线", ASSEMBLY_WORKSHOP, "TEAM-B", "线体宽度 800 mm；额定载荷 120 kg/m；变频器 5.5 kW", "CONV-101,CONV-305", "SP-CONV-ROLLER-02,SP-CONV-VFD-03"));
        return List.copyOf(result);
    }

    private EquipmentModel model(String model, String name, String category, String line, String workshopId,
                                 String teamId, String specifications, String faultCodes, String spareParts) {
        return new EquipmentModel(model, name, category, line, workshopId, teamId, specifications,
                List.of(faultCodes.split(",")), List.of(spareParts.split(",")));
    }

    private List<EquipmentStatus> buildStatuses() {
        return List.of(
                status("CNC-01", "CNC-MT-850", MACHINING_WORKSHOP, "TEAM-M1", "运行中", 4182, "无", "2026-07-19 08:30"),
                status("CNC-02", "CNC-MT-1060", MACHINING_WORKSHOP, "TEAM-M1", "告警停机", 6350, "CNC-203 伺服跟随误差超限", "2026-07-19 08:27"),
                status("CNC-03", "CNC-MT-1270", MACHINING_WORKSHOP, "TEAM-M2", "保养中", 8921, "无", "2026-07-19 07:55"),
                status("CNC-04", "CNC-LT-500", MACHINING_WORKSHOP, "TEAM-M2", "运行中", 2765, "无", "2026-07-19 08:29"),
                status("ROB-01", "ROB-IR-10", ASSEMBLY_WORKSHOP, "TEAM-A", "运行中", 5211, "无", "2026-07-19 08:30"),
                status("ROB-02", "ROB-IR-20", ASSEMBLY_WORKSHOP, "TEAM-A", "待维修", 7384, "ROB-302 碰撞检测触发", "2026-07-19 08:22"),
                status("ROB-03", "ROB-WL-12", ASSEMBLY_WORKSHOP, "TEAM-B", "运行中", 3640, "无", "2026-07-19 08:28"),
                status("CONV-01", "CONV-VF-200", MACHINING_WORKSHOP, "TEAM-M1", "运行中", 6190, "无", "2026-07-19 08:30"),
                status("CONV-02", "CONV-VF-300", ASSEMBLY_WORKSHOP, "TEAM-A", "降速运行", 4481, "CONV-204 光电传感器脏污", "2026-07-19 08:25"),
                status("CONV-03", "CONV-AS-600", ASSEMBLY_WORKSHOP, "TEAM-B", "运行中", 2335, "无", "2026-07-19 08:29")
        );
    }

    private EquipmentStatus status(String deviceId, String model, String workshopId, String teamId, String status,
                                   long runningHours, String currentAlarm, String lastUpdatedAt) {
        return new EquipmentStatus(deviceId, model, workshopId, teamId, status, runningHours, currentAlarm, lastUpdatedAt);
    }

    private List<FaultCode> buildFaultCodes() {
        return List.of(
                fault("CNC-101", "主轴温升异常", "数控机床", "高", "润滑不足,冷却液流量不足,主轴轴承磨损", "立即停机并隔离,检查润滑与冷却回路,由维修人员检测主轴温度"),
                fault("CNC-203", "伺服跟随误差超限", "数控机床", "中", "编码器接触不良,丝杠阻力增大,伺服参数漂移", "暂停自动运行,记录轴号与报警时刻,检查机械卡滞后再复位"),
                fault("CNC-305", "液压压力不足", "数控机床", "中", "液压油液位低,滤芯堵塞,泵站泄漏", "禁止继续夹紧加工,检查油位和泄漏点,更换滤芯后试运行"),
                fault("CNC-407", "冷却液浓度异常", "数控机床", "低", "补液比例错误,循环槽污染,浓度传感器失准", "暂停补液,按工艺卡复测浓度,清理循环槽"),
                fault("ROB-201", "伺服驱动过载", "工业机器人", "高", "末端负载超限,减速机阻力异常,运动轨迹干涉", "按急停流程停机,确认人员离开防护区,由工程师检查负载与轨迹"),
                fault("ROB-302", "碰撞检测触发", "工业机器人", "高", "夹具偏移,工件定位异常,示教点漂移", "保持急停并锁定单元,禁止手动强制复位,检查碰撞面和夹具"),
                fault("ROB-404", "焊接电流不稳定", "工业机器人", "中", "焊丝送进阻塞,接地不良,焊接电源参数异常", "停止焊接,检查焊丝与接地,复核工艺参数"),
                fault("ROB-503", "夹爪真空不足", "工业机器人", "中", "吸盘破损,真空管泄漏,真空发生器堵塞", "暂停码垛,检查吸盘和管路,确认工件不会坠落"),
                fault("CONV-101", "变频器过流", "变频输送线", "高", "输送带卡滞,电机轴承损坏,加速时间过短", "立即停止线体,断开上游供料,排除机械卡滞后再试车"),
                fault("CONV-204", "光电传感器信号丢失", "变频输送线", "低", "镜面脏污,安装位置偏移,接线松动", "停止自动分拣,清洁镜面并检查接线,进行单件测试"),
                fault("CONV-305", "滚筒速度偏差", "变频输送线", "中", "链条松弛,滚筒轴承磨损,编码器异常", "切换人工旁路前确认安全,检查传动链与编码器,完成试运行记录")
        );
    }

    private FaultCode fault(String code, String name, String category, String level, String causes, String actions) {
        return new FaultCode(code, name, category, level, List.of(causes.split(",")), List.of(actions.split(",")));
    }

    private List<MaintenanceWorkOrder> buildWorkOrders() {
        return List.of(
                workOrder("WO-20260719-001", "CNC-02", "CNC-MT-1060", MACHINING_WORKSHOP, "TEAM-M1", "处理中", "李工", "2026-07-19 11:30", "处理 Y 轴伺服跟随误差，待完成编码器线缆检查。"),
                workOrder("WO-20260719-002", "ROB-02", "ROB-IR-20", ASSEMBLY_WORKSHOP, "TEAM-A", "待备件", "王工", "2026-07-19 15:00", "机器人碰撞后需更换末端线缆并复核示教点。"),
                workOrder("WO-20260718-006", "CNC-03", "CNC-MT-1270", MACHINING_WORKSHOP, "TEAM-M2", "保养中", "周工", "2026-07-19 10:00", "执行 500 小时液压与导轨保养计划。"),
                workOrder("WO-20260718-009", "CONV-02", "CONV-VF-300", ASSEMBLY_WORKSHOP, "TEAM-A", "处理中", "陈工", "2026-07-19 09:40", "清洁光电传感器并复测自动分拣信号。"),
                workOrder("WO-20260717-011", "ROB-03", "ROB-WL-12", ASSEMBLY_WORKSHOP, "TEAM-B", "已完成", "赵工", "2026-07-18 16:20", "焊接送丝机构清洁完成，已通过三件试焊验证。")
        );
    }

    private MaintenanceWorkOrder workOrder(String no, String deviceId, String model, String workshopId, String teamId,
                                            String status, String assignee, String eta, String summary) {
        return new MaintenanceWorkOrder(no, deviceId, model, workshopId, teamId, status, assignee, eta, summary);
    }

    private List<SparePartInventory> buildSpareParts() {
        return List.of(
                part("SP-CNC-SPINDLE-01", "CNC 主轴轴承组件", MACHINING_WORKSHOP, "TEAM-M1", 3, "机加备件库 A-01-03", "CNC-MT-850,CNC-MT-1060", "SP-CNC-SPINDLE-02"),
                part("SP-CNC-SERVO-01", "CNC 伺服电机 1.5kW", MACHINING_WORKSHOP, "TEAM-M1", 2, "机加备件库 A-02-01", "CNC-MT-850,CNC-MT-1060", "SP-CNC-SERVO-02"),
                part("SP-CNC-TOOLMAG-01", "加工中心刀库换刀臂组件", MACHINING_WORKSHOP, "TEAM-M1", 2, "机加备件库 A-02-04", "CNC-MT-1060", "SP-CNC-TOOLMAG-02"),
                part("SP-CNC-HYD-01", "液压滤芯组件", MACHINING_WORKSHOP, "TEAM-M2", 8, "机加备件库 A-03-04", "CNC-MT-1270,CNC-LT-500", "无"),
                part("SP-CNC-SERVO-02", "CNC 伺服电机 3.0kW", MACHINING_WORKSHOP, "TEAM-M2", 2, "机加备件库 A-03-02", "CNC-MT-1270,CNC-LT-500", "SP-CNC-SERVO-01"),
                part("SP-CNC-CHUCK-01", "数控车床液压卡盘密封组", MACHINING_WORKSHOP, "TEAM-M2", 6, "机加备件库 A-03-07", "CNC-LT-500", "SP-CNC-CHUCK-02"),
                part("SP-CNC-WHEEL-01", "外圆磨床砂轮法兰组件", MACHINING_WORKSHOP, "TEAM-M1", 4, "机加备件库 A-01-08", "CNC-GR-630", "SP-CNC-WHEEL-02"),
                part("SP-CNC-COOLANT-01", "磨床冷却液浓度传感器", MACHINING_WORKSHOP, "TEAM-M1", 5, "机加备件库 A-01-10", "CNC-GR-630", "SP-CNC-COOLANT-02"),
                part("SP-ROB-REDUCER-02", "机器人 J2 减速机", ASSEMBLY_WORKSHOP, "TEAM-A", 1, "装配备件库 B-01-02", "ROB-IR-20,ROB-PAL-25", "SP-ROB-REDUCER-01"),
                part("SP-ROB-CABLE-02", "焊接机器人线缆包", ASSEMBLY_WORKSHOP, "TEAM-B", 4, "装配备件库 B-02-03", "ROB-WL-12", "无"),
                part("SP-ROB-REDUCER-01", "机器人 J2 减速机基础型", ASSEMBLY_WORKSHOP, "TEAM-A", 3, "装配备件库 B-01-01", "ROB-IR-10", "SP-ROB-REDUCER-02"),
                part("SP-ROB-CABLE-01", "六轴机器人末端线缆包", ASSEMBLY_WORKSHOP, "TEAM-A", 4, "装配备件库 B-01-04", "ROB-IR-10", "SP-ROB-CABLE-02"),
                part("SP-ROB-SERVO-01", "机器人关节伺服电机", ASSEMBLY_WORKSHOP, "TEAM-A", 2, "装配备件库 B-01-05", "ROB-IR-20,ROB-SC-04", "SP-ROB-SERVO-02"),
                part("SP-ROB-TORCH-01", "焊接机器人焊枪组件", ASSEMBLY_WORKSHOP, "TEAM-B", 3, "装配备件库 B-02-05", "ROB-WL-12", "SP-ROB-TORCH-02"),
                part("SP-ROB-GRIPPER-01", "码垛机器人真空夹爪", ASSEMBLY_WORKSHOP, "TEAM-B", 2, "装配备件库 B-02-06", "ROB-PAL-25", "SP-ROB-GRIPPER-02"),
                part("SP-ROB-REDUCER-03", "码垛机器人 J2 减速机", ASSEMBLY_WORKSHOP, "TEAM-B", 2, "装配备件库 B-02-07", "ROB-PAL-25", "SP-ROB-REDUCER-02"),
                part("SP-ROB-BELT-01", "SCARA 同步带套件", ASSEMBLY_WORKSHOP, "TEAM-A", 7, "装配备件库 B-01-08", "ROB-SC-04", "SP-ROB-BELT-02"),
                part("SP-CONV-VFD-02", "输送线变频器 2.2kW", ASSEMBLY_WORKSHOP, "TEAM-A", 5, "装配备件库 B-03-01", "CONV-VF-300,CONV-VF-450", "SP-CONV-VFD-03"),
                part("SP-CONV-BELT-01", "输送线耐磨皮带 200mm", MACHINING_WORKSHOP, "TEAM-M1", 9, "机加备件库 A-04-02", "CONV-VF-200", "SP-CONV-BELT-02"),
                part("SP-CONV-VFD-01", "输送线变频器 1.5kW", MACHINING_WORKSHOP, "TEAM-M1", 6, "机加备件库 A-04-04", "CONV-VF-200", "SP-CONV-VFD-02"),
                part("SP-CONV-BELT-02", "输送线耐磨皮带 300mm", ASSEMBLY_WORKSHOP, "TEAM-A", 8, "装配备件库 B-03-02", "CONV-VF-300", "SP-CONV-BELT-03"),
                part("SP-CONV-ROLLER-01", "重载输送线滚筒组件", MACHINING_WORKSHOP, "TEAM-M2", 5, "机加备件库 A-04-07", "CONV-VF-450", "SP-CONV-ROLLER-02"),
                part("SP-CONV-VFD-04", "重载输送线变频器 3.0kW", MACHINING_WORKSHOP, "TEAM-M2", 3, "机加备件库 A-04-08", "CONV-VF-450", "SP-CONV-VFD-02"),
                part("SP-CONV-SENSOR-01", "漫反射光电传感器", ASSEMBLY_WORKSHOP, "TEAM-B", 12, "装配备件库 B-03-06", "CONV-AS-600", "SP-CONV-SENSOR-02"),
                part("SP-CONV-BELT-03", "分拣线模块化皮带", ASSEMBLY_WORKSHOP, "TEAM-B", 7, "装配备件库 B-03-07", "CONV-AS-600", "SP-CONV-BELT-02"),
                part("SP-CONV-ROLLER-02", "装箱线包胶滚筒", ASSEMBLY_WORKSHOP, "TEAM-B", 6, "装配备件库 B-03-09", "CONV-AS-800", "SP-CONV-ROLLER-03"),
                part("SP-CONV-VFD-03", "输送线变频器 5.5kW", ASSEMBLY_WORKSHOP, "TEAM-B", 2, "装配备件库 B-03-10", "CONV-AS-800", "SP-CONV-VFD-02")
        );
    }

    private SparePartInventory part(String partNo, String name, String workshopId, String teamId, int quantity,
                                    String location, String compatibleModels, String alternativePartNo) {
        return new SparePartInventory(partNo, name, workshopId, teamId, quantity, location,
                List.of(compatibleModels.split(",")), alternativePartNo);
    }

    private List<MaintenancePlan> buildMaintenancePlans() {
        return List.of(
                plan("MP-CNC-850", "CNC-MT-850", MACHINING_WORKSHOP, "TEAM-M1", "每运行 250 小时", "2026-07-28 08:00", "检查主轴温升,检查导轨润滑,清理切屑过滤器"),
                plan("MP-CNC-1060", "CNC-MT-1060", MACHINING_WORKSHOP, "TEAM-M1", "每运行 250 小时", "2026-07-24 08:00", "检查伺服报警记录,检查丝杠防护罩,校验冷却液浓度"),
                plan("MP-CNC-1270", "CNC-MT-1270", MACHINING_WORKSHOP, "TEAM-M2", "每运行 500 小时", "2026-07-19 13:00", "更换液压滤芯,检查夹具压力,检查导轨润滑"),
                plan("MP-CNC-LT500", "CNC-LT-500", MACHINING_WORKSHOP, "TEAM-M2", "每运行 500 小时", "2026-07-29 08:00", "检查卡盘夹紧力,检查主轴润滑,清理排屑机"),
                plan("MP-CNC-GR630", "CNC-GR-630", MACHINING_WORKSHOP, "TEAM-M1", "每运行 300 小时", "2026-07-31 08:00", "检查砂轮法兰,校验冷却液浓度,检查磨削防护罩"),
                plan("MP-ROB-IR10", "ROB-IR-10", ASSEMBLY_WORKSHOP, "TEAM-A", "每运行 500 小时", "2026-08-02 08:00", "检查减速机油脂,检查安全围栏,校验零位"),
                plan("MP-ROB-IR20", "ROB-IR-20", ASSEMBLY_WORKSHOP, "TEAM-A", "每运行 500 小时", "2026-07-22 08:00", "检查末端线缆,检查碰撞检测阈值,复核示教点"),
                plan("MP-ROB-WL12", "ROB-WL-12", ASSEMBLY_WORKSHOP, "TEAM-B", "每运行 250 小时", "2026-07-26 08:00", "清洁送丝机构,检查焊枪绝缘,检查接地回路"),
                plan("MP-ROB-PAL25", "ROB-PAL-25", ASSEMBLY_WORKSHOP, "TEAM-B", "每运行 500 小时", "2026-08-05 08:00", "检查真空夹爪密封,检查码垛轨迹,校验安全光栅"),
                plan("MP-ROB-SC04", "ROB-SC-04", ASSEMBLY_WORKSHOP, "TEAM-A", "每运行 300 小时", "2026-07-27 08:00", "检查同步带张力,校验定位精度,清洁治具定位面"),
                plan("MP-CONV-200", "CONV-VF-200", MACHINING_WORKSHOP, "TEAM-M1", "每运行 300 小时", "2026-07-30 08:00", "检查皮带张力,检查变频器散热,清洁光电传感器"),
                plan("MP-CONV-300", "CONV-VF-300", ASSEMBLY_WORKSHOP, "TEAM-A", "每运行 300 小时", "2026-07-21 08:00", "检查皮带跑偏,检查传感器镜面,检查电机接线"),
                plan("MP-CONV-450", "CONV-VF-450", MACHINING_WORKSHOP, "TEAM-M2", "每运行 300 小时", "2026-07-25 08:00", "检查滚筒轴承,检查链条张紧度,检查变频器参数"),
                plan("MP-CONV-600", "CONV-AS-600", ASSEMBLY_WORKSHOP, "TEAM-B", "每运行 300 小时", "2026-08-01 08:00", "检查分拣气缸,清洁传感器,检查滚筒链条"),
                plan("MP-CONV-800", "CONV-AS-800", ASSEMBLY_WORKSHOP, "TEAM-B", "每运行 300 小时", "2026-08-04 08:00", "检查装箱滚筒,检查皮带张力,检查光电互锁")
        );
    }

    private MaintenancePlan plan(String planId, String model, String workshopId, String teamId, String cycle,
                                 String nextMaintenanceAt, String checklist) {
        return new MaintenancePlan(planId, model, workshopId, teamId, cycle, nextMaintenanceAt, List.of(checklist.split(",")));
    }
}
