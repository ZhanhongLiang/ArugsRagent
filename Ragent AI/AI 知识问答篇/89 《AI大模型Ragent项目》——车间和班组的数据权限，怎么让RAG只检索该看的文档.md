# 《AI大模型Ragent项目》——车间和班组的数据权限，怎么让 RAG 只检索该看的文档

## 开篇引言

前面的知识问答链路已经能完成问题改写、意图识别、多通道检索、重排序和流式回答。但真实的设备运维场景里，另一个问题会立刻出现：**同一句故障问题，不同车间、不同班组能看到的文档并不相同。**

例如，装配一车间的操作员问“变频器过温怎么处理”，不能把涂装二车间的工艺参数、点检记录和维修手册一起检索出来；班组长能看到本车间所有班组资料，普通员工可能只能看到自己班组的 SOP。

如果只在前端隐藏知识库卡片，或者只在 `Sa-Token` 里放一个 `role=user`，这不是数据权限：用户仍然可以直接调用文档预览接口、猜测文档 ID 下载源文件，或者通过全局向量检索把不该看的 Chunk 捞出来。

这一篇实现一个最小可用版本（MVP）：**管理员维护车间、班组、用户数据范围和知识资源 ACL；普通用户只检索与读取已经授权的知识库和文档；管理员保留全量旁路。**

## 1. 先把“功能权限”和“数据权限”分开

很多人一开始会把“车间一”“班组二”写进 Sa-Token 权限字符串：

```text
knowledge:workshop:assembly-1
knowledge:team:assembly-1-a
```

这样短期看起来能跑，但很快会失控：一个用户可能跨多个车间、一个文档也可能授权给多个班组，权限字符串数量会组合爆炸；更重要的是，最终向量 SQL 仍然需要把这些字符串重新解释为可访问文档，检索层和认证层被强耦合。

所以本项目拆成两层：

| 层 | 解决的问题 | 当前实现 |
| --- | --- | --- |
| 功能权限 | 能不能进入某个管理功能 | Sa-Token `getPermissionList()`；管理员有 `knowledge:manage`、`knowledge:access:manage` |
| 数据权限 | 进入后到底能看到哪些数据 | `KnowledgeAccessService` 解析车间、班组和资源 ACL |

`admin` 是功能管理员，也是数据范围管理员，直接拿到全量 `KnowledgeAccessScope`；`user` 只有 `knowledge:read`。普通用户访问 `WORKSHOP`、`TEAM` 资源时必须命中自身组织范围；访问 `GLOBAL` 资源时只要求已登录，不绑定具体车间或班组。

`KnowledgeAccessService` 对知识库、文档写操作检查 `knowledge:manage`，对车间、班组、用户范围和 ACL 配置检查 `knowledge:access:manage`。当前只有 `admin` 拥有这两项权限；以后增加“知识库运营员”角色时，只需在 Sa-Token 权限映射中新增 `knowledge:manage`，不需要改检索或 ACL 代码。

这就是一个很重要的安全原则：**“能调用查询接口”不等于“能看到所有查询结果”。**

## 2. MVP 的四张表

先看最小数据模型：

```text
t_workshop                    车间
        │ 1 : N
t_workshop_team               班组

t_user_data_scope             用户被授予的车间/班组范围

t_knowledge_resource_scope    知识库/文档向哪些范围开放
```

### 2.1 车间与班组

`t_workshop` 是一级组织，`t_workshop_team` 是二级组织：

```sql
CREATE TABLE t_workshop_team (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    workshop_id VARCHAR(20)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    enabled     SMALLINT     NOT NULL DEFAULT 1
);
```

班组必须带 `workshop_id`，所以后续无论是配置用户范围还是资源范围，后端都能校验“这个班组是否真的属于这个车间”。不能相信前端同时传过来的两个 ID。

### 2.2 用户数据范围：`t_user_data_scope`

用户范围只有两种：

```java
public enum DataScopeType {
    WORKSHOP, // 能访问指定车间的所有班组
    TEAM      // 只能访问指定班组
}
```

例如用户 `u-1001` 有两条记录：

