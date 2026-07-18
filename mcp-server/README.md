# 清居智能模拟商城 MCP

`mcp-server` 通过 Streamable HTTP 在 `http://localhost:9099/mcp` 暴露 MCP 工具。Bootstrap 已默认配置该地址，启动时会调用 `listTools` 自动发现并注册工具。

清居智能模块使用内存模拟数据：50 个商品 SKU、12 个订单及对应的履约、库存、配件兼容信息。数据查询接口与内存实现分离，后续接入真实商城 API 时只需新增 `QingjuStoreQueryService` 的实现。

工具列表：

- `qingju_order_query`：查询订单、支付、商品明细和售后状态。
- `qingju_logistics_query`：查询履约、承运商、运单号和物流节点。
- `qingju_inventory_query`：查询 SKU 的型号、售价和库存。
- `qingju_accessory_compatibility`：查询主机型号与配件 SKU 是否兼容。

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

要让 RAG 问答实际调用清居工具，请在意图树后台新增 `kind=MCP` 的叶子节点，并将 `mcpToolId` 分别配置为上述工具名。商品说明、保修政策等静态资料仍应进入知识库；订单、库存、物流等状态通过 MCP 查询。

新增高德地图或其他外部 MCP 时，新增一个独立的 `@Component` 工具提供者并暴露 `SyncToolSpecification` Bean 即可。`McpServerConfig` 会自动收集它，无需修改现有清居工具或注册配置；外部 API Key 应放在本地私有配置中，不写入仓库。
