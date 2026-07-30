-- T5 知识检索数据只通过追加迁移建立；V1～V4 及既有 ACTIVE 浏览投影保持不变。
-- 测试和多 schema 部署中 vector 扩展可能安装在另一命名空间；只在本事务补充其 schema，建表仍落在当前 schema。
SELECT set_config(
    'search_path',
    quote_ident(current_schema()) || ',' || quote_ident(namespace.nspname),
    true
)
FROM pg_extension extension
JOIN pg_namespace namespace ON namespace.oid = extension.extnamespace
WHERE extension.extname = 'vector';

CREATE TABLE knowledge_search_generation (
    generation_id UUID PRIMARY KEY,
    model_id VARCHAR(200) NOT NULL,
    model_checksum CHAR(64) NOT NULL,
    vector_dimension INTEGER NOT NULL,
    chunk_strategy_version VARCHAR(64) NOT NULL,
    fusion_config_version VARCHAR(64) NOT NULL,
    document_count BIGINT NOT NULL,
    chunk_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_knowledge_search_generation_index_generation
        FOREIGN KEY (generation_id) REFERENCES knowledge_index_generation(id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_search_generation_model
        CHECK (length(model_id) > 0 AND model_checksum ~ '^[0-9A-Fa-f]{64}$'),
    CONSTRAINT ck_knowledge_search_generation_dimension CHECK (vector_dimension = 512),
    CONSTRAINT ck_knowledge_search_generation_versions
        CHECK (length(chunk_strategy_version) > 0 AND length(fusion_config_version) > 0),
    CONSTRAINT ck_knowledge_search_generation_counts
        CHECK (document_count >= 0 AND chunk_count >= document_count)
);

COMMENT ON TABLE knowledge_search_generation IS
    '与知识索引 generation 一对一的完整检索配置；缺少该行的旧 generation 仅支持浏览';
COMMENT ON COLUMN knowledge_search_generation.model_checksum IS
    '离线 ONNX 模型 SHA-256，用于查询和索引模型一致性校验';

CREATE TABLE knowledge_search_chunk (
    generation_id UUID NOT NULL,
    document_id UUID NOT NULL,
    chunk_no INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content TEXT NOT NULL,
    title_terms TEXT NOT NULL,
    tag_terms TEXT NOT NULL,
    content_terms TEXT NOT NULL,
    search_vector TSVECTOR NOT NULL,
    embedding VECTOR(512) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    project_id UUID,
    branch_id UUID,
    format VARCHAR(16) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    normalized_tags TEXT[] NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_knowledge_search_chunk PRIMARY KEY (generation_id, document_id, chunk_no),
    CONSTRAINT fk_knowledge_search_chunk_search_generation
        FOREIGN KEY (generation_id) REFERENCES knowledge_search_generation(generation_id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_search_chunk_index_document
        FOREIGN KEY (generation_id, document_id)
        REFERENCES knowledge_index_document(generation_id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_search_chunk_project
        FOREIGN KEY (project_id) REFERENCES project_space(id),
    CONSTRAINT fk_knowledge_search_chunk_branch
        FOREIGN KEY (branch_id) REFERENCES project_branch(id),
    CONSTRAINT ck_knowledge_search_chunk_number CHECK (chunk_no >= 0),
    CONSTRAINT ck_knowledge_search_chunk_offsets
        CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_knowledge_search_chunk_content CHECK (length(content) > 0),
    CONSTRAINT ck_knowledge_search_chunk_scope
        CHECK (
            (scope_type = 'GLOBAL' AND project_id IS NULL AND branch_id IS NULL)
            OR (scope_type = 'PROJECT' AND project_id IS NOT NULL AND branch_id IS NULL)
            OR (scope_type = 'BRANCH' AND project_id IS NOT NULL AND branch_id IS NOT NULL)
        ),
    CONSTRAINT ck_knowledge_search_chunk_format CHECK (format IN ('MARKDOWN', 'PLAIN_TEXT')),
    CONSTRAINT ck_knowledge_search_chunk_source CHECK (source_type IN ('MANUAL', 'WIKI', 'UPLOAD')),
    CONSTRAINT ck_knowledge_search_chunk_tags CHECK (array_position(normalized_tags, NULL) IS NULL)
);

COMMENT ON TABLE knowledge_search_chunk IS
    '固定 generation 的可追溯知识分块；冗余范围字段使隔离条件在候选 SQL 阶段生效';
COMMENT ON COLUMN knowledge_search_chunk.start_offset IS '相对投影正文的 Unicode code point 起始偏移';
COMMENT ON COLUMN knowledge_search_chunk.end_offset IS '相对投影正文的 Unicode code point 结束偏移（不含）';

CREATE INDEX idx_knowledge_search_chunk_scope
    ON knowledge_search_chunk(
        generation_id, scope_type, project_id, branch_id, format, source_type, document_id, chunk_no
    );

CREATE INDEX idx_knowledge_search_chunk_fulltext
    ON knowledge_search_chunk USING GIN(search_vector);

CREATE INDEX idx_knowledge_search_chunk_tags
    ON knowledge_search_chunk USING GIN(normalized_tags);
