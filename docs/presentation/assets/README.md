# LoreDock 答辩图表资产

本目录包含第 7、8、9、10、11、12、13、16 页使用的 16:9 PNG 图表。图片由内置 Image 2 路径生成，并统一转换为白色背景 RGB PNG，适合直接插入 PowerPoint。

## 文件对应关系

| 页码 | 文件 | 表达重点 |
| --- | --- | --- |
| 7 | `slide-07-technical-vs-business-correctness.png` | 技术正确性不能自动推出业务正确性 |
| 8 | `slide-08-loredock-positioning.png` | LoreDock 的输入、定位、用户及四项约束 |
| 9 | `slide-09-knowledge-closed-loop.png` | 开发前查询、开发后沉淀、整理、审核和复用闭环 |
| 10 | `slide-10-technical-architecture.png` | 内网 CPU 单机部署下的五层技术架构 |
| 11 | `slide-11-scope-controlled-retrieval.png` | Web、MCP 与知识整理 Agent 共用的范围受控混合检索 |
| 12 | `slide-12-trust-and-review.png` | 回答引用校验与正式知识发布的双重门禁 |
| 13 | `slide-13-pilot-materials.png` | 真实需求材料如何沉淀为可追溯知识 |
| 16 | `slide-16-ai-collaboration-boundary.png` | AI 协作方式调整及开发者决策边界 |

第 14 页仍缺少实际难点与验证结果，未生成占位图。第 15 页应使用脱敏后的真实系统截图，不使用生成图替代运行证据。

## 统一视觉提示词

```text
Use case: productivity-visual / infographic-diagram
Asset type: Chinese graduation thesis defense chart
Style/medium: restrained professional Chinese thesis-defense infographic, clean vector-like flat design, white background, dark navy #17324D, muted blue #356C9B, desaturated teal #4F827B, small amber #C78B3A only for review or warning states, thin cool-gray arrows, crisp geometric sans-serif typography.
Composition/framing: 16:9 landscape, generous whitespace, presentation-ready, large readable Chinese labels, strict grid and alignment.
Constraints: render all quoted Chinese text verbatim; no extra words; no logo; no watermark; no decorative people; no stock photography; no 3D; no gradients; no glowing effects; no tiny text; no excessive icons.
```

## 各页最终提示词要点

### 第 7 页

```text
Create a left-right comparison: “技术正确性” with “编译通过 / 测试通过 / 流程可运行”, versus “业务正确性” with “字段口径正确 / 模板规则真实 / 兼容原因明确”. Between them show “不能自动推出”. Bottom flow: “重复沟通 + Agent 无法判断” → “LoreDock：可检索、可验证的业务上下文”. Title: “代码可运行 ≠ 业务结果正确”.
```

### 第 8 页

```text
Show “需求背景 / PR 变更 / 测试证据 / 代码事实 / 历史经验” flowing into “LoreDock：业务规则 · 设计原因 · 历史约束 · 验证证据”, then to “开发人员 / 新成员（Web）” and “本地研发 Agent（MCP）”. Bottom tags: “范围隔离 / 人工审核 / MCP 接入 / 内网 CPU 部署”. Include the verified research statement about 19 open-source projects.
```

### 第 9 页

```text
Show a seven-step knowledge closed loop: “本地材料（需求 · PR · 代码 · 测试）” → “开发前查询（Coding Agent 通过 MCP 获取业务上下文）” → “完成开发” → “本地 Skill 提炼（生成候选 Markdown）” → “AI 知识整理（查重 · 冲突 · 过期 · 缺口）” → “人工审核发布” → “Web / MCP 使用”. Loop usage back to development-time query. Branch “证据不足” to “拒答 + 记录知识缺口”. Make clear that the local Agent reads local materials; the platform does not import the local repository directly.
```

### 第 10 页

```text
Create five horizontal layers: “交互层（Vue 3 Web / 本地 Coding Agent）”; “接入层（REST + SSE / MCP Streamable HTTP / Sa-Token）”; “应用层（知识管理 / 知识问答 Agent / 知识整理 Agent / 人工审核）”; “检索与模型层（关键词 + 向量混合检索 / BGE ONNX 离线 Embedding / 可配置 ChatModel）”; “数据与部署层（PostgreSQL + pgvector / Java 21 + Spring Boot / Docker Compose 单机部署）”. Show Web and MCP sharing application and retrieval services. Exclude Lucene, code snapshot, branch scope, local object storage, Kubernetes and microservices.
```

### 第 11 页

```text
Main flow: “Web 问答 / MCP 查询 / 知识整理 Agent” → “范围解析（通用知识 + 当前项目）” → parallel “关键词候选” and “向量候选（BGE ONNX）” → “RRF 融合 + 范围复核” → “Top-K 结果 + 来源引用”. Index mini-flow: “构建新索引” → “验证” → “原子切换”; failure branch “失败：保留旧 ACTIVE 索引”.
```

### 第 12 页

```text
Two gates. Answer gate: “本轮检索证据” → “模型回答 + 引用 ID” → “服务端引用校验” → either “有效：带引用回答” or “无效 / 不足：拒答 + 知识缺口”. Knowledge gate: “Agent 生成草稿” → “多文档 Diff” → “管理员审核” → “发布并进入正式索引”, with rejection loop “追加指导 / 继续修改”. Bottom conclusion: “模型不能绕过证据，也不能直接发布正式知识”.
```

### 第 13 页

```text
For the pilot requirement “场景包导入导出与新产品适配”, connect “需求背景 / 模板字段 / 代码链路 / 改动记录 / 测试证据” to “本地 Skill 提炼” → “LoreDock 整理与审核” → “Web 带引用问答 / MCP 业务上下文”. Add “试点项目：网络设计工具（约 10 万行代码）” and “验证：材料可追溯 · 知识可审核 · 结果可引用”. Do not invent performance numbers or screenshots.
```

### 第 16 页

```text
Three stages: “初始方式：大任务交给 AI 全流程执行” → “发现问题：规格过大 / 超出 MVP / 类、表和通用机制膨胀” → “调整后的流程：确定核心流程与 MVP → 固定模块与契约 → 拆分小任务 → AI 辅助实现与测试 → 人工审查范围、依赖和复杂度”. Bottom responsibility boundary: “开发者决策：目标 · 范围 · 架构 · 取舍” and “AI 辅助：调研 · 规格整理 · 编码 · 测试 · 文档”.
```
