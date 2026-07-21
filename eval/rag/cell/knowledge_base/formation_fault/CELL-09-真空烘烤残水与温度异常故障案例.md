# 真空烘烤残水与温度异常故障案例

## 元数据

```json
{
  "factory_id": "GZ01",
  "workshop_id": "FORMATION",
  "line_id": "L01",
  "process_stage": "后段注液化成",
  "process_code": "baking",
  "equipment_type": "真空烘箱",
  "equipment_model": "OVEN-B1",
  "equipment_code": "GZ01-F01-L01-BAKE-OVEN-01",
  "subsystem": "真空、温度、残水",
  "doc_type": "case",
  "fault_code": "",
  "alarm_code": "",
  "fault_family": "mechanical,electrical,process",
  "quality_defect": "",
  "cell_model": "PHONE-4P45",
  "cell_form": "软包",
  "polarity": "公共",
  "revision": "V1.0",
  "effective_status": "有效",
  "confidential_level": "受控",
  "supplier": "示例供应商"
}
```

## 适用范围

适用于 `GZ01` 工厂、`FORMATION` 车间、`baking` 工序的 `真空烘箱`。设备对象为 `GZ01-F01-L01-BAKE-OVEN-01`，重点子系统：真空、温度、残水。

## 标准排查与处置

1. 先确认人员、设备、工艺和化学品安全边界；需要时停机、急停、隔离能源并执行 LOTO。
2. 记录报警代码、时间、产品型号、批次、当前参数和故障前发生的操作，不绕过安全联锁。
3. 按“供能/供料或真空 → 传感器与执行机构 → 机械与工装 → 受控工艺参数 → 软件通讯”的顺序排查。
4. 任何配方、化成制度、PLC 程序或受控参数变更必须走授权变更流程；禁止凭经验直接修改。
5. 修复后完成空载、首件/样件和质量确认；记录根因、备件、参数、停机时间及预防措施。

## 检索提示

可使用工序、设备型号、设备编号、子系统、报警码、故障族、产品型号和文档版本作为检索过滤条件。实时状态、报警、工单和库存必须经 MCP 查询，不能以本资料替代实时事实。
