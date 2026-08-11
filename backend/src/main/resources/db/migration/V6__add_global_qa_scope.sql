-- 全局范围（全库问答 / 全局知识整理）支持：会话、问题、Agent 运行与知识整理工作区解除项目强制归属。
-- 范围统一由 project_id IS NULL 表达；project_identifier/branch_name 保持 NOT NULL，
-- GLOBAL 行写入哨兵 'GLOBAL'/'global'（满足既有长度 CHECK，且真实项目标识为小写风格，无碰撞）。
-- task_type 复用既有值（project_qa / knowledge_curation），ck_agent_run_lifecycle 不约束 project_id，无需重建。

ALTER TABLE web_qa_conversation ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE web_qa_question ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE web_qa_question ALTER COLUMN branch_id DROP NOT NULL;
ALTER TABLE agent_run ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE agent_run ALTER COLUMN branch_id DROP NOT NULL;
ALTER TABLE knowledge_task_conversation ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE knowledge_draft ALTER COLUMN project_id DROP NOT NULL;

-- 跨项目最近会话列表（首页侧栏与全局问答页）：按操作者 + 最后问题时间倒序，无项目前缀。
CREATE INDEX idx_web_qa_conversation_operator_history
    ON web_qa_conversation(operator_id, last_question_at DESC, id DESC);

COMMENT ON COLUMN web_qa_conversation.project_id IS 'GLOBAL 会话为 NULL；范围由空 project_id 与哨兵 project_identifier 共同表达';
COMMENT ON COLUMN web_qa_question.project_id IS 'GLOBAL 轮次为 NULL，branch_id 同';
COMMENT ON COLUMN agent_run.project_id IS 'GLOBAL 运行为 NULL（project_qa 全库问答 / knowledge_curation 全局整理），branch_id 同';
COMMENT ON COLUMN knowledge_task_conversation.project_id IS 'GLOBAL 知识整理任务为 NULL';
COMMENT ON COLUMN knowledge_draft.project_id IS 'GLOBAL 知识整理草稿工作区为 NULL';
