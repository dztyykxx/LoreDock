-- T7 只追加 Web 问答、知识缺口反馈和回答依据；V1～V6 的正式事实结构保持不变。
ALTER TABLE agent_run
    ADD COLUMN answer_basis VARCHAR(32);

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_answer_basis
        CHECK (answer_basis IS NULL OR answer_basis IN (
            'BUSINESS_RULE', 'CURRENT_IMPLEMENTATION', 'MIXED'
        ));

COMMENT ON COLUMN agent_run.answer_basis IS '运行完成时固定的回答依据；迁移前运行保持 NULL 并由安全引用类型推导';

CREATE TABLE web_qa_question (
    id UUID PRIMARY KEY,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    project_id UUID NOT NULL,
    project_identifier VARCHAR(64) NOT NULL,
    branch_id UUID NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    run_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_web_qa_question_operator_key UNIQUE (operator_id, idempotency_key),
    CONSTRAINT uq_web_qa_question_run UNIQUE (run_id),
    CONSTRAINT uq_web_qa_question_run_id UNIQUE (run_id, id),
    CONSTRAINT fk_web_qa_question_project FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_web_qa_question_branch FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT fk_web_qa_question_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT ck_web_qa_question_operator CHECK (length(operator_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_web_qa_question_key CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_web_qa_question_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_web_qa_question_scope CHECK (
        length(project_identifier) BETWEEN 1 AND 64
        AND length(branch_name) BETWEEN 1 AND 255
    )
);

COMMENT ON TABLE web_qa_question IS '操作者提交的一次项目问答身份与固定范围；问题正文保存在唯一 USER 消息';
CREATE INDEX idx_web_qa_question_history
    ON web_qa_question(operator_id, project_id, created_at DESC, id DESC);

CREATE TABLE web_qa_message (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    result_type VARCHAR(16),
    refusal_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_web_qa_message_role UNIQUE (question_id, role),
    CONSTRAINT fk_web_qa_message_question
        FOREIGN KEY (question_id) REFERENCES web_qa_question(id) ON DELETE CASCADE,
    CONSTRAINT ck_web_qa_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_web_qa_message_content CHECK (
        length(content) > 0 AND octet_length(content) <= 48000
    ),
    CONSTRAINT ck_web_qa_message_result CHECK (
        (role = 'USER' AND result_type IS NULL AND refusal_reason IS NULL)
        OR (role = 'ASSISTANT' AND result_type = 'ANSWER' AND refusal_reason IS NULL)
        OR (role = 'ASSISTANT' AND result_type = 'REFUSAL' AND refusal_reason IS NOT NULL)
    )
);

COMMENT ON TABLE web_qa_message IS '单次问答的用户原问题与可恢复终态公开消息；不保存阶段输出或思维链';
CREATE INDEX idx_web_qa_message_question_created
    ON web_qa_message(question_id, created_at, id);

CREATE TABLE knowledge_gap_feedback (
    id UUID PRIMARY KEY,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    project_id UUID NOT NULL,
    project_identifier VARCHAR(64) NOT NULL,
    branch_id UUID NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    question_id UUID,
    run_id UUID,
    gap_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    question_text TEXT NOT NULL,
    note TEXT,
    result_type VARCHAR(16),
    refusal_reason VARCHAR(64),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT uq_knowledge_gap_operator_key UNIQUE (operator_id, idempotency_key),
    CONSTRAINT uq_knowledge_gap_run_id UNIQUE (run_id, id),
    CONSTRAINT fk_knowledge_gap_project FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_knowledge_gap_branch FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT fk_knowledge_gap_question_run
        FOREIGN KEY (run_id, question_id) REFERENCES web_qa_question(run_id, id),
    CONSTRAINT ck_knowledge_gap_operator CHECK (
        length(operator_id) BETWEEN 1 AND 128
        AND length(created_by) BETWEEN 1 AND 128
        AND length(updated_by) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_knowledge_gap_key CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_knowledge_gap_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_gap_scope CHECK (
        length(project_identifier) BETWEEN 1 AND 64
        AND length(branch_name) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_knowledge_gap_relation CHECK (
        (question_id IS NULL AND run_id IS NULL)
        OR (question_id IS NOT NULL AND run_id IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_gap_type CHECK (gap_type IN (
        'NO_ANSWER', 'WRONG_ANSWER', 'OUTDATED_KNOWLEDGE'
    )),
    CONSTRAINT ck_knowledge_gap_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'CLOSED')),
    CONSTRAINT ck_knowledge_gap_question CHECK (
        length(question_text) > 0 AND octet_length(question_text) <= 8000
    ),
    CONSTRAINT ck_knowledge_gap_note CHECK (note IS NULL OR octet_length(note) <= 8000),
    CONSTRAINT ck_knowledge_gap_result CHECK (
        (result_type IS NULL AND refusal_reason IS NULL)
        OR (result_type = 'ANSWER' AND refusal_reason IS NULL)
        OR (result_type = 'REFUSAL' AND refusal_reason IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_gap_time CHECK (updated_at >= created_at)
);

COMMENT ON TABLE knowledge_gap_feedback IS '成员提交并由管理员单向处理的知识缺口；不自动修改知识或索引';
CREATE INDEX idx_knowledge_gap_feedback_cursor
    ON knowledge_gap_feedback(created_at DESC, id DESC);
CREATE INDEX idx_knowledge_gap_feedback_filter
    ON knowledge_gap_feedback(project_id, branch_id, status, gap_type, created_at DESC, id DESC);

CREATE TABLE knowledge_gap_feedback_citation (
    id UUID PRIMARY KEY,
    feedback_id UUID NOT NULL,
    run_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    citation_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_knowledge_gap_citation_order UNIQUE (feedback_id, citation_order),
    CONSTRAINT uq_knowledge_gap_citation_evidence UNIQUE (feedback_id, evidence_id),
    CONSTRAINT fk_knowledge_gap_citation_feedback
        FOREIGN KEY (run_id, feedback_id)
        REFERENCES knowledge_gap_feedback(run_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_gap_citation_evidence
        FOREIGN KEY (run_id, evidence_id)
        REFERENCES agent_evidence(run_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_gap_citation_order CHECK (citation_order > 0)
);

COMMENT ON TABLE knowledge_gap_feedback_citation IS '反馈关联的运行时证据快照；复合外键阻止跨运行拼接来源';
CREATE INDEX idx_knowledge_gap_citation_feedback
    ON knowledge_gap_feedback_citation(feedback_id, citation_order);
