-- T2 项目与分支主数据只通过追加迁移建立；V1 基础结构保持不可变。
CREATE TABLE project_space (
    id UUID PRIMARY KEY,
    identifier VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    technology_stack VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT uq_project_space_identifier UNIQUE (identifier),
    CONSTRAINT ck_project_space_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_project_space_audit_time CHECK (updated_at >= created_at)
);

CREATE TABLE project_branch (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    CONSTRAINT uq_project_branch_project_name UNIQUE (project_id, name),
    CONSTRAINT fk_project_branch_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT ck_project_branch_audit_time CHECK (updated_at >= created_at)
);

COMMENT ON TABLE project_space IS '知识、代码快照、检索与问答共用的项目范围主数据';
COMMENT ON COLUMN project_space.identifier IS '经领域校验的小写 kebab-case 稳定业务标识';
COMMENT ON COLUMN project_space.status IS 'ENABLED 对普通入口可见，DISABLED 仅管理入口可见且不删除数据';
COMMENT ON TABLE project_branch IS '项目内分支范围；后续隔离使用项目与分支 UUID，不使用名称拼接路径';
COMMENT ON COLUMN project_branch.name IS '保留大小写的 Git 风格分支名，不得直接作为文件路径';

CREATE INDEX idx_project_space_status_name
    ON project_space(status, name, id);

CREATE INDEX idx_project_branch_project_name
    ON project_branch(project_id, name, id);
