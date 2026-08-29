ALTER TABLE knowledge_tool_invocation ADD COLUMN agent_node VARCHAR(64);

CREATE INDEX idx_knowledge_tool_agent ON knowledge_tool_invocation(run_id, agent_node, sequence);

COMMENT ON COLUMN knowledge_tool_invocation.agent_node IS '执行该工具调用的 Agent 节点名(coordinator/retriever/drafter/reviewer)，用于按 Agent 归组展示（工具运行中即可归属，避免仅靠阶段事件时间推断在中途误归到上一 Agent）';
