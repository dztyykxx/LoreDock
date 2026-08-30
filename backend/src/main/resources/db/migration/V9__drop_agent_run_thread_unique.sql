-- 知识整理多路会话编排（multiagent-conversation-orchestration）阶段 1：
-- Graph threadId 从“每个 run 随机唯一”改为“同一会话所有 run 共享 `knowledge-task-conversation-{conversationId}`”，
-- 多个 run 的 thread_id 会重复，旧唯一约束 uq_agent_run_thread 不再成立。
-- thread_id 仍受 ck_agent_run_thread_pair 的长度与会话配对检查（V3）约束。
ALTER TABLE agent_run DROP CONSTRAINT uq_agent_run_thread;
