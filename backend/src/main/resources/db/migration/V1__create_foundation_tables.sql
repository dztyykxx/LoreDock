-- T1 基础结构只通过 Flyway 演进；已执行的版本化迁移禁止修改。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE stored_object (
    id UUID PRIMARY KEY,
    object_key VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT ck_stored_object_status
        CHECK (status IN ('AVAILABLE', 'DELETING')),
    CONSTRAINT ck_stored_object_size
        CHECK (size_bytes >= 0),
    CONSTRAINT ck_stored_object_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stored_object_audit_time
        CHECK (updated_at >= created_at)
);

COMMENT ON TABLE stored_object IS '本地持久化对象的可读取元数据；文件系统与数据库协调时以该记录为读取事实';
COMMENT ON COLUMN stored_object.object_key IS '服务生成的安全对象键，不接受原始文件名作为路径';
COMMENT ON COLUMN stored_object.status IS 'AVAILABLE 可读取，DELETING 正在执行幂等删除';

CREATE TABLE background_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress SMALLINT NOT NULL DEFAULT 0,
    input_object_key VARCHAR(64),
    project_id UUID,
    branch_id UUID,
    snapshot_id UUID,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    owner_instance VARCHAR(128),
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT ck_background_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_background_job_progress
        CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_background_job_audit_time
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_background_job_lifecycle_time
        CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT fk_background_job_input_object
        FOREIGN KEY (input_object_key) REFERENCES stored_object(object_key)
);

COMMENT ON TABLE background_job IS 'MVP 单实例后台工作的持久化状态，不承担分布式调度或自动重试';
COMMENT ON COLUMN background_job.status IS '仅允许 PENDING→RUNNING→SUCCEEDED、FAILED 或 CANCELLED';
COMMENT ON COLUMN background_job.error_message IS '经过脱敏和长度限制的诊断摘要，不保存堆栈或输入正文';

CREATE INDEX idx_background_job_status_created
    ON background_job(status, created_at);

CREATE INDEX idx_background_job_running_heartbeat
    ON background_job(heartbeat_at)
    WHERE status = 'RUNNING';
