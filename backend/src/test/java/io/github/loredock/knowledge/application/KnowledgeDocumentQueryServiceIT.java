package io.github.loredock.knowledge.application;

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
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeDocumentQueryServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_query_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDocumentQueryUseCase queries;

    @Autowired
    private AdminKnowledgeDocumentQueryUseCase adminQueries;

    @Autowired
    private KnowledgeDocumentCommandUseCase commands;

    @Autowired
    private KnowledgeDocumentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void clearDocuments() {
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
    }

    /**
     * 业务目的：普通目录浏览必须返回当前范围内的真实子目录计数与分页摘要，正文不得混入列表结果。
     */
    @Test
    void browseReturnsDirectoryNodesAndPublishedSummaries() {
        publish(create("Root", "", KnowledgeScope.global()));
        publish(create("Guide", "guides", KnowledgeScope.global()));
        publish(create("Nested", "guides/start", KnowledgeScope.global()));
        create("Draft", "guides", KnowledgeScope.global());

        KnowledgeBrowseResult all = queries.browse(new BrowseKnowledgeDocumentsQuery(
                globalContext(), null, 0, 20));
        KnowledgeBrowseResult root = queries.browse(new BrowseKnowledgeDocumentsQuery(
                globalContext(), new DocumentDirectory(""), 0, 20));
        KnowledgeBrowseResult guides = queries.browse(new BrowseKnowledgeDocumentsQuery(
                globalContext(), new DocumentDirectory("guides"), 0, 20));

        assertThat(all.documents().items()).extracting(KnowledgeDocumentSummary::title)
                .containsExactlyInAnyOrder("Root", "Guide", "Nested");
        assertThat(root.directories()).containsExactly(new KnowledgeDirectoryNode("guides", "guides", 2));
        assertThat(root.documents().items()).extracting(KnowledgeDocumentSummary::title).containsExactly("Root");
        assertThat(guides.directories()).containsExactly(
                new KnowledgeDirectoryNode("guides/start", "start", 1));
        assertThat(guides.documents().items()).extracting(KnowledgeDocumentSummary::title).containsExactly("Guide");
    }

    /**
     * 业务目的：普通详情必须携带入口上下文，跨范围 ID、草稿和归档统一返回文档不存在以防枚举。
     */
    @Test
    void ordinaryDetailHidesCrossScopeAndNonPublishedDocuments() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        insertProjectAndBranch(projectId, branchId);
        KnowledgeDocumentView global = publish(create("Global", "", KnowledgeScope.global()));
        KnowledgeDocumentView project = publish(create("Project", "", KnowledgeScope.project(projectId)));
        KnowledgeDocumentView draft = create("Draft", "", KnowledgeScope.global());

        assertThat(queries.get(new ReadKnowledgeDocumentQuery(globalContext(), global.id())).title()).isEqualTo("Global");
        assertThatThrownBy(() -> queries.get(new ReadKnowledgeDocumentQuery(globalContext(), project.id())))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
        assertThatThrownBy(() -> queries.get(new ReadKnowledgeDocumentQuery(globalContext(), draft.id())))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
        assertThatThrownBy(() -> queries.get(new ReadKnowledgeDocumentQuery(
                new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, projectId, branchId), UUID.randomUUID())))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
    }

    /**
     * 业务目的：管理员列表必须保留草稿、已发布和归档筛选，并显示修订号、范围与同步状态供维护判断。
     */
    @Test
    void adminListFiltersLifecycleAndReturnsManagementMetadata() {
        create("Draft", "admin", KnowledgeScope.global());
        KnowledgeDocumentView published = publish(create("Published", "admin", KnowledgeScope.global()));
        archive(published.id());

        PageResult<KnowledgeDocumentSummary> drafts = adminQueries.list(new AdminKnowledgeDocumentQuery(
                KnowledgeScopeType.GLOBAL, null, null, new DocumentDirectory("admin"),
                DocumentStatus.DRAFT, null, 0, 20));
        PageResult<KnowledgeDocumentSummary> archived = adminQueries.list(new AdminKnowledgeDocumentQuery(
                KnowledgeScopeType.GLOBAL, null, null, new DocumentDirectory("admin"),
                DocumentStatus.ARCHIVED, null, 0, 20));

        assertThat(drafts.items()).singleElement().satisfies(summary -> {
            assertThat(summary.title()).isEqualTo("Draft");
            assertThat(summary.revision()).isEqualTo(1);
            assertThat(summary.scope()).isEqualTo(KnowledgeScope.global());
        });
        assertThat(archived.items()).extracting(KnowledgeDocumentSummary::title).containsExactly("Published");
    }

    /**
     * 业务目的：管理详情必须包含替代追溯、生命周期审计和同步信息，且未知 ID 使用稳定不存在语义。
     */
    @Test
    void adminDetailReturnsCompleteLifecycleAndReplacementInformation() {
        KnowledgeDocumentView created = publish(create("Detail", "", KnowledgeScope.global()));
        KnowledgeDocumentView detail = adminQueries.get(created.id());

        assertThat(detail.publishedAt()).isNotNull();
        assertThat(detail.publishedBy()).isEqualTo("publisher");
        assertThat(detail.replacement().replacesDocumentId()).isNull();
        assertThat(detail.syncStatus()).isNotNull();
        assertThatThrownBy(() -> adminQueries.get(UUID.randomUUID()))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
    }

    private KnowledgeDocumentView create(String title, String directory, KnowledgeScope scope) {
        return commands.create(new CreateKnowledgeDocumentCommand(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(directory), DocumentTags.of(List.of("tag")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"), scope));
    }

    private KnowledgeDocumentView publish(KnowledgeDocumentView view) {
        KnowledgeDocument current = repository.findById(view.id()).orElseThrow();
        KnowledgeDocument published = current.publish(new DocumentAudit(
                current.updatedAt().plusSeconds(1), "publisher"));
        assertThat(repository.update(published, current.revision())).isTrue();
        return adminView(published);
    }

    private void archive(UUID documentId) {
        KnowledgeDocument current = repository.findById(documentId).orElseThrow();
        assertThat(repository.update(current.archive(new DocumentAudit(
                current.updatedAt().plusSeconds(1), "archiver")), current.revision())).isTrue();
    }

    private KnowledgeDocumentView adminView(KnowledgeDocument document) {
        return new KnowledgeDocumentView(
                document.id(), document.fields().format(), document.fields().title().value(),
                document.fields().body().value(), document.fields().directory().value(),
                document.fields().tags().values(), document.fields().source(), document.fields().scope(),
                document.status(), document.revision(), document.publishedAt(), document.publishedBy(),
                document.archivedAt(), document.archivedBy(), document.replacement(),
                io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus.PENDING,
                document.createdAt(), document.updatedAt(), document.createdBy(), document.updatedBy());
    }

    private KnowledgeBrowseContext globalContext() {
        return new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null);
    }

    private void insertProjectAndBranch(UUID projectId, UUID branchId) {
        jdbcTemplate.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, 'Query project', '', '', 'ENABLED', now(), now(), 'SYSTEM', 'SYSTEM')
                """, projectId, "query-" + projectId.toString().substring(0, 8));
        jdbcTemplate.update("""
                insert into project_branch(
                    id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', now(), now(), 'SYSTEM', 'SYSTEM')
                """, branchId, projectId);
    }
}
