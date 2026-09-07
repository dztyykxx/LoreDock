# Tasks: user-memory

实现顺序遵循「契约/接口 → 失败测试 → 最小实现 → 验证」。每个行为只保留有业务目的的代表性测试，不堆数量；所有测试用例必须带中文注释说明业务目的，验证步骤须输出可核验证据。

## 1. 契约与数据模型

- [x] 1.1 新增 Flyway 迁移 `V10__add_user_memory.sql`：单表 `user_memory`（scope_type/category/title/summary/content/source 溯源/status/use_count/审计字段），完整性 CHECK（scope 与 project_id 一致性、枚举与长度、时间先后）与 COMMENT 齐全，与 `knowledge_task_conversation` 同款约束风格
- [x] 1.2 定义 `memory` 模块与跨模块契约：`memory/api/MemoryService.java`（listRelevant/loadFull/acceptWrite/管理操作）+ 不可变类型与枚举（MemoryScope/MemoryCategory/MemoryStatus/MemorySourceType），中文 Javadoc 说明失败语义；`Memory → Project(api)` 依赖
- [x] 1.3 实现持久化层：`UserMemoryEntity`（`@TableName`+`@TableField` 显式列名）+ `UserMemoryMapper`（BaseMapper + 预筛/列表查询）

## 2. 行为 A：检索与摘要预载（GLOBAL∪本项目、全文匹配、有界）

- [x] 2.1 失败测试（先行）：① 检索/预载不返回其他项目记忆（范围隔离，防跨范围泄漏）；② 标题命中排序高于正文命中（确定性打分）；③ 命中超 30 条时预载只返回前 30 条摘要且每条 ≤300 码点；④ 无命中时兜底最近使用的高频记忆且不超过 3 条
- [x] 2.2 实现确定性打分器与预载查询：SQL 预筛（scope+status+ILIKE，候选 ≤100）+ Java 打分（CJK 二元组/空白分词；标题×3/摘要×2/正文×1 + log2(use_count+1)；同分 last_used_at DESC, id DESC）+ 无命中兜底；查询词由调用方提供，参数有界；配置项（预载上限、摘要长度）进配置
- [x] 2.3 验证：真实 PostgreSQL 集成跑通上述用例并输出证据日志（命中范围、数量、排序）

## 3. 行为 B：全文按需加载

- [x] 3.1 失败测试（先行）：⑤ 加载其他项目记忆被拒答（越权不返回正文、use_count 不变）；⑥ 合法加载后 use_count+1、last_used_at 刷新
- [x] 3.2 实现全文加载：范围校验（GLOBAL∪指定项目）+ 有界正文返回 + 频次更新
- [x] 3.3 验证：集成跑通越权拒答与计数用例

## 4. 行为 C：写入判断（新增 / 重复 / 增量 / 冲突 / 预算）

- [x] 4.1 失败测试与代表性回归：语义重复跳过、停用不复活、一次性指令拒写、项目范围写入、人工版本历史和增量原地更新。
- [x] 4.2 实现 `memory_write` 判断链：候选校验 → 相近既有记忆召回 → 结构化关系/动作判断 → 完整正文合并、历史与版本写入；判断失败不得静默写入。
- [ ] 4.3 补齐验证：真实模型新增→增量→重复链路、明确更正与未决冲突、同批冲突撤销、写入前 stale 重检与短事务边界。

## 5. 行为 D：REST 管理接口（权限与错误语义）

- [x] 5.1 失败测试（先行）：⑫ 非管理员调用创建/编辑/删除返回 403；⑬ PROJECT 记忆绑定不存在或停用项目被拒；⑭ 编辑不允许修改 scope/所属项目；⑮ 不存在的记忆编辑/停用返回明确 404
- [x] 5.2 实现 `GET /api/memories`（过滤+分页，登录即读）与 `POST/PUT/DELETE /api/admin/memories`（ADMIN）：人工路径不做语义判断、但字段与 scope 校验不可绕过；审计字段记录操作者
- [x] 5.3 验证：403/404/校验错误语义与成功路径集成用例

## 6. 行为 E：Agent 集成（主 Agent 注入与三工具）

- [x] 6.1 实现 `MemoryTools`（@Tool：memory_search/memory_read/memory_write）并注册到会话图主 Agent（仅主 Agent；主 Agent Agent 定义 tool_names 白名单同步，专家白名单不变），工具内回查 `agent_run` 范围校验（taskType=knowledge_curation、RUNNING、会话/项目一致）
- [x] 6.2 失败测试（先行）：⑯ 记忆块只注入 `agentNode=MAIN` 的上下文，coordinator/retriever/drafter/reviewer 上下文无记忆块（组装视图断言）；⑰ 主 Agent spec 与注册工具不一致时 validate() 启动 fail-fast；⑱ run 状态/范围不符时记忆工具拒绝执行
- [x] 6.3 实现注入：`ContextAssemblyRequest` 增加会话 `projectId`；`purposeBlock` 在【当前指令】前追加【用户记忆】块（仅 MAIN；摘要行 `[分类/范围] 标题：摘要`；末尾含 memory_read 指引与择优提示；检索异常 WARN 跳过不阻塞）；主 Agent spec 指令补充记忆使用边界（非证据、非任务、冲突择优、偏好经指令传达给专家）
- [x] 6.4 端到端验证：真实 Executor + 脚本化模型——对话中出现偏好 → 主 Agent 调 memory_write 写入 → 下一轮上下文出现【用户记忆】块 → 起草指令携带偏好且记忆全文/编号不出现于专家视图，公开事件与发布门禁（等待人工发布）行为不变

## 7. 文档与收口

- [x] 7.1 更新 `docs/product/LoreDock_MVP功能开发计划.md`：登记记忆模块任务状态与"超出 MVP 基线、用户确认新增"说明；`docs/architecture/` 简单登记记忆模块卡片（归属/边界/非证据约束）
- [ ] 7.2 收口验证：相关单测、真实 PostgreSQL 记忆 IT、Web 契约、前端测试与构建通过；`openspec validate` 通过；全量 Agent 驱动回归中的既有失败需单独归因后再收口，提交信息按规范记录改动点与验证证据
  - 当前证据：MemoryServiceIT 15/15、MemoryWebContractTest 6/6、MemoryToolsTest 4/4；前端 119/119，构建通过。后端默认测试共 506 例，失败 5 例集中在既有知识整理 Agent 驱动用例，尚未作为本次记忆改动收口依据。
