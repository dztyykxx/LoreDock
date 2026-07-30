package io.github.loredock.knowledge.infrastructure.persistence;

import io.github.loredock.knowledge.application.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.application.AdminKnowledgeDocumentQueryUseCase;
import io.github.loredock.knowledge.application.KnowledgeBrowseContext;
import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.KnowledgeDocumentRepository;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexBatch;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexQuery;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexReader;
import io.github.loredock.knowledge.application.PublishedKnowledgeEligibilityReader;
import io.github.loredock.knowledge.domain.DocumentAudit;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeDocument;
import io.github.loredock.knowledge.domain.KnowledgeDocumentFields;
import io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeIndexProjectionRepositoryIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_index_projection_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private AdminKnowledgeDocumentQueryUseCase adminQueries;

    @Autowired
    private PublishedKnowledgeIndexReader indexReader;

    @Autowired
    private PublishedKnowledgeEligibilityReader eligibility;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstProjectId;
    private UUID firstMainBranchId;
    private UUID firstFeatureBranchId;
    private UUID secondProjectId;
    private UUID secondMainBranchId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.mcp.token-sha256", () -> "a".repeat(64));
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "成员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        firstProjectId = insertProject("first-project");
        firstMainBranchId = insertBranch(firstProjectId, "main");
        firstFeatureBranchId = insertBranch(firstProjectId, "feature/a");
        secondProjectId = insertProject("second-project");
        secondMainBranchId = insertBranch(secondProjectId, "main");
    }

    /**
     * 业务目的：同步状态只能由当前修订与活动投影派生，草稿不适用、无活动代次从未索引、缺项待同步、相等同步、旧修订过期。
     */
    @Test
    void syncStatusIsDerivedFromActiveProjectionWithoutWritingIndexedRevision() {
        KnowledgeDocument published = published(id(1), "Published", KnowledgeScope.global());
        KnowledgeDocument draft = draft(id(2), "Draft", KnowledgeScope.global());
        documents.insert(published);
        documents.insert(draft);

        Map<UUID, KnowledgeIndexSyncStatus> withoutGeneration = statuses();
        assertThat(withoutGeneration.get(published.id())).isEqualTo(KnowledgeIndexSyncStatus.NEVER_INDEXED);
        assertThat(withoutGeneration.get(draft.id())).isEqualTo(KnowledgeIndexSyncStatus.NOT_APPLICABLE);

        UUID generationId = insertGeneration("ACTIVE");
        assertThat(statuses().get(published.id())).isEqualTo(KnowledgeIndexSyncStatus.PENDING);
        insertProjection(generationId, published);
        assertThat(statuses().get(published.id())).isEqualTo(KnowledgeIndexSyncStatus.SYNCED);

        KnowledgeDocument edited = published.edit(new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle("Published edited"), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")), manual(), KnowledgeScope.global()),
                new DocumentAudit(NOW.plusSeconds(2), "editor"));
        assertThat(documents.update(edited, published.revision())).isTrue();
        assertThat(statuses().get(published.id())).isEqualTo(KnowledgeIndexSyncStatus.STALE);
        assertThat(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name='knowledge_document'",
                String.class)).doesNotContain("indexed_revision");
    }

    /**
     * 业务目的：数据库最多允许一个 ACTIVE，读取器只返回当前代次且在 SQL 中应用通用/项目/当前分支联合范围和主键游标。
     */
    @Test
    void activeGenerationIsUniqueAndReaderAppliesScopeBeforeStableBatching() {
        List<KnowledgeDocument> indexed = List.of(
                published(id(11), "Global", KnowledgeScope.global()),
                published(id(12), "Project", KnowledgeScope.project(firstProjectId)),
                published(id(13), "Main", KnowledgeScope.branch(firstProjectId, firstMainBranchId)),
                published(id(14), "Feature", KnowledgeScope.branch(firstProjectId, firstFeatureBranchId)),
                published(id(15), "Other", KnowledgeScope.branch(secondProjectId, secondMainBranchId)));
        indexed.forEach(documents::insert);
        UUID generationId = insertGeneration("ACTIVE");
        indexed.forEach(document -> insertProjection(generationId, document));

        assertThatThrownBy(() -> insertGeneration("ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        PublishedKnowledgeIndexQuery firstQuery = new PublishedKnowledgeIndexQuery(
                new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, firstProjectId, firstMainBranchId),
                null, 2);
        PublishedKnowledgeIndexBatch first = indexReader.read(firstQuery);
        PublishedKnowledgeIndexBatch second = indexReader.read(new PublishedKnowledgeIndexQuery(
                firstQuery.context(), first.nextAfterDocumentId(), 2));

        assertThat(first.generationId()).isEqualTo(generationId);
        assertThat(first.documents()).extracting(document -> document.documentId()).containsExactly(id(11), id(12));
        assertThat(first.hasMore()).isTrue();
        assertThat(second.documents()).extracting(document -> document.documentId()).containsExactly(id(13));
        assertThat(second.hasMore()).isFalse();
    }

    /**
     * 业务目的：旧 ACTIVE 仍含归档文档时，实时资格端口必须再次按状态和范围排除，不能把投影当授权事实。
     */
    @Test
    void archivedDocumentRemainingInActiveProjectionIsExcludedByRealtimeEligibility() {
        KnowledgeDocument published = published(id(21), "Soon archived", KnowledgeScope.global());
        documents.insert(published);
        UUID generationId = insertGeneration("ACTIVE");
        insertProjection(generationId, published);
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null);
        assertThat(indexReader.read(new PublishedKnowledgeIndexQuery(context, null, 10)).documents())
                .extracting(document -> document.documentId()).containsExactly(published.id());

        KnowledgeDocument archived = published.archive(new DocumentAudit(NOW.plusSeconds(3), "archiver"));
        assertThat(documents.update(archived, published.revision())).isTrue();

        assertThat(eligibility.retainEligible(List.of(published.id()), context)).isEmpty();
    }

    private Map<UUID, KnowledgeIndexSyncStatus> statuses() {
        return adminQueries.list(new AdminKnowledgeDocumentQuery(null, null, null, null, null, null, 0, 20))
                .items().stream().collect(Collectors.toMap(item -> item.id(), item -> item.syncStatus()));
    }

    private KnowledgeDocument published(UUID id, String title, KnowledgeScope scope) {
        KnowledgeDocument draft = draft(id, title, scope);
        return draft.publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
    }

    private KnowledgeDocument draft(UUID id, String title, KnowledgeScope scope) {
        return KnowledgeDocument.create(id, new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")), manual(), scope),
                new DocumentAudit(NOW, "author"));
    }

    private DocumentSource manual() {
        return new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated");
    }

    private UUID insertGeneration(String status) {
        UUID jobId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at, created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'SUCCEEDED', 100, ?, ?, 'test', 'test')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_index_generation(id, job_id, status, document_count, created_at, activated_at)
                values (?, ?, ?, 0, ?, ?)
                """, generationId, jobId, status, Timestamp.from(NOW),
                "ACTIVE".equals(status) ? Timestamp.from(NOW) : null);
        return generationId;
    }

    private void insertProjection(UUID generationId, KnowledgeDocument document) {
        var fields = document.fields();
        jdbcTemplate.update("""
                insert into knowledge_index_document(
                    generation_id, document_id, source_revision, format, title, body, directory_path, tags,
                    scope_type, project_id, branch_id, source_type, wiki_url, original_filename,
                    curation_note, source_updated_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, document.id(), document.revision().value(), fields.format().name(),
                fields.title().value(), fields.body().value(), fields.directory().value(), "[\"tag\"]",
                fields.scope().type().name(), fields.scope().projectId(), fields.scope().branchId(),
                fields.source().type().name(), fields.source().wikiUrl(), fields.source().originalFilename(),
                fields.source().curationNote(), Timestamp.from(document.updatedAt()));
    }

    private UUID insertProject(String identifier) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', '', 'ENABLED', ?, ?, 'test', 'test')
                """, id, identifier, identifier, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID insertBranch(UUID projectId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, id, projectId, name, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }
}
