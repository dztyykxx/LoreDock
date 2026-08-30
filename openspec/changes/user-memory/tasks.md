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

## 4. 行为 C：写入判断（值得写 / 重复不复活 / 冲突仍写 / 预算）

- [x] 4.1 失败测试（先行）：⑦ 语义重复（异词同义）跳过且返回 SKIP_DUPLICATE；⑧ 与 DISABLED 记忆语义重复被跳过、停用记忆不被复活；⑨ 与既有记忆语义冲突仍写入并双 ACTIVE；⑩ 一次性任务指令判 SKIP_NOT_WORTH；⑪ 单次候选超 3 条与 run 累计 `source_run_id` 新增超上限（默认 10）被拒写
- [x] 4.2 实现 `memory_write` 判断链：候选校验 → 相近既有记忆召回（同分类或关键词命中，ACTIVE+DISABLED，≤50）→ 单次 ChatModel 结构化判断（CREATED/CONFLICT_CREATED/SKIP_DUPLICATE/SKIP_NOT_WORTH，输出 conflictsWith 仅入日志）→ 写入（缺省 summary=正文前 300 码点）；判断失败抛可重试错误不静默；scope 由 run 决定（会话挂项目→PROJECT，否则 GLOBAL）
- [x] 4.3 验证：用脚本化模型驱动判断器 + 真实库断言三条路径与预算边界，输出判断结论与预算余量日志

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
- [x] 7.2 全量相关测试通过 + `openspec validate` 通过 + `tasks.md` 勾选同步 + 提交信息按规范记录改动点与验证证据
  - 验证证据：单元 467/467（跳过 3 为 POC）；集成 199 例中用户记忆相关全部通过（MemoryServiceIT 12、MemoryWebContractTest 6、MemoryToolsTest 4、DriveIT 8 含端到端证据行「场景=记忆端到端，写入 id=1(GLOBAL/FORMAT)，runB主Agent两次注入=- [FORMAT/GLOBAL] 正文格式偏好…，专家视图无记忆块，发布门禁不变」）；`openspec validate --changes/--specs` 7 changes + 18 specs 全部通过
  - 遗留（非本 change 缺陷，已另行修复）：`AtlasAgentEvalDeterministicIT` 2 例在干净 HEAD(6cdf6d3) 同样失败（回归确认：`AgentRunStatus` 缺 `PAUSE_REQUESTED/WAITING_FOR_USER` 枚举常量 + 评估脚本仍为旧单 Agent 协议），属于 `multiagent-knowledge-curation` 编排 change 的遗留；已通过 `fix(agent): AgentRunStatus 补暂停恢复状态 + 评估脚本改主 Agent TURN_DONE 结构化协议` 提交修复（全量 verify 通过）
