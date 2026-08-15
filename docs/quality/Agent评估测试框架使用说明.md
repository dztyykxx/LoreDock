# LoreDock Agent 评估测试框架使用说明

本文说明如何用后端测试框架运行 Atlas Agent 评估：加载测试数据集、构造评估环境、执行 QA 与知识整理用例、计算指标并生成机器报告。

## 1. 框架位置与职责

框架位于 `backend/src/test/java/io/github/loredock/eval/`，全部为测试支撑类，不进入生产代码：

| 类 | 职责 |
|---|---|
| `AtlasAgentEvalFixture` | 读取 `docs/quality/atlas-agent-eval-tests` 与 `atlas-eval-documents` 数据集，执行最小质量检查（用例数量、类型分布、文档 ID 反查、参考回答完整等） |
| `AtlasAgentEvalSeeder` | 按 manifest 固定 Long ID 写入 atlas 项目、main 分支、14 篇正式知识与 8 篇候选草稿，并真实重建知识索引 |
| `AtlasQaEvalRunner` | 逐条执行 QA 用例：`caseId` 作幂等键创建问答，等待终态，读取 `agent_run_retrieval` 中模型本轮实际看到的检索原文，计算 Top-5 候选 |
| `AtlasCurationEvalRunner` | 逐条执行知识整理用例：单草稿启动任务，等待终态，提取最后一条 `COORDINATOR_AGENT` 最终回复与实际工作区正文 |
| `AtlasEvalMetrics` | 客观指标：QA Top-5 准确率/召回率、结果类型与拒答原因匹配、引用覆盖；知识整理工作区匹配、动作正确率（确定性近似）、误写率；LLM Judge 字段保留为扩展点 |
| `AgentEvalReport` | 组装机器报告（数据集版本、逐条实际结果与判定、汇总指标、门禁），写入 JSON 并输出逐条 stdout 证据 |

数据只保留一份：框架直接读取仓库 `docs/quality/` 下的数据集，不复制到测试资源；可用系统属性 `loredock.agent-eval.data-dir` 覆盖数据目录。

## 2. 运行方式

### 2.1 确定性框架验证（无外部调用，可随时运行）

```bash
cd backend
JAVA_HOME=<jdk21> ./mvnw -Dit.test=AtlasAgentEvalDeterministicIT \
  test-compile failsafe:integration-test failsafe:verify
```

使用真实 PostgreSQL（Testcontainers）与可控脚本 ChatModel 运行代表性用例（回答、拒答、知识整理各一），验证种子 → 运行 → 采集 → 指标 → 报告全链路，不产生模型费用。

### 2.2 真实模型全量评估（需要模型与密钥）

```bash
cd backend
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval=true \
  -Dloredock.agent-eval.model-dir=/path/to/bge-small-zh-v1.5 \
  -Dit.test=AtlasAgentEvalRealModelIT \
  test-compile failsafe:integration-test failsafe:verify
```

- `loredock.agent-eval=true`：启用真实模型评估（默认关闭，避免日常测试产生费用）；
- `loredock.agent-eval.model-dir`：语义检索 ONNX 模型目录，须包含 `model.onnx` 与 `tokenizer.json`，模型与 T5 基准一致（BAAI/bge-small-zh-v1.5，校验和 `3a40c6ea...`）；
- `LOREDOCK_AGENT_MODEL_API_KEY`：ChatModel 密钥，也可用 `-Dloredock.agent-eval.api-key` 指定；
- 完整运行 40 条 QA + 8 条知识整理用例，单条用例等待终态上限 5 分钟，全量运行可能持续数小时。

### 2.3 真实模型冒烟验证（先确认链路再全量执行）

Agent 运行本身消耗 token，先用少量真实案例确认链路可运行，再投入全量：

```bash
cd backend
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval=true \
  -Dloredock.agent-eval.model-dir=/path/to/bge-small-zh-v1.5 \
  -Dloredock.agent-eval.qa-cases=1 \
  -Dloredock.agent-eval.curation-cases=1 \
  -Dloredock.agent-eval.output=target/atlas-agent-eval-report-smoke.json \
  -Dit.test=AtlasAgentEvalRealModelIT \
  test-compile failsafe:integration-test failsafe:verify
```

- `loredock.agent-eval.qa-cases`：执行前 N 条 QA 用例（默认全部 40）；
- `loredock.agent-eval.curation-cases`：执行前 N 条知识整理用例（默认全部 8）；
- `loredock.agent-eval.output`：报告输出路径，冒烟验证建议单独指定，避免覆盖全量报告；
- 冒烟通过（门禁全部通过、stdout 逐条证据符合预期）后再去掉限制参数执行全量评估。

### 2.4 断点续跑（长跑中断后只重跑失败用例）

长跑中断或部分用例失败后，不需要全量重来：

