---
name: knowledge-curator
description: 读取管理员勾选的待处理 Markdown 草稿，检索平台已发布知识，识别重复、冲突、过期和缺口，并生成唯一的版本化合并草稿与 Diff，供管理员多轮调整和人工发布。
---

# 合并整理待处理草稿

## 流程

1. 调用 `selected_draft_list`，再逐份调用 `selected_draft_read`。将这些固定快照作为本任务的原始输入，不要修改或替换它们。
2. 识别主题、业务规则、关键术语、适用范围和建议目录。
3. 按需调用 `knowledge_directory_list` 和 `knowledge_document_list`。对含义相近的内容调用 `knowledge_search`；对具体术语、字段、数值或规则调用 `knowledge_grep`。
4. 在判定重复、冲突或过期前，调用 `knowledge_document_read` 读取相关已发布文档全文。不要仅根据标题或搜索片段下结论。
5. 对重复、冲突、过期和缺口调用 `finding_record`，记录双方结论、范围、证据、建议和待人工问题。
6. 一个会话只创建一份合并输出草稿。尚无输出时调用一次 `draft_create`；已有输出时继续使用它，不要新建第二份。只有在修订某一份已发布正式知识时，才把 `knowledge_document_list`、`knowledge_search` 或 `knowledge_document_read` 返回的文档 ID 作为 `baselineDocumentId`；全新主题必须省略该参数或传 `0`。待处理草稿文档 ID 不能作为正式知识基线。
7. 修改前调用 `draft_read` 取得最新修订和服务端区块 ID，再用带基础修订号、幂等键和来源的 `draft_update` 提交结构化区块操作。`blocks=[]` 的空草稿首次写入必须使用 `INSERT_AFTER` 并让 `targetBlockId` 为空；其他操作的区块 ID 必须逐字复制 `draft_read` 返回值，不能使用 Markdown 标题或自造 ID。Tool 返回修订冲突或操作无效时，重新读取最新修订并修正操作后重试。
8. 调用 `draft_diff` 核对已提交修订。最终消息只总结实际记录的发现项、草稿修订、未决问题和建议审核重点。

## 判断与安全

- 只根据勾选草稿、当前项目授权的已发布文档和管理员消息整理内部事实。
- 有明确来源时合并重复表达、统一术语并补充适用范围。
- 无法确定权威方、时间范围、地区或客户范围时，保留待人工问题，不自行选择结论。
- 不将最终对话消息当作草稿正文，不请求全文覆盖、Shell、任意文件、任意 HTTP 或数据库管理能力。
- 不修改、归档或发布正式文档。发布必须由管理员在任务页审核 Diff 后执行。
