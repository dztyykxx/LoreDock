# LoreDock 模型选型评估

## 结论

当前最终选择 DeepSeek V4 Flash。它已有 40 条 QA 的全量评估结果；知识整理从单 Agent 改为多 Agent 后，8/8 条全部完成，问题案例动作正确率为 83.33%，误写率为 0%。在三款候选模型中，DeepSeek 同时满足 QA 质量、知识整理安全性和完整运行要求，而且调用成本最低，因此最适合作为 LoreDock 当前默认模型。Qwen3.8 Flash 的 QA 表现同样较好，但知识整理只完成 7/8 条且成本更高；GLM-5.3 Flash 确认完成 34/40 条 QA 和 1/8 条知识整理，其余按失败计。

## 评估范围

- QA：项目/分支范围、检索命中、回答类型、引用覆盖和拒答边界。
- 知识整理：重复/冲突/缺失识别、动作选择、工作文档写入和完成状态。
- 运行指标：模型调用次数、Token、耗时、超时、401/403/429 与结构化响应失败。
- DeepSeek 结果复用历史已验证报告，不在本轮重复消耗额度。

## 结果

| 模型 | 运行范围 | QA Top-5 命中 | QA 召回 | QA 结果类型匹配 | 整理动作正确 | 误写率 | 成本判断 | 状态 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| DeepSeek V4 Flash | 40 QA；多 Agent 改造后复跑 8 条整理 | 100% | 100% | 97.5% | 83.33% | 0% | 三者最低 | 最终选型 |
| GLM-5.3 Flash | 本轮 40 QA + 8 整理全量尝试 | 报告未落盘，无法重算 | 报告未落盘，无法重算 | 成功样本 97.06%（33/34）；全量 82.5%（33/40） | 成功样本 100%（1/1）；全量 12.5%（1/8） | 成功样本 0% | 全量未跑通，缺少有效对比 | 暂不采用 |
| Qwen3.8 Flash | 本轮 40 QA + 8 整理；成功样本离线裁判 | 100% | 98.61% | 92.5% | 83.33%（确定性）/60%（Judge） | 0% | 高于 DeepSeek | QA 40 条完成；整理 7/8 完成，作为备选 |

DeepSeek 的 QA 正式报告为 [atlas-agent-eval-report-judged.json](/Users/dztyykxx/projects/LoreDock/docs/quality/atlas-agent-eval-results/atlas-agent-eval-report-judged.json)：40/40 条 QA 完成，平均忠实度 96.93、相关性 96.48，平均耗时约 13.6 秒/条。知识整理采用单 Agent 改为多 Agent 后的复跑报告 [atlas-agent-eval-report-projection-repair-full-judged.json](/Users/dztyykxx/projects/LoreDock/backend/target/atlas-agent-eval-report-projection-repair-full-judged.json)：8/8 条完成，问题案例动作正确率 83.33%，误写率 0%。旧版单 Agent 的 66.67% 动作正确率和 25% 误写率只作为改造前基线，不再代表当前 DeepSeek 多 Agent 方案。

GLM 全量进程执行了 40 条 QA：日志可确认 34 条 `COMPLETED`、2 条 `AGENT_MODEL_RESPONSE_INVALID`，另 4 条终态记录缺失；按本次约定，后 6 条全部算失败。34 条成功 QA 中有 28 条回答、6 条拒答，结果类型匹配 33/34；按 40 条全量分母为 33/40。成功 QA 平均耗时约 19.0 秒/条。知识整理仅第 1 条确认完成，后续运行卡在终态等待，因此按 1/8 成功；这 1 条经 OpenGo `deepseek-v4-pro` Judge 判定问题识别正确、动作正确、无误写。恢复日志保存在 [glm-full-recovered.log](/Users/dztyykxx/projects/LoreDock/backend/target/glm-full-recovered.log)，成功整理 Judge 报告为 [atlas-eval-glm-5.3-flash-success-judged.json](/Users/dztyykxx/projects/LoreDock/backend/target/atlas-eval-glm-5.3-flash-success-judged.json)。由于中断前没有生成 GLM 全量 JSON，现有日志没有保存每条最终 Top-5 文档列表和 QA 回答正文，不能可靠重算召回率、忠实度和相关性。

