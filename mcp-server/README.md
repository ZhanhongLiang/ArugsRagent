# 设备运维 MCP

`mcp-server` 通过 Streamable HTTP 在 `http://localhost:9099/mcp` 暴露 MCP 工具。Bootstrap 在启动时调用 `tools/list` 自动发现并注册工具。

设备运维模块使用内存模拟数据，覆盖数控机床、工业机器人和变频输送线共 15 个型号，以及设备状态、故障码、工单、备件和保养计划。`EquipmentOperationsToolProvider` 只依赖 `EquipmentOperationsService` 接口；接入 MES、EAM、ERP 或工单系统时新增该接口的实现即可，无需修改 MCP 工具协议层。

工具列表：

- `device_model_query`：查询设备型号、技术参数和适用产线。
- `device_status_query`：查询设备实时状态、运行时长和告警。
- `fault_code_query`：查询故障码、可能原因、危险等级和立即处置。
- `work_order_query`：查询维修工单状态、处理人和预计完成时间。
- `spare_part_inventory_query`：查询备件库存、库位、适配型号和替代型号。
- `maintenance_plan_query`：查询保养周期、下次保养时间和点检清单。

高德地图工具：

- `amap_geocode`：查询地址经纬度。
- `amap_driving_route`：查询驾车距离、时长和关键路线步骤。
- `amap_weather_query`：查询城市实时天气。

高德 Key 只从环境变量读取，不写入仓库：

```powershell
$env:AMAP_WEB_SERVICE_KEY = "你的高德 Web 服务 Key"
```

启动命令：

```powershell
.\mvnw.cmd -pl mcp-server spring-boot:run
```

要让 RAG 问答实际调用设备工具，请在意图树后台新增 `kind=MCP` 的叶子节点，并将 `mcpToolId` 配置为上述工具名。型号手册、点检 SOP、维修案例和安全规范进入知识库；设备状态、工单和库存等实时信息通过 MCP 查询。

新增高德地图或其他外部 MCP 时，新增一个独立的 `@Component` 工具提供者并暴露 `SyncToolSpecification` Bean 即可。`McpServerConfig` 会自动收集它，无需修改现有设备工具或注册配置；外部 API Key 应放在本地私有配置中，不写入仓库。