| user_id | scope_type | workshop_id | team_id |
| --- | --- | --- | --- |
| u-1001 | WORKSHOP | workshop-a | null |
| u-1001 | TEAM | workshop-b | team-b-02 |

这表示他能访问车间 A 全部资料，以及车间 B 的二班资料。不是“用户只能有一个车间和一个班组”，而是允许多条范围记录；这也是不要把范围塞进 `t_user` 单个字段的原因。

### 2.3 资源 ACL：`t_knowledge_resource_scope`

资源范围又分为三种：

```java
public enum KnowledgeResourceScopeType {
    GLOBAL,
    WORKSHOP,
    TEAM
}
```

`resource_type` 为 `KNOWLEDGE_BASE` 或 `DOCUMENT`。知识库 ACL 是默认范围，文档 ACL 是更细的覆盖范围。

举一个实际例子：

| resource_type | resource_id | scope_type | workshop_id | team_id |
| --- | --- | --- | --- | --- |
| KNOWLEDGE_BASE | kb-device | WORKSHOP | workshop-a | null |
| DOCUMENT | doc-shutdown | TEAM | workshop-a | team-a-03 |

`kb-device` 里的普通文档对 A 车间开放；但 `doc-shutdown` 是停机工艺卡，单独配置成仅 A 车间三班可见。**文档上存在 ACL 时优先使用文档 ACL；文档没有 ACL 才继承知识库 ACL。**

这个“覆盖而不是简单叠加”很关键：如果把文档 ACL 和知识库 ACL 一律 OR 起来，管理员永远无法收紧某一份敏感文档。

## 3. MVP 为什么默认拒绝

旧项目里的历史知识库没有车间和班组标签。如果上线数据权限后把“没有 ACL”解释成“全员可见”，等于在迁移期间自动公开所有历史资料；这是最危险的默认值。

本 MVP 的约定是：

```text
管理员：全量可见
普通用户：资源必须配置 ACL；`GLOBAL` 对所有已登录用户可见，`WORKSHOP` / `TEAM` 必须命中用户组织范围
资源没有 ACL：普通用户不可见
```

因此升级后管理员需要显式给每个知识库配置 `GLOBAL`、`WORKSHOP` 或 `TEAM` 范围。`resources/database/upgrade_v1.1_to_v1.2.sql` 只建表，不会偷偷给历史数据插入 `GLOBAL` ACL。

这会让首次迁移多一步配置工作，但能保证安全边界是“默认关闭”而不是“默认敞开”。

## 4. 统一入口：`KnowledgeAccessService`

权限判断没有散落在 Controller、RAG Engine 和 SQL 字符串里，而是先收敛为一个值对象：

```java
public record KnowledgeAccessScope(
        boolean unrestricted,
        Set<String> readableKnowledgeBaseIds,
        Set<String> readableCollectionNames,
        Set<String> readableDocumentIds
) {
}
```

`KnowledgeAccessService.resolveAccessScope(userId, role)` 的核心流程是：

```text
当前用户
  │
  ├─ admin？──是──> allAccess()
  │
  └─ 否
       │
       ├─ 查 t_user_data_scope，得到车间范围和班组范围
       ├─ 查知识库、文档及 t_knowledge_resource_scope
       ├─ 对每份文档选“文档 ACL 优先，否则知识库 ACL”
       └─ 同时命中用户范围与资源 ACL，加入 readableDocumentIds
```

它对外只暴露：

```java
KnowledgeAccessScope currentAccessScope();
void requireReadableKnowledgeBase(String knowledgeBaseId);
void requireReadableDocument(String documentId);
void requireManageKnowledgeBase(String knowledgeBaseId);
void requireManageDocument(String documentId);
```

这样 Controller 不需要知道车间表、班组表和 ACL 表如何关联；检索通道也不需要知道用户角色来自 Sa-Token 还是未来的外部 IAM。这就是开闭原则在这里的落点：**新增“项目组”或“租户”时扩展范围解析器，不改所有检索通道。**

## 5. 一次 SSE 问答如何透传权限快照

