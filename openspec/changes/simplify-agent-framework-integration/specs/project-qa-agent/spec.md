## MODIFIED Requirements

### Requirement: project_qa 必须只使用受控证据工具回答项目事实
`project_qa` SHALL 由框架 classpath Skill Registry 加载，并 SHALL 只能通过服务端显式提供的知识搜索 Tool 获取项目业务事实；Skill 定义、Tool 解析和 ReAct 循环 MUST 使用 Spring AI Alibaba/Spring AI 原生组件，不得由 LoreDock 自建 Provider、Front Matter Parser 或通用 Runtime。系统 SHALL 为每条工具结果分配仅在当前运行有效的稳定证据 ID，并保留知识文档来源元数据；工具正文 MUST 在进入模型上下文前受长度和总量限制。模型常识、其他对话、其他运行和未通过工具取得的内容 MUST NOT 作为 LoreDock 项目事实证据。

#### Scenario: 基于人工知识回答业务原因
- **WHEN** 问题询问业务规则或设计原因，且允许范围内的已发布知识达到证据阈值
- **THEN** 框架 Agent 使用 `knowledge_search` 取得对应证据形成结果，并保留文档 ID、范围、标题、公开来源和更新时间

#### Scenario: 其他范围存在更相关内容
- **WHEN** 其他分支或其他项目存在更高相关的知识
- **THEN** 候选生成、证据台账和最终结果均不包含越界来源

#### Scenario: 工具没有命中
- **WHEN** 当前允许范围内的知识工具没有返回达到阈值的证据
- **THEN** Agent 不扩大范围、不使用模型常识补写项目事实，并进入拒答结果

#### Scenario: 工具执行失败
- **WHEN** `knowledge_search` 第一次调用因权限、证据版本或基础设施异常失败
- **THEN** 框架立即停止当前运行并保留对应业务错误，不继续请求模型直到耗尽运行上限，也不把失败转换为证据不足
