# MCP 配置与使用说明

本文说明 LoreDock 后端 MCP 服务的配置方式，以及如何接入 Claude Code 等本地 Agent。文档中的命令与行为已在 Claude Code 2.1.227 上实测验证。

## 1. 服务端概览

| 项目 | 值 |
|---|---|
| 端点 | `http://localhost:8080/mcp`（端口由 `LOREDOCK_BACKEND_PORT` 控制，默认 8080） |
| 协议 | Spring AI MCP Server，Streamable HTTP，SYNC 模式 |
| 认证 | `Authorization: Bearer <token>` 请求头，不复用浏览器 Cookie 会话 |
| 项目锁 | 可选 `X-LoreDock-Project` 请求头，把检索范围锁定为部署配置的项目（见第 3 节） |
| 能力 | 仅工具（Tool），无 Resource / Prompt / Completion |

工具清单（`KnowledgeMcpController` 中的 6 个 `@McpTool`）：

- `knowledge_directory_list`：列出项目及通用范围的已发布知识目录；
- `knowledge_document_list`：列出指定目录下的已发布知识文档；
- `knowledge_document_read`：按 Unicode 码点游标分段读取已发布文档；
- `knowledge_grep`：在已发布正文中执行关键词匹配；
- `knowledge_search`：与 Web 问答同源索引的近似检索；
- `knowledge_draft_submit`：向项目待处理草稿池提交 Markdown（写 Token 专用，不启动 AI、不发布）。

MCP 查询不调用模型；草稿提交后仍需管理员在 Web 勾选并启动合并整理，审核通过后才发布。

## 2. Token 配置（服务端）

服务端在 `application.yml` 中读取两个独立 Token（`loredock.mcp.read-token` / `write-token`），通过 `.env` 注入：

```bash
# .env（已被 .gitignore 排除，禁止提交）
LOREDOCK_MCP_ENABLED=true
LOREDOCK_MCP_READ_TOKEN=<只读Token，仅允许查询>
LOREDOCK_MCP_WRITE_TOKEN=<写Token，额外允许提交待处理草稿>
```

语义：

- **只读 Token**：只能调用 5 个查询工具；
- **写 Token**：查询 + `knowledge_draft_submit`，仍不发布、不启动 AI；
- 任一 Token 为空时，对应能力不可用；两个都为空时所有 `/mcp` 请求返回 401，不会退化为匿名访问；
- 修改 Token 后需重启后端生效。

生成高熵 Token（64 位 hex）：

```bash
openssl rand -hex 32
```

## 3. Claude Code 接入配置

使用官方 CLI 注册，scope 默认为 `local`（仅当前项目目录生效，不写仓库）：

```bash
cd /path/to/LoreDock
claude mcp add --transport http loredock http://localhost:8080/mcp \
  --header "Authorization: Bearer <LOREDOCK_MCP_WRITE_TOKEN>"
```

可选的项目范围锁定（推荐）：加一个 `X-LoreDock-Project` 请求头，该 MCP server 的所有工具就只会检索这个项目，工具调用不再需要传 `project` 参数，模型也无法越界：

```bash
claude mcp add --transport http loredock-atlas http://localhost:8080/mcp \
  --header "Authorization: Bearer <LOREDOCK_MCP_WRITE_TOKEN>" \
  --header "X-LoreDock-Project: atlas"
```

多个项目各注册一个实例（server 名不同、header 不同）即可。锁定语义：

- 配置后，工具未传 `project` 时自动使用锁定项目；
- 工具显式传入其他项目被服务端拒绝（`MCP_PROJECT_LOCKED`），不会返回任何数据；
- 未配置时，`project` 参数保持必填（`MCP_PROJECT_REQUIRED`）。

要点（本仓库实测）：

- **不要手写 `.claude/settings.local.json`**：Claude Code 2.1.227 不读取该位置，配置不会加载；
- 该命令把配置写入用户级 `~/.claude.json` 的 `projects["<项目绝对路径>"].mcpServers` 段，只在该项目目录启动时生效；
- Token 同时存在于 `.env`（后端使用）和 `~/.claude.json`（客户端使用），均不在 git 跟踪范围内；若项目克隆到其他机器，需在新机器上重新执行本命令；
- **必须完全退出并重启 Claude Code 进程**（新开对话不算）才会加载新服务器；
- 建议使用写 Token 接入 Claude Code（查询与提交草稿都可用，草稿仍须人工审核）；只读 Token 可分配给 Codex 等其他只读 Agent。

## 4. 验证

```bash
# 检查注册状态与连接健康（会真实发起 MCP 握手）
claude mcp list

# 期望输出：loredock: http://localhost:8080/mcp (HTTP) - ✔ Connected
```

手工握手（替代方法）：

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0.1"}}}'
```

常见排查顺序：

1. 后端是否运行：`curl -s http://localhost:8080/actuator/health` 应返回 200；
2. Token 是否与 `.env` 一致：Token 错误返回 401，连接显示 `failed to connect`；
3. 是否重启了 Claude Code 进程：`/mcp` 面板中应出现 `loredock`。

## 5. 安全边界

- Token 只保存在 `.env`（git 忽略）与本机 `~/.claude.json`，禁止写入仓库或共享配置；
- 写 Token 的最高能力是提交待处理草稿：不创建知识任务、不调用模型、不发布，发布必须走 Web 人工审核；
- Spring AI MCP Server 只扫描 `@McpTool` 标注的 6 个入口（`tool-callback-converter: false`），不会把内部 Agent Tool 暴露给 MCP。
