package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

/**
 * 知识索引文档投影 Mapper；投影只供检索读取和 generation 构建写入。
 */
@Mapper
public interface KnowledgeIndexDocumentMapper extends BaseMapper<KnowledgeIndexDocumentEntity> {

    /** 插入一条不可变投影；JSON 标签显式转换为 PostgreSQL jsonb。 */
    @Insert("""
            insert into knowledge_index_document(
                generation_id, document_id, source_revision, format, title, body, directory_path, tags,
                scope_type, project_id, branch_id, source_type, wiki_url, original_filename,
                curation_note, source_updated_at)
            values (#{generationId}, #{documentId}, #{sourceRevision}, #{format}, #{title}, #{body},
                #{directoryPath}, cast(#{tags} as jsonb), #{scopeType}, #{projectId}, #{branchId},
                #{sourceType}, #{wikiUrl}, #{originalFilename}, #{curationNote}, #{sourceUpdatedAt})
            """)
    int insertProjection(KnowledgeIndexDocumentEntity entity);
}
