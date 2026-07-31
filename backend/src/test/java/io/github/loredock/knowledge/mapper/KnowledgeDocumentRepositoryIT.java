package io.github.loredock.knowledge.mapper;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.request.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.result.PageResult;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.support.TestIds;
import io.github.loredock.knowledge.service.KnowledgeDocumentDataService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeDocumentRepositoryIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_repository_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDocumentDataService repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long firstProjectId;
    private Long firstMainBranchId;
    private Long firstFeatureBranchId;
    private Long secondProjectId;
    private Long secondMainBranchId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_import_batch");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        firstProjectId = insertProject("first-project");
        firstMainBranchId = insertBranch(firstProjectId, "main");
        firstFeatureBranchId = insertBranch(firstProjectId, "feature/a");
        secondProjectId = insertProject("second-project");
        secondMainBranchId = insertBranch(secondProjectId, "main");
    }

    /**
     * 业务目的：文档主体、来源、范围、审计和标签必须作为同一聚合往返，防止实体字段遗漏或标签孤立。
     */
    @Test
    void aggregateRoundTripPersistsAllFieldsAndReplacesTagsAtomically() {
        KnowledgeDocument original = draft(
                "Roundtrip", "body", "guides/start", List.of("Java", "业务"),
                new DocumentSource(DocumentSourceType.WIKI, "https://example.test/wiki", "source.md", "人工整理"),
                KnowledgeScope.branch(firstProjectId, firstFeatureBranchId), 0);
        repository.insert(original);

        KnowledgeDocument loaded = repository.findById(original.id()).orElseThrow();
        assertThat(loaded).isEqualTo(original);

        KnowledgeDocument edited = loaded.edit(fields(
                "Roundtrip edited", "new body", "guides/edited", List.of("Spring", "检索"),
                DocumentSourceType.UPLOAD, KnowledgeScope.project(firstProjectId)), audit(1));
        assertThat(repository.update(edited, loaded.revision())).isTrue();
        assertThat(repository.findById(original.id()).orElseThrow()).isEqualTo(edited);
        assertThat(jdbcTemplate.queryForObject(
                """
                select tags::jsonb @> '[
                    {"displayName":"Spring","normalizedName":"spring"},
                    {"displayName":"检索","normalizedName":"检索"}
                ]'::jsonb
                from knowledge_document where id = ?
                """,
                Boolean.class, original.id())).isTrue();
    }

    /**
     * 业务目的：同值保存不得制造数据库更新，过期 revision 也不得覆盖并发编辑，保护索引同步判断的修订事实。
     */
    @Test
    void sameValueIsNoOpAndStaleRevisionCannotOverwrite() {
        KnowledgeDocument original = draft("Original", "body", "", List.of(), manual(), KnowledgeScope.global(), 0);
        repository.insert(original);
        String initialTuple = tupleVersion(original.id());

        assertThat(repository.update(original, original.revision())).isTrue();
        assertThat(tupleVersion(original.id())).isEqualTo(initialTuple);

        KnowledgeDocument firstEdit = original.edit(fields(
                "First edit", "body", "", List.of(), DocumentSourceType.MANUAL, KnowledgeScope.global()), audit(1));
        KnowledgeDocument staleEdit = original.edit(fields(
                "Stale edit", "body", "", List.of(), DocumentSourceType.MANUAL, KnowledgeScope.global()), audit(2));
        assertThat(repository.update(firstEdit, original.revision())).isTrue();
        assertThat(repository.update(staleEdit, original.revision())).isFalse();
        assertThat(repository.findById(original.id()).orElseThrow().fields().title().value()).isEqualTo("First edit");
    }

    /**
     * 业务目的：管理员筛选必须在数据库中同时应用状态、范围、目录与标签，并返回稳定分页而不是内存隐藏。
     */
    @Test
    void adminQueryFiltersStatusScopeDirectoryAndTagWithStablePaging() {
        repository.insert(published("Newest", "ops/run", List.of("Shared"), KnowledgeScope.project(firstProjectId), 5));
        repository.insert(published("Older", "ops/run", List.of("shared"), KnowledgeScope.project(firstProjectId), 4));
        repository.insert(draft("Draft", "body", "ops/run", List.of("Shared"), manual(),
                KnowledgeScope.project(firstProjectId), 3));
        repository.insert(published("Other scope", "ops/run", List.of("Shared"),
                KnowledgeScope.project(secondProjectId), 2));

        AdminKnowledgeDocumentQuery query = new AdminKnowledgeDocumentQuery(
                KnowledgeScopeType.PROJECT, firstProjectId, null, new DocumentDirectory("ops/run"),
                DocumentStatus.PUBLISHED, "SHARED", 0, 1);
        PageResult<KnowledgeDocument> firstPage = repository.findAdmin(query);
        PageResult<KnowledgeDocument> secondPage = repository.findAdmin(new AdminKnowledgeDocumentQuery(
                query.scopeType(), query.projectId(), query.branchId(), query.directory(), query.status(), query.tag(), 1, 1));

        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.items()).extracting(document -> document.fields().title().value()).containsExactly("Newest");
        assertThat(secondPage.items()).extracting(document -> document.fields().title().value()).containsExactly("Older");
    }

    /**
     * 业务目的：通用入口只能在 SQL 中召回 GLOBAL/PUBLISHED，项目、分支、草稿和归档均不可进入候选集。
     */
    @Test
    void globalBrowseReturnsOnlyPublishedGlobalDocuments() {
        repository.insert(published("Global", "", List.of(), KnowledgeScope.global(), 1));
        repository.insert(draft("Global draft", "body", "", List.of(), manual(), KnowledgeScope.global(), 2));
        repository.insert(published("Project", "", List.of(), KnowledgeScope.project(firstProjectId), 3));
        KnowledgeDocument archived = published("Archived", "", List.of(), KnowledgeScope.global(), 4).archive(audit(5));
        repository.insert(archived);

        PageResult<KnowledgeDocument> page = repository.findPublished(new BrowseKnowledgeDocumentsQuery(
                new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null),
                new DocumentDirectory(""), 0, 20));

        assertThat(page.items()).extracting(document -> document.fields().title().value()).containsExactly("Global");
    }

    /**
     * 业务目的：项目入口只联合通用、当前项目与当前分支知识，其他项目和同项目其他分支必须在加载前被排除。
     */
    @Test
    void projectBrowseUnionsOnlyCurrentProjectAndBranchScopes() {
        repository.insert(published("Global", "", List.of(), KnowledgeScope.global(), 1));
        repository.insert(published("Project", "", List.of(), KnowledgeScope.project(firstProjectId), 2));
        repository.insert(published("Main", "", List.of(), KnowledgeScope.branch(firstProjectId, firstMainBranchId), 3));
        repository.insert(published("Feature", "", List.of(), KnowledgeScope.branch(firstProjectId, firstFeatureBranchId), 4));
        repository.insert(published("Other", "", List.of(), KnowledgeScope.branch(secondProjectId, secondMainBranchId), 5));

        KnowledgeBrowseContext context = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, firstProjectId, firstMainBranchId);
        PageResult<KnowledgeDocument> page = repository.findPublished(new BrowseKnowledgeDocumentsQuery(
                context, new DocumentDirectory(""), 0, 20));

        assertThat(page.items()).extracting(document -> document.fields().title().value())
                .containsExactly("Main", "Project", "Global");
    }

    /**
     * 业务目的：按 ID 的普通读取必须复用查询前置隔离，跨项目、跨分支和非发布状态统一不可见，避免存在性泄露。
     */
    @Test
    void directReadReturnsEmptyOutsideExactPublishedContext() {
        KnowledgeDocument current = published(
                "Current branch", "", List.of(), KnowledgeScope.branch(firstProjectId, firstMainBranchId), 1);
        KnowledgeDocument draft = draft(
                "Draft", "body", "", List.of(), manual(), KnowledgeScope.branch(firstProjectId, firstMainBranchId), 2);
        repository.insert(current);
        repository.insert(draft);

        KnowledgeBrowseContext main = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, firstProjectId, firstMainBranchId);
        KnowledgeBrowseContext feature = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, firstProjectId, firstFeatureBranchId);
        assertThat(repository.findPublishedById(current.id(), main)).isPresent();
        assertThat(repository.findPublishedById(current.id(), feature)).isEmpty();
        assertThat(repository.findPublishedById(draft.id(), main)).isEmpty();
    }

    private KnowledgeDocument draft(
            String title, String body, String directory, List<String> tags,
            DocumentSource source, KnowledgeScope scope, long seconds
    ) {
        Instant time = BASE_TIME.plusSeconds(seconds);
        return KnowledgeDocument.create(TestIds.next(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody(body),
                new DocumentDirectory(directory), DocumentTags.of(tags), source, scope),
                new DocumentAudit(time, "admin"));
    }

    private KnowledgeDocument published(
            String title, String directory, List<String> tags, KnowledgeScope scope, long seconds
    ) {
        KnowledgeDocument draft = draft(title, "body", directory, tags, manual(), scope, seconds);
        return draft.publish(new DocumentAudit(BASE_TIME.plusSeconds(seconds + 100), "publisher"));
    }

    private KnowledgeDocumentFields fields(
            String title, String body, String directory, List<String> tags,
            DocumentSourceType sourceType, KnowledgeScope scope
    ) {
        DocumentSource source = switch (sourceType) {
            case MANUAL -> manual();
            case WIKI -> new DocumentSource(sourceType, "https://example.test/wiki", "source.md", "note");
            case UPLOAD -> new DocumentSource(sourceType, null, "upload.md", "note");
        };
        return new KnowledgeDocumentFields(
                DocumentFormat.PLAIN_TEXT, new DocumentTitle(title), new DocumentBody(body),
                new DocumentDirectory(directory), DocumentTags.of(tags), source, scope);
    }

    private DocumentSource manual() {
        return new DocumentSource(DocumentSourceType.MANUAL, null, null, null);
    }

    private DocumentAudit audit(long seconds) {
        return new DocumentAudit(BASE_TIME.plusSeconds(1_000 + seconds), "editor");
    }

    private Long insertProject(String identifier) {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', '', 'ENABLED', ?, ?, 'SYSTEM', 'SYSTEM')
                """, id, identifier, identifier, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
        return id;
    }

    private Long insertBranch(Long projectId, String name) {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'SYSTEM', 'SYSTEM')
                """, id, projectId, name, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
        return id;
    }

    private String tupleVersion(Long documentId) {
        return jdbcTemplate.queryForObject(
                "select xmin::text from knowledge_document where id = ?", String.class, documentId);
    }
}
