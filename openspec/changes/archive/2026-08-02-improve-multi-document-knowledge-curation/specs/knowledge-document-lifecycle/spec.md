## ADDED Requirements

### Requirement: 知识任务必须按完整审核集合原子发布多文档
管理员 SHALL 只能发布仍为 `PROCESSING` 的知识任务，并 SHALL 提交任务当前全部有效工作文档的 `draft_id/reviewed_revision` 完整集合与任务级幂等键。服务端 MUST 在一个事务中验证集合、当前工作修订和固定正式基线，执行全部 ADD/MODIFY，更新草稿、任务与候选输入状态，记录发布事实并创建一个持久化索引任务。任一校验或写入失败 MUST 回滚整个集合，MUST NOT 留下部分正式发布。

#### Scenario: 同时发布新增与修改
- **WHEN** 审核集合包含一份 ADD 和一份 MODIFY，所有当前修订、目录标题和正式基线都有效
- **THEN** 系统在同一事务创建新正式文档、更新既有正式文档、把任务设为 PUBLISHED、把候选输入设为 CURATED，并只创建一个索引任务

#### Scenario: 审核集合不完整
- **WHEN** 请求遗漏当前有效工作文档、包含额外草稿、重复草稿或包含空 v0 草稿
- **THEN** 系统拒绝整个请求且不发布任何文档

#### Scenario: 任一正式基线变化
- **WHEN** 任一 MODIFY 工作文档固定的 baseline_revision 已不再是正式文档当前修订
- **THEN** 系统报告冲突并回滚全部 ADD/MODIFY，其他无冲突文档也不发布

#### Scenario: 发布幂等重试
- **WHEN** 管理员以相同幂等键和相同审核集合重试已成功请求
- **THEN** 系统返回原发布结果，不生成重复正式文档、正式修订或索引任务

#### Scenario: 发布幂等键异参
- **WHEN** 同一任务使用已存在幂等键提交不同审核集合
- **THEN** 系统拒绝为发布幂等冲突，原发布事实保持不变

### Requirement: 知识任务 MODIFY 必须保持正式文档稳定身份
知识任务的 MODIFY 发布 SHALL 更新固定 `baseline_document_id` 对应的同一正式文档，只修改 Markdown 正文并增加正式修订号；标题、目录、标签和 document_id SHALL 保持不变。普通 MODIFY MUST NOT 创建替代文档或归档基线。ADD SHALL 创建新正式文档 ID，并在发布时验证项目内已有目录和标题约束。

#### Scenario: 发布正文修改
- **WHEN** MODIFY 的审核正文有效且正式修订等于固定基线
- **THEN** 原 document_id 的正文和正式修订被更新，原标题、目录、标签与引用身份不变

#### Scenario: 新增文档标题冲突
- **WHEN** ADD 的最终目录与标题在项目正式知识中已经存在
- **THEN** 整个任务发布失败且不创建新文档

#### Scenario: Agent 尝试修改元数据
- **WHEN** MODIFY 工作草稿请求改变标题、目录或标签
- **THEN** 工作区 Tool 在正式发布前拒绝该变更，既有元数据保持不变

### Requirement: 发布后的索引更新必须与业务事务可靠衔接
知识任务发布事务 SHALL 只创建一个持久化知识索引任务，并 SHALL 在业务事务提交后异步执行。索引任务失败 MUST NOT 回滚已提交的正式文档或任务状态；系统 SHALL 暴露索引中、成功或失败状态并允许现有后台任务机制重试。

#### Scenario: 发布提交后索引成功
- **WHEN** 多文档发布事务提交且索引任务正常完成
- **THEN** 所有新正式修订进入同一个新的活动索引数据集，页面显示索引已更新

#### Scenario: 发布提交后索引失败
- **WHEN** 正式发布已经提交但异步索引任务失败
- **THEN** 正式文档与任务保持 PUBLISHED，页面显示索引更新失败，上一活动索引继续可用且后台可以重试