```bash
cd backend
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval=true \
  -Dloredock.agent-eval.model-dir=/path/to/bge-small-zh-v1.5 \
  -Dloredock.agent-eval.resume=true \
  -Dit.test=AtlasAgentEvalRealModelIT \
  test-compile failsafe:integration-test failsafe:verify
```

- 读取 `loredock.agent-eval.output` 指定的上一轮报告，跳过已 `COMPLETED` 的用例，只重跑未完成/失败/缺失的用例，合并后写回同一报告；
- 续跑模式忽略用例数量限制；每次运行是全新数据库并重新灌入评估环境，不依赖旧库数据；
- 想强制重跑某条已完成的用例：删除报告或换 `loredock.agent-eval.output` 路径后再跑。

### 2.5 离线 LLM Judge（忠实度/相关性/问题识别评判）

评估报告生成后，可单独对已采集的实际结果做 LLM 评判，不重跑 Agent、不需要数据库：

```bash
cd backend
JAVA_HOME=<jdk21> LOREDOCK_AGENT_MODEL_API_KEY=<密钥> ./mvnw \
  -Dloredock.agent-eval.judge=true \
  -Dit.test=AtlasEvalJudgeIT \
  test-compile failsafe:integration-test failsafe:verify
```

- 读取 `loredock.agent-eval.output` 指定的评估报告（默认 `target/atlas-agent-eval-report.json`），逐条调用 ChatModel 评判后写入评判报告（默认 `target/atlas-agent-eval-report-judged.json`，可用 `-Dloredock.agent-eval.judge-output` 覆盖）；
- QA Judge 输出忠实度/相关性（0-100）与理由，知识整理 Judge 输出问题识别/动作/误写判定与理由，并重算问题识别正确率与各问题类型 F1；
- 评判温度固定为 0，同一报告可换模型反复评判；评判额外消耗约 1 次模型调用/用例；
- 裁判模型独立配置：环境变量 `LOREDOCK_EVAL_JUDGE_MODEL`（或 `-Dloredock.agent-eval.judge-model`）指定，默认与 Agent 模型相同；建议使用与 Agent 不同源的更强模型（如 `qwen3.7-plus`）避免同模型自评偏差；
- 评判前必须先有完整评估报告：冒烟报告（`-Dloredock.agent-eval.output=target/atlas-agent-eval-report-smoke.json`）或全量报告均可直接评判。

### 2.6 单元测试

```bash
cd backend
JAVA_HOME=<jdk21> ./mvnw test -Dtest='AtlasAgentEvalFixtureTest,AtlasEvalMetricsTest'
```

保护数据集完整性与指标口径，不需要 Docker 与模型。

## 3. 报告与证据

- 机器报告：`backend/target/atlas-agent-eval-report.json`（可用 `-Dloredock.agent-eval.output` 覆盖路径）；
- 评判报告：`backend/target/atlas-agent-eval-report-judged.json`（可用 `-Dloredock.agent-eval.judge-output` 覆盖路径），在原始报告基础上回填 Judge 判定并重算指标；
- 逐条证据：运行 stdout 输出每个用例的场景、范围、终态、Top-5 候选、引用数、工作区结果、耗时与判定，与报告内容一致；
- 报告字段与《Agent 评估测试数据构造要求》第 5、7 节的实际结果格式对应（检索记录含模型实际看到的 `content` 与裁剪标记）。

## 4. 指标口径

- QA：Top-5 候选 = 跨全部检索按相关度降序去重取前 5；准确率/召回率只统计可回答用例（`ANSWER`），拒答用例不参与；
- 知识整理（未评判报告）：动作正确率采用确定性近似（NO_CHANGE/MERGE/ADD_OR_UPDATE 按工作区匹配，ASK_USER 额外要求最终回复请求人工确认）；误写率按禁止事实是否写入工作草稿判定；
- 知识整理（评判报告）：动作正确率、误写率、问题识别正确率与 DUPLICATE/CONFLICT/MISSING 的 Precision/Recall/F1 均采用 LLM Judge 判定（问题识别只看最终回复，动作与误写结合工作草稿）；
- QA 忠实度/相关性（评判报告）：LLM Judge 百分制平均分，忠实度只判断实际回答是否由本轮检索原文支持，相关性判断是否直接解决用户问题；
- Judge 温度固定为 0，只依据评判输入中的原文，不使用模型常识补充 Atlas 事实。

## 5. 常见问题

- 找不到数据目录：确认从仓库内运行 Maven，或设置 `-Dloredock.agent-eval.data-dir=<docs/quality 绝对路径>`；
- 真实模型评估启动失败：检查模型目录文件与校验和、ChatModel 密钥是否配置；
- 离线评判提示找不到报告：先运行真实模型评估（全量或冒烟）生成报告，再运行 `AtlasEvalJudgeIT`；
- 用例超时：查看 stdout 中该用例的终态与错误码，通常是模型不可用或运行受限，而不是框架问题。
