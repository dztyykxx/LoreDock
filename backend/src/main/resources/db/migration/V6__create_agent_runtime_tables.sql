-- T6A 单 Agent 只追加运行事实、有限证据和版本化 Skill 元数据；不修改 V1～V5 正式知识与代码结构。
CREATE TABLE agent_skill_version (
    id UUID PRIMARY KEY,
    skill_name VARCHAR(64) NOT NULL,
    skill_version VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    object_key VARCHAR(64) NOT NULL,
    output_schema_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_skill_name_version UNIQUE (skill_name, skill_version),
    CONSTRAINT fk_agent_skill_object FOREIGN KEY (object_key) REFERENCES stored_object(object_key),
    CONSTRAINT ck_agent_skill_name CHECK (skill_name ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT ck_agent_skill_version CHECK (skill_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_agent_skill_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_skill_schema CHECK (length(output_schema_version) BETWEEN 1 AND 64),
    CONSTRAINT ck_agent_skill_status CHECK (status IN ('ENABLED', 'RETIRED'))
);

COMMENT ON TABLE agent_skill_version IS '内置 Skill 的不可变版本元数据；数据库记录是启用版本与对象内容定位的事实来源';
COMMENT ON COLUMN agent_skill_version.object_key IS 'Skill Markdown 与输出 schema 的不透明对象键，不得写入公开事件和日志';

CREATE UNIQUE INDEX uq_agent_skill_single_enabled
    ON agent_skill_version(skill_name)
    WHERE status = 'ENABLED';

CREATE TABLE agent_run (
    id UUID PRIMARY KEY,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    question_hash CHAR(64) NOT NULL,
    question_length INTEGER NOT NULL,
    project_id UUID NOT NULL,
    project_identifier VARCHAR(64) NOT NULL,
    branch_id UUID NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    snapshot_id UUID,
    commit_hash VARCHAR(64),
    knowledge_generation_id UUID,
    skill_version_id UUID NOT NULL,
    skill_name VARCHAR(64) NOT NULL,
    skill_version VARCHAR(32) NOT NULL,
    skill_content_hash CHAR(64) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    output_schema_version VARCHAR(64) NOT NULL,
    tool_policy_version VARCHAR(64) NOT NULL,
    limit_policy_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_type VARCHAR(16),
    result_text VARCHAR(12000),
    refusal_reason VARCHAR(64),
    error_code VARCHAR(64),
    event_sequence BIGINT NOT NULL DEFAULT 0,
    step_count INTEGER NOT NULL DEFAULT 0,
    model_call_count INTEGER NOT NULL DEFAULT 0,
    retrieval_count INTEGER NOT NULL DEFAULT 0,
    trimmed_character_count INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT,
    output_tokens BIGINT,
    elapsed_millis BIGINT,
    accepted_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_run_operator_idempotency UNIQUE (operator_id, idempotency_key),
    CONSTRAINT fk_agent_run_project FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_agent_run_branch FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT fk_agent_run_snapshot FOREIGN KEY (snapshot_id) REFERENCES code_snapshot(id),
    CONSTRAINT fk_agent_run_knowledge_generation
        FOREIGN KEY (knowledge_generation_id) REFERENCES knowledge_search_generation(generation_id),
    CONSTRAINT fk_agent_run_skill FOREIGN KEY (skill_version_id) REFERENCES agent_skill_version(id),
    CONSTRAINT ck_agent_run_operator CHECK (length(operator_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_agent_run_idempotency CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_agent_run_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND question_hash ~ '^[0-9a-f]{64}$'
        AND skill_content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_run_task CHECK (task_type = 'project_qa'),
    CONSTRAINT ck_agent_run_question_length CHECK (question_length BETWEEN 1 AND 2000),
    CONSTRAINT ck_agent_run_scope_text CHECK (
        length(project_identifier) BETWEEN 1 AND 64 AND length(branch_name) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_agent_run_snapshot_pair CHECK (
        (snapshot_id IS NULL AND commit_hash IS NULL)
        OR (snapshot_id IS NOT NULL AND commit_hash ~ '^[0-9a-f]{7,64}$')
    ),
    CONSTRAINT ck_agent_run_versions CHECK (
        length(skill_name) > 0 AND length(skill_version) > 0
        AND length(model_provider) > 0 AND length(model_name) > 0
        AND length(output_schema_version) > 0
        AND length(tool_policy_version) > 0 AND length(limit_policy_version) > 0
    ),
    CONSTRAINT ck_agent_run_counts CHECK (
        event_sequence >= 0 AND step_count >= 0 AND model_call_count >= 0 AND retrieval_count >= 0
        AND trimmed_character_count >= 0
        AND (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (elapsed_millis IS NULL OR elapsed_millis >= 0)
    ),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('ACCEPTED', 'RUNNING', 'COMPLETED', 'FAILED', 'TERMINATED')),
    CONSTRAINT ck_agent_run_result_type CHECK (result_type IS NULL OR result_type IN ('ANSWER', 'REFUSAL')),
    CONSTRAINT ck_agent_run_lifecycle CHECK (
        (status = 'ACCEPTED'
            AND started_at IS NULL AND finished_at IS NULL
            AND result_type IS NULL AND result_text IS NULL AND refusal_reason IS NULL AND error_code IS NULL)
        OR (status = 'RUNNING'
            AND started_at IS NOT NULL AND finished_at IS NULL
            AND result_type IS NULL AND result_text IS NULL AND refusal_reason IS NULL AND error_code IS NULL)
        OR (status = 'COMPLETED'
            AND started_at IS NOT NULL AND finished_at IS NOT NULL AND error_code IS NULL
            AND result_type IS NOT NULL AND result_text IS NOT NULL AND length(result_text) > 0
            AND ((result_type = 'ANSWER' AND refusal_reason IS NULL)
                OR (result_type = 'REFUSAL' AND refusal_reason IS NOT NULL)))
        OR (status IN ('FAILED', 'TERMINATED')
            AND finished_at IS NOT NULL AND error_code IS NOT NULL
            AND result_type IS NULL AND result_text IS NULL AND refusal_reason IS NULL)
    ),
    CONSTRAINT ck_agent_run_time CHECK (
        updated_at >= accepted_at
        AND (started_at IS NULL OR started_at >= accepted_at)
        AND (finished_at IS NULL OR finished_at >= accepted_at)
    )
);

COMMENT ON TABLE agent_run IS '运行状态、固定版本与范围的持久化事实；完整问题只保存 SHA-256 和 Unicode 长度';
COMMENT ON COLUMN agent_run.question_hash IS '用于幂等比较的完整问题摘要，不保存问题原文';
COMMENT ON COLUMN agent_run.input_tokens IS '模型未提供 usage 时保持 NULL，不能伪造为零';

CREATE INDEX idx_agent_run_project_accepted ON agent_run(project_id, accepted_at DESC, id);
CREATE INDEX idx_agent_run_non_terminal ON agent_run(status, accepted_at) WHERE status IN ('ACCEPTED', 'RUNNING');

CREATE TABLE agent_run_event (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_run_event_sequence UNIQUE (run_id, sequence),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_run_event_sequence CHECK (sequence > 0),
    CONSTRAINT ck_agent_run_event_type CHECK (event_type IN (
        'RUN_ACCEPTED', 'RUN_STARTED', 'SKILL_PINNED', 'MODEL_STARTED',
        'TOOL_STARTED', 'TOOL_COMPLETED', 'SOURCE_FOUND', 'ANSWER_DELTA',
        'REFUSAL', 'RUN_COMPLETED', 'RUN_FAILED', 'RUN_TERMINATED'
    )),
    CONSTRAINT ck_agent_run_event_payload_size CHECK (octet_length(payload::text) <= 16384)
);

COMMENT ON TABLE agent_run_event IS '先提交到数据库再供下游按序读取的公开事件；禁止隐藏思维链和完整证据正文';
CREATE INDEX idx_agent_run_event_after_sequence ON agent_run_event(run_id, sequence);

CREATE TABLE agent_tool_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    call_sequence INTEGER NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    argument_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_count INTEGER NOT NULL DEFAULT 0,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_tool_call_sequence UNIQUE (run_id, call_sequence),
    CONSTRAINT fk_agent_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_tool_call_sequence CHECK (call_sequence > 0),
    CONSTRAINT ck_agent_tool_name CHECK (tool_name IN ('knowledge_search', 'code_search', 'code_snippet_read')),
    CONSTRAINT ck_agent_tool_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_agent_tool_counts CHECK (result_count >= 0 AND evidence_count >= 0),
    CONSTRAINT ck_agent_tool_summary_size CHECK (octet_length(argument_summary::text) <= 16384),
    CONSTRAINT ck_agent_tool_lifecycle CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)
    )
);

COMMENT ON TABLE agent_tool_call IS '只保存工具名、有限计数和脱敏参数摘要，不保存原始检索正文或服务器路径';

CREATE TABLE agent_evidence (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    evidence_key VARCHAR(32) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    retained BOOLEAN NOT NULL,
    relevance DOUBLE PRECISION NOT NULL,
    document_id UUID,
    snapshot_id UUID,
    project_identifier VARCHAR(64) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    commit_hash VARCHAR(64),
    repository_path VARCHAR(1000),
    title VARCHAR(200),
    source_updated_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_evidence_key UNIQUE (run_id, evidence_key),
    CONSTRAINT uq_agent_evidence_run_id UNIQUE (run_id, id),
    CONSTRAINT fk_agent_evidence_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_evidence_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT fk_agent_evidence_snapshot FOREIGN KEY (snapshot_id) REFERENCES code_snapshot(id),
    CONSTRAINT ck_agent_evidence_key CHECK (evidence_key ~ '^E[1-9][0-9]{0,8}$'),
    CONSTRAINT ck_agent_evidence_type CHECK (source_type IN ('KNOWLEDGE', 'CODE')),
    CONSTRAINT ck_agent_evidence_relevance CHECK (relevance >= 0.0 AND relevance <= 1.0),
    CONSTRAINT ck_agent_evidence_scope_text CHECK (
        length(project_identifier) BETWEEN 1 AND 64 AND length(branch_name) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_agent_evidence_source CHECK (
        (source_type = 'KNOWLEDGE'
            AND document_id IS NOT NULL AND snapshot_id IS NULL
            AND commit_hash IS NULL AND repository_path IS NULL
            AND title IS NOT NULL AND length(title) > 0)
        OR (source_type = 'CODE'
            AND document_id IS NULL AND snapshot_id IS NOT NULL
            AND commit_hash ~ '^[0-9a-f]{7,64}$'
            AND repository_path IS NOT NULL AND length(repository_path) BETWEEN 1 AND 1000)
    ),
    CONSTRAINT ck_agent_evidence_metadata_size CHECK (octet_length(metadata::text) <= 16384)
);

COMMENT ON TABLE agent_evidence IS '运行内有限证据台账，只保存引用来源和摘要元数据，正文仅存在于有界模型上下文';
COMMENT ON COLUMN agent_evidence.repository_path IS '仓库相对路径，禁止服务器绝对路径';
CREATE INDEX idx_agent_evidence_run ON agent_evidence(run_id, evidence_key);

CREATE TABLE agent_citation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    citation_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_citation_order UNIQUE (run_id, citation_order),
    CONSTRAINT uq_agent_citation_evidence UNIQUE (run_id, evidence_id),
    CONSTRAINT fk_agent_citation_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_citation_run_evidence
        FOREIGN KEY (run_id, evidence_id) REFERENCES agent_evidence(run_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_citation_order CHECK (citation_order > 0)
);

COMMENT ON TABLE agent_citation IS '通过复合外键保证最终引用只能指向同一运行实际登记的证据';
