# 手机锂电池电芯厂 RAG 场景资产

本目录是可重复执行的电芯厂初始化包，不会启动 Docker，也不创建后台服务。Bootstrap 与 MCP Server 均应由 IDEA 启动。

## 组织与权限

- 5 个车间：前段极片制造、中段装配、后段注液化成、质量检测、公辅动力。
- 16 个班组：见 `organization_spec.json`；产线、设备编号、设备型号属于文档 metadata，不创建为意图节点。
- 用户范围使用现有 `WORKSHOP` / `TEAM` 数据范围；资源 ACL 使用 `GLOBAL` / `WORKSHOP` / `TEAM`。
- 文档 ACL 优先于知识库 ACL。工艺配方、软件备份、质量根因和供应商资料按车间/班组受控；EHS 应急资料可设 GLOBAL。
- `access_assignment_spec.json` 给出角色模板和仅对已存在用户生效的示例绑定。脚本不会创建账号或密码。

## 路由模型

7 个 DOMAIN 严格按单一路由类型分组：前 5 个是 KB，实时生产与运维数据是 MCP，系统交互与人工升级是 SYSTEM。复合问题由现有问题拆分能力拆成多个子问题，而不是在一个 DOMAIN 内混合 KB/MCP/SYSTEM。

故障 TOPIC 的主库为对应故障案例库。`application-cell-factory.yml` 为高价值故障补充设备手册、维修标准或自动化库；补充集合仍通过 `KnowledgeAccessScope` 校验。默认应用配置以可选 classpath 资源加载同内容的 `application-cell-factory.yaml`，未命中 `CELL_*` 意图时保持原有单库路由。

## 元数据契约

每份示例资料包含工厂、车间、产线、工序、设备、型号、子系统、文档类型、故障族、产品型号、版本、有效性和密级等字段。当前内核已将 `metadataJson` 写入向量 metadata，并在 Pgvector 与 Milvus 中将 `RetrieveRequest.metadataFilters` 和 collection/document ACL 以 AND 关系强制过滤。

## 初始化

先从 IDEA 重启 Bootstrap，使补充集合配置扩展生效；如需 MCP，请也由 IDEA 启动 `mcp-server`。不要执行 Docker Compose。

```powershell
$env:RAG_EVAL_ROOT = (Resolve-Path "eval/rag/cell").Path
$env:RAGENT_BASE_URL = "http://localhost:9090/api/ragent"
python eval/rag/cell/init/generate_cell_factory_assets.py
python eval/rag/cell/init/validate_cell_factory_assets.py
python eval/rag/init/create_kbs.py
python eval/rag/init/upload_docs.py --sleep 1
python eval/rag/init/build_intent_tree.py
python eval/rag/cell/init/configure_cell_access.py
```

无需修改包含本地密钥的 `application-local.yaml`：`application.yaml` 已可选加载 `application-cell-factory.yaml`，该配置只匹配电芯 `CELL_*` 意图代码。

若要演示已有测试账号的用户范围绑定，显式执行：

```powershell
python eval/rag/cell/init/configure_cell_access.py --assign-samples
```

MCP 的演示实现当前按调用参数的 `workshopId/teamId` 过滤，尚未携带 Bootstrap 登录态；生产接入 MES/EAM/WMS 时必须在 `EquipmentOperationsService` 实现中使用服务端身份范围重新校验，不能信任模型生成的组织参数。

## Metadata 回填

`metadataJson` 在上传、重新分块、手工修改分块和重新启用文档时都会进入向量 metadata。Pgvector 与 Milvus 将 `RetrieveRequest.metadataFilters` 和既有 collection/document ACL 以 AND 关系强制过滤；电芯 profile 为部分故障 TOPIC 提供工序过滤示例，未启用 profile 时保持原有按知识库检索行为。

修改已有文档的 `metadataJson` 后，应执行一次“分块”以原子替换现有向量；这与现有文档处理配置的更新语义一致，避免管理接口在 HTTP 请求中同步触发耗时的 embedding 调用。

`bootstrap/src/main/resources/application.yaml` 已以可选资源方式加载 `application-cell-factory.yaml`；配置只对 `CELL_*` 意图代码生效，因此无需修改包含本地密钥的 `application-local.yaml`，也无需启动 Docker。

升级已有数据库时，先执行 `resources/database/upgrade_v1.3_to_v1.4.sql`，从 IDEA 重启 Bootstrap 后再回填已上传的电芯资料：

```powershell
$env:RAG_EVAL_ROOT = (Resolve-Path "eval/rag/cell").Path
python eval/rag/cell/init/apply_cell_metadata.py
```