RAG 问答里存在多个线程池：意图分类、子问题上下文、检索通道和 Collection 并发检索都可能在不同线程执行。即使项目用了 TTL 透传 `UserContext`，也不应该让每个子线程都重新查一次 ACL。

`StreamChatPipeline.execute()` 在入口处计算一次：

```java
ctx.setKnowledgeAccessScope(knowledgeAccessService.currentAccessScope());
```

之后同一份 `KnowledgeAccessScope` 依次传给：

```text
StreamChatContext
  -> IntentResolver.resolve(rewriteResult, accessScope)
  -> DefaultIntentClassifier.classifyTargets(question, accessScope)
  -> RetrievalEngine.retrieve(subIntents, topK, accessScope)
  -> MultiChannelRetrievalEngine.retrieveKnowledgeChannels(..., accessScope)
  -> RetrieveRequest.accessScope
```

这样做有两个好处：

- 同一次问答的多个子问题不会出现前半段按旧 ACL、后半段按新 ACL 的不一致。
- 解析权限只做一次，避免多子问题并发时重复查组织与 ACL 表。

权限变更对下一次问答生效；不会打断已经拿到快照并正在流式回答的请求。这是读取一致性和在线体验之间的合理取舍。

## 6. 三道检索防线

只在最终 SQL 加过滤并不够，既浪费调用，也可能让未授权知识库参与意图分类；只在前面过滤更不够，未来新增一个通道就可能忘记接入。MVP 采用三道防线。

### 6.1 意图树防线：不把未授权节点发给 LLM

`DefaultIntentClassifier` 原本把全部叶子节点的名称、描述和示例拼入 Prompt。现在先过滤：

```java
return leafNodes.stream()
        .filter(node -> !node.isKB()
                || accessScope.canReadCollection(node.getCollectionName()))
        .toList();
```

SYSTEM 和 MCP 节点仍保留；KB 节点只有其 collection 可读才会加入 Prompt。即使模型幻觉返回了一个未展示节点 ID，解析结果也只会从 `candidateNodeById` 取，最终被丢弃。

### 6.2 通道防线：不调度未授权集合

全局通道原来会把 `t_knowledge_base` 中全部 collection 拿出来并发检索：

```java
List<String> collections = getAllKBCollections(context.getAccessScope());
```

`getAllKBCollections` 现在只保留 `accessScope.canReadCollection(collectionName)` 的集合；意图定向通道也会再次过滤命中的 KB 意图。

这层是性能保护：没有权限的 Collection 连 Embedding 后的检索任务都不会创建。

### 6.3 最终 SQL 防线：只返回允许的 `doc_id`

真正的安全收口在 `PgRetrieverService`：

```sql
SELECT id, content, 1 - (embedding <=> ?::vector) AS score
FROM t_knowledge_vector
WHERE metadata->>'collection_name' = ?
  AND metadata->>'doc_id' IN (?, ?, ...)
ORDER BY embedding <=> ?::vector
LIMIT ?
```

`readableDocumentIds` 为空时，Java 在发 SQL 前直接返回空列表；collection 不可读也同样短路。即使未来某个新通道遗漏了上游过滤，只要它构造 `RetrieveRequest.accessScope`，最终向量查询仍然越不过文档边界。

项目仍保留 Milvus 实现，因此 `MilvusRetrieverService` 也使用：

```text
metadata["doc_id"] in ["doc-a", "doc-b"]
```

同一份 `RetrieveRequest.accessScope` 让两个后端的语义一致。

## 7. 管理接口如何配置

MVP 先提供本地后端接口，前端权限管理页可以后续独立新增，不影响 RAG 主链：

| 操作 | 接口 |
| --- | --- |
| 创建/查询车间 | `POST` / `GET /knowledge-access/workshops` |
| 创建/查询某车间的班组 | `POST` / `GET /knowledge-access/workshops/{workshop-id}/teams` |
| 覆盖某用户的数据范围 | `PUT /knowledge-access/users/{user-id}/scopes` |
| 覆盖知识库或文档 ACL | `PUT /knowledge-access/{resource-type}/{resource-id}/scopes` |

例如给用户只授予 A 车间二班：