Qwen 全量报告为 [atlas-eval-qwen3.8-flash-full.json](/Users/dztyykxx/projects/LoreDock/backend/target/atlas-eval-qwen3.8-flash-full.json)。其中 40 条 QA 全部完成；8 条知识整理中 7 条完成、1 条为 `WAITING_FOR_USER`。成功样本裁判报告为 [atlas-eval-qwen3.8-flash-success-noqa19-judged.json](/Users/dztyykxx/projects/LoreDock/backend/target/atlas-eval-qwen3.8-flash-success-noqa19-judged.json)，包含 39 条 QA 和 7 条成功知识整理；QA-019 的 Judge 返回空正文，按裁判失败计入 40 条原始分母。

Qwen 运行成本概览：40 条 QA 平均耗时约 11.8 秒/条；7 条已完成的知识整理平均耗时约 136.9 秒/条、平均 15 次模型调用、输入约 7.8 万 Token、输出约 1.0 万 Token。8 条整理中实际跑通 7 条，1 条等待人工输入。

Token 口径说明：上述 Token 是 Agent 运行记录中的输入/输出累计，按 7 条成功知识整理计算平均值；QA 运行报告没有逐条 Token 字段，Judge 报告也没有记录供应商 usage，因此不对这两部分臆造 Token 数字。

成功知识整理任务的 Token 明细如下，输入/输出均为模型供应商返回的 usage，合计为两者相加：

| 模型 | 成功任务数 | 单任务平均输入 / 输出 / 合计 | 成功任务总输入 / 输出 / 合计 |
|---|---:|---:|---:|
| DeepSeek V4 Flash（多 Agent） | 8 | 38,401 / 14,117 / 52,518 | 307,208 / 112,939 / 420,147 |
| GLM-5.3 Flash | 1 | 31,605 / 10,987 / 42,592 | 31,605 / 10,987 / 42,592 |
| Qwen3.8 Flash | 7 | 77,865 / 10,116 / 87,981 | 545,058 / 70,811 / 615,869 |

DeepSeek 的 8 条成功整理总 Token 约 42.0 万，单任务约 5.25 万，低于 Qwen 的约 61.6 万总 Token和约 8.80 万单任务。GLM 只有 1 条成功整理，Token 数只能作为单条记录参考，不能与完整 8 条运行直接比较。三款模型的 QA 报告没有保存逐任务输入/输出 Token，GLM QA 日志也只记录 `tokenUsageKnown=true`，没有具体数量；离线 Judge 的 usage 同样未写入报告，以上表格不包含 Judge 消耗。

本轮 Qwen 和 GLM 的离线裁判使用 OpenGo `deepseek-v4-pro`，沿用历史评估的 QA 忠实度/相关性和知识整理问题类型、动作、误写口径，只对已保存的成功样本调用 Judge；未完成、等待人工或 Judge 空响应均保留在全量分母中并计为失败。Qwen 的 39 条可判 QA 平均忠实度 98.62、平均相关性 98.13；7 条成功整理的问题识别率 60%、动作正确率 60%、误写率 0%，问题类型 F1 为 CONFLICT 0.667、MISSING 0.571、DUPLICATE 0。GLM 只有 1 条成功整理保留了完整可判数据，因此该条的问题识别和动作均正确、无误写；GLM 的 34 条成功 QA 没有形成可供 Judge 读取的报告，不能补造评分。

如果把 Qwen 的 1 条 Judge 空响应也按失败计入 QA 分母，忠实度均值为 96.15、相关性均值为 95.68；知识整理按 8 条原始用例计完成率时，Judge 成功样本的动作正确折算为 37.5%（3/8），等待人工的用例不能被当作成功。

## 面试口径

模型选型不是只比较单次回答，而是先设质量与安全门槛，再比较完整运行率和成本。LoreDock 使用固定数据集、真实 PostgreSQL/BGE 检索、统一 Tool 权限和统一报告进行评估。DeepSeek V4 Flash 的 40 条 QA 全部完成；知识整理改为多 Agent 后，8 条全部完成，动作正确率达到 83.33%，误写率降到 0%，同时三款模型中调用成本最低。因此当前选择 DeepSeek V4 Flash 作为默认模型，Qwen3.8 Flash 作为备选，GLM-5.3 Flash 暂不采用。
