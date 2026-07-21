# 意图节点备份说明

`legacy_intent_nodes_before_final_cleanup.raw` 保存了最终清理前导出的 95 个非 `CELL_*` 旧意图节点原始记录。原历史数据包含已损坏的文本编码，数据库 JSON 文本导出不能保证作为标准 JSON 再解析，因此该文件按原始导出保存，不能直接作为 JSON 导入。

当前运行库只保留 122 个 `CELL_*` 节点（7 个 DOMAIN、26 个 CATEGORY、89 个 TOPIC）。如需恢复旧树，应先在隔离库中修复原始字段编码和 JSON 转义，再按 `intent_code`、`parent_code` 从根到叶恢复，避免覆盖电芯意图树。