```json
{
  "scopes": [
    {
      "scopeType": "TEAM",
      "workshopId": "workshop-a",
      "teamId": "team-a-02"
    }
  ]
}
```

给某文档只授权 A 车间二班：

```json
{
  "scopes": [
    {
      "scopeType": "TEAM",
      "workshopId": "workshop-a",
      "teamId": "team-a-02"
    }
  ]
}
```

`PUT` 的语义是整体覆盖；传入 `{ "scopes": [] }` 就是撤销全部范围。后端会校验班组存在、启用且确实属于传入的车间。

车间或班组被停用后，已有范围记录不会被删除，但 `KnowledgeAccessService` 解析快照时只保留启用组织单元。因此停用会立即阻断该组织范围的普通用户读取；重新启用后原有配置可恢复，无需重复配置 ACL。

## 8. 管理接口和原文件下载同样要过滤

检索过滤之后，以下接口如果不加控制仍然可以泄露数据：

- `GET /knowledge-base/docs/{docId}` 文档详情
- `GET /knowledge-base/docs/{docId}/preview` Markdown 原文
- `GET /knowledge-base/docs/{docId}/file` S3 源文件
- 文档分页、全局文档搜索、Chunk 分页和分块日志

所以 `KnowledgeDocumentServiceImpl` 的读取方法统一调用 `requireReadableDocument` 或在分页 SQL 中按 `readableDocumentIds` 加 `IN`。编辑、删除、启停、重新分块和手工编辑 Chunk 则调用 `requireManageDocument`；MVP 中仅 `admin` 可做这些管理操作。

这叫**横向授权校验**：不能因为请求路径合法、文档 ID 存在，就默认请求人有权访问。

## 9. 和原始 Sa-Token 的关系

原项目的：

```java
public List<String> getPermissionList(Object loginId, String loginType) {
    return Collections.emptyList();
}
```

现在改为返回稳定的功能权限：

```text
admin -> knowledge:read, knowledge:manage, knowledge:access:manage, user:manage
user  -> knowledge:read
```

但车间、班组、知识库和文档范围仍在数据库 ACL 中实时解析。不要做成：

```text
permission = knowledge:team:team-a-02
```

因为这会让权限缓存、范围变更、资源多授权、SQL 过滤与审计都变得难以维护。

## 10. MVP 的边界与下一步

这版先解决静态知识文档的两级组织权限，明确不做以下扩展：

- 不做前端权限管理页面；现阶段使用本地管理接口配置即可。
- 不把 ACL 写进 Redis；当前 ACL 变更频率低，优先保证正确性。
- 不做 PostgreSQL Row Level Security；应用层已经将权限收口到 `KnowledgeAccessService` 与最终向量 SQL，后续有跨服务直连数据库的需求再增加 RLS。
- 不自动将历史资料设为全局可读；迁移后由管理员显式配置。
- 不处理 MCP 实时业务数据的车间过滤；下一阶段应把 `userId` 和 `KnowledgeAccessScope` 传给工具服务，再由订单、工单、设备查询 SQL 自己执行范围过滤。

后续如果文档量上升到百万级，`readableDocumentIds IN (...)` 可能过长，可以扩展为在向量 SQL 中通过 `EXISTS` 关联 ACL 表，或将权限投影到向量 metadata 并建立对应索引。但在当前知识库规模下，先让授权语义正确、链路完整，比过早引入复杂索引更重要。

## 总结

车间 + 班组数据权限不是在某个 Controller 上加一个 `if`，而是一条贯穿意图、检索、管理读取和源文件下载的链路：

```text
Sa-Token 功能权限
      +
用户车间/班组范围
      +
知识库/文档 ACL
      ↓
KnowledgeAccessScope（一次问答的快照）
      ↓
意图 Prompt 过滤 → 检索通道过滤 → 向量 SQL doc_id 过滤
      ↓
只把当前用户有权看到的 Chunk 交给 LLM
```

三道检索防线负责性能和安全，管理端横向鉴权封住原文件和文档 ID 旁路，文档 ACL 覆盖知识库 ACL 则保证敏感资料可以从大范围内进一步收紧。这才是设备运维 RAG 场景里完整的数据权限闭环。
