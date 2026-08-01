package io.github.loredock.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.request.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.request.AdminBrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.ReadKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.result.KnowledgeBrowseResult;
import io.github.loredock.knowledge.model.result.KnowledgeDirectoryNode;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentSummary;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.model.result.PageResult;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
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
    private KnowledgeDocumentQueryService queries;

    @Autowired
    private KnowledgeDocumentQueryService adminQueries;

    @Autowired
    private KnowledgeDocumentCommandService commands;

    @Autowired
    private KnowledgeDocumentDataService repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void clearDocuments() {
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
     * 业务目的：父目录必须递归包含全部后代文档，完整目录计数不能被当前分页或 SQL 通配符字符破坏。
     */
    @Test
    void subtreeBrowseKeepsCompleteDirectoryCountsAndTreatsWildcardsLiterally() {
        create("Parent direct", "测试资料", KnowledgeScope.global());
        create("Runtime one", "测试资料/Atlas/source/runtime", KnowledgeScope.global());
        create("Runtime two", "测试资料/Atlas/source/runtime", KnowledgeScope.global());
        create("Literal wildcard", "测试%_资料/精确", KnowledgeScope.global());
        create("Would match wildcard", "测试甲资料/错误", KnowledgeScope.global());

        KnowledgeBrowseResult parent = adminQueries.browseAdmin(new AdminBrowseKnowledgeDocumentsQuery(
                globalContext(), new DocumentDirectory("测试资料"), 0, 2));
        KnowledgeBrowseResult literalWildcard = adminQueries.browseAdmin(new AdminBrowseKnowledgeDocumentsQuery(
                globalContext(), new DocumentDirectory("测试%_资料"), 0, 20));

        assertThat(parent.documents().totalElements()).isEqualTo(3);
        assertThat(parent.documents().items()).hasSize(2);
        assertThat(parent.directories()).contains(
                new KnowledgeDirectoryNode("测试资料", "测试资料", 3),
                new KnowledgeDirectoryNode("测试资料/Atlas", "Atlas", 2),
                new KnowledgeDirectoryNode("测试资料/Atlas/source/runtime", "runtime", 2),
                new KnowledgeDirectoryNode("测试%_资料", "测试%_资料", 1));
        assertThat(literalWildcard.documents().items()).extracting(KnowledgeDocumentSummary::title)
                .containsExactly("Literal wildcard");
        System.out.printf("测试证据：场景=管理员父目录子树浏览，目录=测试资料，命中=%d，目录节点=%d，通配符字面命中=%d%n",
                parent.documents().totalElements(), parent.directories().size(),
                literalWildcard.documents().totalElements());
    }

    /**
     * 业务目的：普通详情必须携带入口上下文，跨范围 ID、草稿和归档统一返回文档不存在以防枚举。
     */
    @Test
    void ordinaryDetailHidesCrossScopeAndNonPublishedDocuments() {
        Long projectId = 8000000000000000019L;
        Long branchId = 8000000000000000020L;
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
                new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, projectId, branchId), 8000000000000000021L)))
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
        assertThatThrownBy(() -> adminQueries.get(8000000000000000022L))
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

    private void archive(Long documentId) {
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
                io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus.PENDING,
                document.createdAt(), document.updatedAt(), document.createdBy(), document.updatedBy());
    }

    private KnowledgeBrowseContext globalContext() {
        return new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null);
    }

    private void insertProjectAndBranch(Long projectId, Long branchId) {
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
