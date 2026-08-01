# Atlas 场景包文档问答评测集 v1

这是一套完全虚构、可公开提交的测试数据，用于验证 LoreDock 当前“文档优先”的知识导入、发布、索引、检索、项目问答、引用和拒答链路。数据不包含真实公司信息、凭据或生产地址。

## 测试项目

- 项目名称：Atlas 网络场景编排平台
- 项目标识：`atlas`
- 默认分支：`main`

请只在 LoreDock 中创建上述项目。当前前端不提供分支管理或选择入口，导入、检索和问答均使用服务端解析的默认范围。

## 可直接上传的压缩包

`dist/` 中包含四个知识导入包。除通用规范外，其余三个包都导入同一 PROJECT 范围；V2 只是 Atlas 业务规则版本，不是 LoreDock 分支：

| 文件 | 导入范围 | 项目 | 建议目录前缀 | 建议标签 |
|---|---|---|---|---|
| `atlas-global-knowledge.zip` | GLOBAL | — | `测试资料/通用规范` | `测试数据,通用规范` |
| `atlas-project-knowledge.zip` | PROJECT | `atlas` | `测试资料/Atlas` | `测试数据,场景包` |
| `atlas-runtime-knowledge.zip` | PROJECT | `atlas` | `测试资料/Atlas/稳定规则` | `测试数据,稳定规则` |
| `atlas-v2-knowledge.zip` | PROJECT | `atlas` | `测试资料/Atlas/V2` | `测试数据,增量导入` |

导入后文档处于草稿状态。请人工发布全部文档，再执行一次知识索引重建。两个“审计记录留存期限”文档故意互相冲突，不要设置替代关系；它们用于验证冲突拒答和后续 `knowledge_curator` 整理能力。

## QA 数据集

- `qa/qa-dataset.jsonl`：机器评测主文件，每行一个 JSON 对象；
- `qa/qa-dataset-schema.json`：字段结构约束；
- `qa/qa-dataset.md`：人工评测清单与评分方法。

共 20 题：16 题有文档依据，1 题来源冲突，3 题无依据；其中包含 2 题口语表达和 4 题业务版本规则题。全部问题都在项目默认范围执行。

建议以来源命中和事实覆盖为主，不要求模型逐字匹配参考答案：

1. 有答案题必须返回 `ANSWER / BUSINESS_RULE`，至少引用一个 `expectedSources` 中的文档；
2. `requiredFacts` 全部出现或语义等价，且不得出现 `forbiddenClaims`；
3. 冲突题必须返回 `REFUSAL / SOURCE_CONFLICT` 并同时引用两份冲突文档；
4. 无依据题必须拒答，不得使用模型常识补造项目事实；
5. 业务版本题必须根据文档中明确的“稳定规则”或“V2 规则”作答，不得把 V2 试验结论说成已正式上线。

## 源文件与总包

`source/` 保存生成各上传包的 Markdown 原文。`dist/atlas-document-qa-eval-kit-v1.zip` 是便于传递的总包，包含本说明、源文档、四个上传包以及完整 QA 数据集。
