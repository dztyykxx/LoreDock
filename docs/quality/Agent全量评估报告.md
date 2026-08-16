# Agent 全量评估报告（v1）

## 1. 评估概述

- 数据集：`docs/quality/atlas-agent-eval-tests/`（`atlas-agent-eval-v1`，项目 `atlas`）
- 范围：40 条 QA 用例 + 8 条知识整理用例，全部真实模型运行
- Agent 模型：`deepseek-v4-flash`（环境变量 `LOREDOCK_AGENT_MODEL_NAME`）
- 裁判模型：`glm-5.3`（独立于 Agent，温度 0，仅依据检索原文与工作区判定）
- 执行时间：2026-08-16 01:31（UTC 2026-08-15T17:31:57Z）
- 环境：Mac OS X/aarch64 | Java 21.0.12 | PostgreSQL 17.8（Testcontainers pgvector/pgvector:0.8.1-pg17）| Embedding BAAI/bge-small-zh-v1.5 (ONNX)
- 报告文件：
  - 原始报告（机器采集）：`backend/target/atlas-agent-eval-report.json`
  - 评判报告（LLM 裁判回填）：`backend/target/atlas-agent-eval-report-judged.json`

指标口径见《Agent评估测试数据构造要求》第 9 节：参与 Top-N 统计的用例为 `ANSWER` 用例 +
携带期望文档的 `SOURCE_CONFLICT` 拒答用例（36 条）；准确率 = 期望文档在最终 Top-N 中的
出现率（找回数 ÷ N），召回率 = 目标找回率（找回数 ÷ 期望总数），Top-N 取多次检索合并
去重（同一文档取最高相关度）后的最终候选。

## 2. QA Agent 指标

### 2.1 检索指标（36 用例参与统计）

| 窗口 | 准确率（出现率） | 召回率（目标找回率） | 命中率 |
| --- | --- | --- | --- |
| Top-3 | 41.67% | 93.06% | 100% |
| Top-5 | 27.78% | 100% | 100% |

- Top-5 目标文档全部找回，但窗口内噪声大（准确率仅 27.78%，即 5 个位置平均不到 1.5 个目标）；
- 收紧到 Top-3 后窗口噪声下降（41.67%），但 5 条双目标用例的第二个目标被挤出（召回 93.06%）。

### 2.2 行为指标

| 指标 | 结果 |
| --- | --- |
| 结果类型匹配率（ANSWER/REFUSAL） | 97.5%（39/40） |
| 拒答原因匹配率 | 71.4%（5/7） |

### 2.3 质量指标（glm-5.3 裁判）

| 指标 | 结果 |
| --- | --- |
| 平均忠实度 | 96.92（82~100） |
| 平均相关性 | 96.47（78~100） |

回答整体有原文支撑；唯一低分为 QA-039（忠实 82/相关 78）。

## 3. 知识整理 Agent 指标（8 用例）

| 指标 | 结果 |
| --- | --- |
| 问题识别正确率（问题用例 6 条） | 100%（6/6） |
| 问题识别正确率（全量 8 条含 NONE） | 87.5%（7/8，CUR-007 误判） |
| 动作正确率（Judge） | 66.7%（4/6） |
| 误写率（CONFLICT/MISSING 用例） | 25%（1/4，CUR-005） |
| 问题类型 F1 | DUPLICATE 1.00 / CONFLICT 1.00 / MISSING 0.80（P=0.67，R=1.0） |

## 4. Bad Case 与原因

### 4.1 QA Agent

| 用例 | 现象 | 原因 |
| --- | --- | --- |
| QA-034 | 该拒答却回答（期望 REFUSAL/INSUFFICIENT_EVIDENCE，实际 ANSWER） | 问题"Atlas 是否会对接企业微信或钉钉发送通知"，模型检索到"通知规则"相关文档后，把"文档未提及外部渠道"包装成可回答结论（并补充"以本地代码为准"）。拒答触发条件未覆盖"主题相关但问题要点无依据"的场景；回答内容本身有原文支持（裁判 97/97），是结果类型行为错误而非质量问题 |
| QA-039 | 冲突拒答表述缺失（忠实 82/相关 78） | 问题"审计事件保留 180 天还是 365 天"，模型只回复"当前知识库没有足够依据"，未指出 180/365 的具体矛盾出处，且拒答原因误判为 OUTPUT_POLICY_VIOLATION（应为 SOURCE_CONFLICT）。冲突检测到了，但表述信息量与原因归类都错 |
| QA-015/016/017/020/033 | Top-3 召回 0.5（双目标第二个漏出） | 双主题问题的两个目标文档相关度不均衡：第一主题目标进入 Top-3，第二主题目标被同主题干扰文档（校验规则、重试策略等）挤出到第 4-5 位（如 QA-015 期望 [710003 上传导入, 710007 审核发布]，top3=[710003, 710008, 710009]）。Top-5 时全部找回，属于排序质量而非召回能力问题 |

