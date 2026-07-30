-- T3 知识文档、导入证据与活动 generation 只通过追加迁移建立；V1/V2 保持不可变。
CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    format VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    directory_path VARCHAR(1000) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    project_id UUID,
    branch_id UUID,
    source_type VARCHAR(16) NOT NULL,
    wiki_url VARCHAR(2000),
    original_filename VARCHAR(512),
    curation_note VARCHAR(2000),
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    replaces_document_id UUID,
    published_at TIMESTAMPTZ,
    published_by VARCHAR(255),
    archived_at TIMESTAMPTZ,
    archived_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    CONSTRAINT fk_knowledge_document_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_knowledge_document_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT fk_knowledge_document_replaces
        FOREIGN KEY (replaces_document_id) REFERENCES knowledge_document(id),
    CONSTRAINT uq_knowledge_document_replaces_document UNIQUE (replaces_document_id),
    CONSTRAINT ck_knowledge_document_format
        CHECK (format IN ('MARKDOWN', 'PLAIN_TEXT')),
    CONSTRAINT ck_knowledge_document_content
        CHECK (length(title) > 0 AND length(body) > 0),
    CONSTRAINT ck_knowledge_document_scope
        CHECK (
            (scope_type = 'GLOBAL' AND project_id IS NULL AND branch_id IS NULL)
            OR (scope_type = 'PROJECT' AND project_id IS NOT NULL AND branch_id IS NULL)
            OR (scope_type = 'BRANCH' AND project_id IS NOT NULL AND branch_id IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_document_source
        CHECK (
            (source_type = 'MANUAL' AND wiki_url IS NULL AND original_filename IS NULL)
            OR (source_type = 'WIKI' AND wiki_url IS NOT NULL)
            OR (source_type = 'UPLOAD' AND wiki_url IS NULL AND original_filename IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_document_revision CHECK (revision > 0),
    CONSTRAINT ck_knowledge_document_replacement_not_self
        CHECK (replaces_document_id IS NULL OR replaces_document_id <> id),
    CONSTRAINT ck_knowledge_document_lifecycle
        CHECK (
            (status = 'DRAFT'
                AND published_at IS NULL AND published_by IS NULL
                AND archived_at IS NULL AND archived_by IS NULL)
            OR (status = 'PUBLISHED'
                AND published_at IS NOT NULL AND published_by IS NOT NULL
                AND archived_at IS NULL AND archived_by IS NULL)
            OR (status = 'ARCHIVED'
                AND archived_at IS NOT NULL AND archived_by IS NOT NULL
                AND ((published_at IS NULL AND published_by IS NULL)
                    OR (published_at IS NOT NULL AND published_by IS NOT NULL)))
        ),
    CONSTRAINT ck_knowledge_document_audit_time
        CHECK (updated_at >= created_at)
);

COMMENT ON TABLE knowledge_document IS '人工审核知识的当前聚合状态；revision 不代表正文版本历史';
COMMENT ON COLUMN knowledge_document.directory_path IS '知识分类逻辑路径，不得作为文件系统路径';
COMMENT ON COLUMN knowledge_document.replaces_document_id IS '当前文档替代的旧文档；唯一约束保护一个旧文档只有一个当前替代者';

CREATE TABLE knowledge_document_tag (
    document_id UUID NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    CONSTRAINT pk_knowledge_document_tag PRIMARY KEY (document_id, normalized_name),
    CONSTRAINT fk_knowledge_document_tag_document
        FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_document_tag_name
        CHECK (length(normalized_name) > 0 AND length(display_name) > 0)
);

CREATE TABLE knowledge_import_batch (
    id UUID PRIMARY KEY,
    object_key VARCHAR(64) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    project_id UUID,
    branch_id UUID,
    directory_prefix VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    succeeded_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    ignored_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    CONSTRAINT fk_knowledge_import_batch_object
        FOREIGN KEY (object_key) REFERENCES stored_object(object_key),
    CONSTRAINT fk_knowledge_import_batch_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_knowledge_import_batch_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT ck_knowledge_import_batch_scope
        CHECK (
            (scope_type = 'GLOBAL' AND project_id IS NULL AND branch_id IS NULL)
            OR (scope_type = 'PROJECT' AND project_id IS NOT NULL AND branch_id IS NULL)
            OR (scope_type = 'BRANCH' AND project_id IS NOT NULL AND branch_id IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_import_batch_status
        CHECK (status IN ('COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_knowledge_import_batch_counts
        CHECK (succeeded_count >= 0 AND failed_count >= 0 AND ignored_count >= 0),
    CONSTRAINT ck_knowledge_import_batch_audit_time
        CHECK (updated_at >= created_at)
);

CREATE TABLE knowledge_import_item (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    entry_name VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    document_id UUID,
    CONSTRAINT uq_knowledge_import_item_batch_ordinal UNIQUE (batch_id, ordinal),
    CONSTRAINT fk_knowledge_import_item_batch
        FOREIGN KEY (batch_id) REFERENCES knowledge_import_batch(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_import_item_document
        FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT ck_knowledge_import_item_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_knowledge_import_item_status
        CHECK (status IN ('SUCCEEDED', 'FAILED', 'IGNORED')),
    CONSTRAINT ck_knowledge_import_item_result_document
        CHECK ((status = 'SUCCEEDED' AND document_id IS NOT NULL)
            OR (status <> 'SUCCEEDED' AND document_id IS NULL))
);

CREATE TABLE knowledge_index_generation (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    document_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_knowledge_index_generation_job UNIQUE (job_id),
    CONSTRAINT fk_knowledge_index_generation_job
        FOREIGN KEY (job_id) REFERENCES background_job(id),
    CONSTRAINT ck_knowledge_index_generation_status
        CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_knowledge_index_generation_count CHECK (document_count >= 0),
    CONSTRAINT ck_knowledge_index_generation_activation
        CHECK ((status = 'BUILDING' AND activated_at IS NULL)
            OR (status IN ('ACTIVE', 'RETIRED') AND activated_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_knowledge_index_generation_single_active
    ON knowledge_index_generation ((status))
    WHERE status = 'ACTIVE';

CREATE TABLE knowledge_index_document (
    generation_id UUID NOT NULL,
    document_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    format VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    directory_path VARCHAR(1000) NOT NULL,
    tags JSONB NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    project_id UUID,
    branch_id UUID,
    source_type VARCHAR(16) NOT NULL,
    wiki_url VARCHAR(2000),
    original_filename VARCHAR(512),
    curation_note VARCHAR(2000),
    source_updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_knowledge_index_document PRIMARY KEY (generation_id, document_id),
    CONSTRAINT fk_knowledge_index_document_generation
        FOREIGN KEY (generation_id) REFERENCES knowledge_index_generation(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_index_document_document
        FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT fk_knowledge_index_document_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_knowledge_index_document_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT ck_knowledge_index_document_revision CHECK (source_revision > 0),
    CONSTRAINT ck_knowledge_index_document_format
        CHECK (format IN ('MARKDOWN', 'PLAIN_TEXT')),
    CONSTRAINT ck_knowledge_index_document_scope
        CHECK (
            (scope_type = 'GLOBAL' AND project_id IS NULL AND branch_id IS NULL)
            OR (scope_type = 'PROJECT' AND project_id IS NOT NULL AND branch_id IS NULL)
            OR (scope_type = 'BRANCH' AND project_id IS NOT NULL AND branch_id IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_index_document_source
        CHECK (
            (source_type = 'MANUAL' AND wiki_url IS NULL AND original_filename IS NULL)
            OR (source_type = 'WIKI' AND wiki_url IS NOT NULL)
            OR (source_type = 'UPLOAD' AND wiki_url IS NULL AND original_filename IS NOT NULL)
        )
);

CREATE INDEX idx_knowledge_document_admin_list
    ON knowledge_document(status, scope_type, directory_path, updated_at DESC, id ASC);

CREATE INDEX idx_knowledge_document_project_scope
    ON knowledge_document(project_id, branch_id, status, updated_at DESC, id ASC);

CREATE INDEX idx_knowledge_document_tag_lookup
    ON knowledge_document_tag(normalized_name, document_id);

CREATE INDEX idx_knowledge_import_batch_created
    ON knowledge_import_batch(created_at DESC, id ASC);

CREATE INDEX idx_knowledge_index_document_scope
    ON knowledge_index_document(generation_id, scope_type, project_id, branch_id, document_id);
