package io.github.loredock.eval;

import io.github.loredock.eval.AtlasAgentEvalFixture.DocumentSpec;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.service.KnowledgeDocumentDataService;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import io.github.loredock.support.TestIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Atlas Agent 评估环境种子：按 manifest 固定 Long ID 写入项目、分支、正式知识与候选草稿，
 * 并真实重建知识索引，使评估运行与生产链路一致。
 */
public final class AtlasAgentEvalSeeder {

    /** 评估固定项目标识；数据集不包含项目/分支 ID，由评估程序生成。 */
    public static final Long PROJECT_ID = 7600000000000000001L;
    public static final Long BRANCH_ID = 7600000000000000002L;
    private static final String PROJECT_IDENTIFIER = "atlas";
    private static final String BRANCH_NAME = "main";
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentDataService documents;
    private final KnowledgeIndexRebuildService rebuilder;

    /**
     * @param dataSource 测试数据库数据源
     * @param documents 知识文档数据服务
     * @param rebuilder 知识索引重建服务
     */
    public AtlasAgentEvalSeeder(
            DataSource dataSource,
            KnowledgeDocumentDataService documents,
            KnowledgeIndexRebuildService rebuilder
    ) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.documents = documents;
        this.rebuilder = rebuilder;
    }

    /** 清空评估相关业务表，保持数据库与数据集完全一致；外键顺序与运行器写入顺序对应。 */
    public void resetDatabase() {
        for (String table : List.of(
                "web_qa_message", "web_qa_question", "web_qa_conversation",
                "agent_run_retrieval",
                "knowledge_draft_revision_source", "knowledge_draft_revision", "knowledge_draft",
                "knowledge_task_message", "knowledge_task_selected_draft",
                "agent_evidence", "agent_run_event", "agent_run", "knowledge_task_conversation",
                "knowledge_search_chunk", "knowledge_index_generation", "knowledge_document", "background_job",
                "project_branch", "project_space", "stored_object")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    /**
     * 写入评估环境并重建索引：项目/分支、全部已映射文档（正式知识发布、候选草稿保持 DRAFT）。
     *
     * @param data 已校验的评估数据集
     */
    public void seed(EvalData data) {
        seedProject();
        for (DocumentSpec document : data.documents()) {
            if (document.documentId() != null) {
                insertDocument(document);
            }
        }
        rebuildIndex();
    }

    /** 写入 atlas 项目与 main 分支。 */
    public void seedProject() {
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, 'Atlas', '公开模拟评估项目', 'Java 21', 'ENABLED', ?, ?, 'agent-eval', 'agent-eval')
                """, PROJECT_ID, PROJECT_IDENTIFIER, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'agent-eval', 'agent-eval')
                """, BRANCH_ID, PROJECT_ID, BRANCH_NAME, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertDocument(DocumentSpec document) {
        KnowledgeDocument created = KnowledgeDocument.create(document.documentId(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(document.title()), new DocumentBody(document.markdown()),
                new DocumentDirectory(document.directory()), DocumentTags.of(List.of()),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "agent-eval"),
                KnowledgeScope.project(PROJECT_ID)), new DocumentAudit(NOW, "agent-eval"));
        KnowledgeDocument toInsert = "PUBLISHED".equals(document.status())
                ? created.publish(new DocumentAudit(NOW.plusSeconds(1), "agent-eval"))
                : created;
        documents.insert(toInsert);
    }

    /** 插入重建任务并真实重建索引，返回任务 ID。 */
    public Long rebuildIndex() {
        Long jobId = TestIds.next();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'RUNNING', 0, ?, ?, 'agent-eval', 'agent-eval')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        rebuilder.rebuild(jobId, new KnowledgeIndexRebuildService.Progress(percentage -> { }, () -> { }));
        return jobId;
    }
}