### 4.2 知识整理 Agent

| 用例 | 现象 | 原因 |
| --- | --- | --- |
| CUR-002 | DUPLICATE 识别正确，MERGE 动作未执行 | 草稿约九成与已发布规则重复，但含一条有效新增（清单必须声明幂等键字段，幂等键为空或格式不对时内容校验失败），期望合并保留新增；模型把"九成重复"等同于"全部重复"，不创建任何工作文档，漏掉该新增规则。对"部分重复 + 少量新增"判定过严，倾向保守不动 |
| CUR-005 | MISSING 识别正确，ASK_USER 变 MODIFY，且误写 | 草稿删除规则不完整（缺操作者角色、允许删除的状态），期望询问人工且不写入不完整规则；模型直接 MODIFY DOC-10，把来自未确认草稿的操作细节（删除入口位置、确认框提示）写入正式文档基线。ASK_USER 场景执行了写入，缺少"待确认内容禁止写入"的强约束（误写率 25% 的唯一样本） |
| CUR-007 | NONE 误判为 MISSING | 草稿提供校验和验证步骤（解压确认、逐个计算、逐条比对、不一致处理），属正常补充内容，期望 NONE + 新增文档；模型把"既有文档没有的操作细节"一律归为缺失，识别为 MISSING 并 MODIFY DOC-09。未区分"补充性新增（NONE）"与"既有知识的缺口（MISSING）"；动作层面写入了（MODIFY 与预期 ADD_OR_UPDATE 均判动作正确），但问题类型语义错误 |

## 5. 问题画像与结论

### QA Agent
- 优势：检索召回扎实（Top-5 目标全找回），回答内容有据（忠实度/相关性 96+），引用覆盖 100%（可回答用例）
- 短板：行为判定——拒答触发（QA-034）、冲突拒答表述与原因归类（QA-039）；双主题检索第二目标排序（Top-3 口径 5 条召回 0.5）

### 知识整理 Agent
- 优势：问题识别准确（唯一错误为 NONE/MISSING 边界），冲突类识别与处置全对（CONFLICT F1=1.0）
- 短板：动作执行——MERGE 保守不动（CUR-002）、ASK_USER 越权写入（CUR-005 误写）；NONE 与 MISSING 边界判定（CUR-007）

### 后续建议
1. 拒答判定：补充"主题相关但问题要点无依据"的拒答触发场景（QA-034），并强化冲突拒答必须说明矛盾出处与归类 SOURCE_CONFLICT（QA-039）；
2. 知识整理动作约束：ASK_USER 场景强制禁止写工作区（CUR-005）；"部分重复 + 少量新增"应执行 MERGE 而非 NO_CHANGE（CUR-002）；
3. 问题类型语义：区分"补充性新增（NONE + ADD_OR_UPDATE）"与"既有知识缺口（MISSING）"（CUR-007）；
4. 检索排序：双主题问题可考虑按子问题拆分检索或提高第二主题目标权重（Top-3 召回）。

## 6. 复现方法

```bash
# 全量评估（真实模型，40 QA + 8 知识整理，需 -Dloredock.agent-eval=true 与模型目录/密钥）
cd backend
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval=true \
  -Dloredock.agent-eval.model-dir=<bge-small-zh-v1.5 模型目录> \
  -Dit.test=AtlasAgentEvalRealModelIT test-compile failsafe:integration-test failsafe:verify

# 离线裁判（glm-5.3，逐用例落盘，可断点续跑）
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval.judge=true \
  -Dloredock.agent-eval.judge-model=glm-5.3 \
  -Dit.test=AtlasEvalJudgeIT test-compile failsafe:integration-test failsafe:verify
```
