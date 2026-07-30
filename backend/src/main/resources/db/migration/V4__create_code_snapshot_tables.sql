-- T4 代码快照和 Lucene generation 只通过追加迁移建立；V1～V3 保持不可变。
CREATE TABLE code_snapshot (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    commit_hash VARCHAR(64) NOT NULL,
    input_object_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    previous_snapshot_id UUID,
    indexed_file_count BIGINT NOT NULL DEFAULT 0,
    ignored_file_count BIGINT NOT NULL DEFAULT 0,
    indexed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT uq_code_snapshot_input_object UNIQUE (input_object_key),
    CONSTRAINT fk_code_snapshot_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_code_snapshot_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT fk_code_snapshot_input_object
        FOREIGN KEY (input_object_key) REFERENCES stored_object(object_key),
    CONSTRAINT fk_code_snapshot_previous
        FOREIGN KEY (previous_snapshot_id) REFERENCES code_snapshot(id),
    CONSTRAINT ck_code_snapshot_commit
        CHECK (commit_hash ~ '^[0-9a-f]{7,64}$'),
    CONSTRAINT ck_code_snapshot_status
        CHECK (status IN ('CANDIDATE', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT ck_code_snapshot_counts
        CHECK (indexed_file_count >= 0 AND ignored_file_count >= 0),
    CONSTRAINT ck_code_snapshot_previous_not_self
        CHECK (previous_snapshot_id IS NULL OR previous_snapshot_id <> id),
    CONSTRAINT ck_code_snapshot_activation
        CHECK ((status IN ('CANDIDATE', 'FAILED') AND indexed_at IS NULL)
            OR (status IN ('ACTIVE', 'RETIRED') AND indexed_at IS NOT NULL)),
    CONSTRAINT ck_code_snapshot_audit_time
        CHECK (updated_at >= created_at)
);

COMMENT ON TABLE code_snapshot IS '指定项目分支和声明 commit 的代码快照业务生命周期；只有 ACTIVE 可进入普通查询';
COMMENT ON COLUMN code_snapshot.input_object_key IS '可重建原始 ZIP 的不透明对象键，普通响应和日志不得返回';
COMMENT ON COLUMN code_snapshot.previous_snapshot_id IS '前一成功活动快照，仅用于 INITIAL/CHANGED/UNCHANGED 提示而非完整 diff';

CREATE UNIQUE INDEX uq_code_snapshot_branch_active
    ON code_snapshot(branch_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_code_snapshot_admin_list
    ON code_snapshot(project_id, branch_id, created_at DESC, id ASC);

ALTER TABLE background_job
    ADD CONSTRAINT fk_background_job_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    ADD CONSTRAINT fk_background_job_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    ADD CONSTRAINT fk_background_job_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES code_snapshot(id);

-- 构建与重建共享这一数据库排他入口，避免重启或意外多实例让同分支任务竞速激活。
CREATE UNIQUE INDEX uq_background_job_code_branch_active
    ON background_job(branch_id)
    WHERE job_type IN ('CODE_SNAPSHOT_BUILD', 'CODE_SNAPSHOT_REINDEX')
      AND status IN ('PENDING', 'RUNNING');

CREATE TABLE code_index_generation (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL,
    job_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    document_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_code_index_generation_job UNIQUE (job_id),
    CONSTRAINT fk_code_index_generation_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES code_snapshot(id),
    CONSTRAINT fk_code_index_generation_job
        FOREIGN KEY (job_id) REFERENCES background_job(id),
    CONSTRAINT ck_code_index_generation_status
        CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT ck_code_index_generation_count
        CHECK (document_count >= 0),
    CONSTRAINT ck_code_index_generation_activation
        CHECK ((status IN ('BUILDING', 'FAILED') AND activated_at IS NULL)
            OR (status IN ('ACTIVE', 'RETIRED') AND activated_at IS NOT NULL))
);

COMMENT ON TABLE code_index_generation IS '一次代码索引任务生成的独立 Lucene generation；数据库只激活已发布且验证可读的目录';
COMMENT ON COLUMN code_index_generation.id IS '同时作为服务端生成的物理目录名，任何客户端都不能指定';

CREATE UNIQUE INDEX uq_code_index_generation_snapshot_active
    ON code_index_generation(snapshot_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_code_index_generation_snapshot_created
    ON code_index_generation(snapshot_id, created_at DESC, id ASC);
