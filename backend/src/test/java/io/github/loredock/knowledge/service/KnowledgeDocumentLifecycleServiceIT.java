package io.github.loredock.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.ArchiveKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.request.ReadKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class KnowledgeDocumentLifecycleServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_lifecycle_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDocumentLifecycleService lifecycle;

    @Autowired
    private KnowledgeDocumentCommandService commands;

    @Autowired
    private KnowledgeDocumentQueryService queries;

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
        // 标签已字段化进 knowledge_document，不再有独立标签表；只清理文档主表即可。
        jdbcTemplate.update("delete from knowledge_document");
    }

    /**
     * 业务目的：发布与归档重复请求必须返回同一事实，不重复修改 revision 或首次生命周期审计。
     */
    @Test
    void publishAndArchiveAreTargetStateIdempotent() {
        KnowledgeDocumentView draft = create("Lifecycle", KnowledgeScope.global());
        KnowledgeDocumentView published = lifecycle.publish(new PublishKnowledgeDocumentCommand(draft.id(), null));
        KnowledgeDocumentView publishedAgain = lifecycle.publish(new PublishKnowledgeDocumentCommand(draft.id(), null));
        KnowledgeDocumentView archived = lifecycle.archive(new ArchiveKnowledgeDocumentCommand(draft.id()));
        KnowledgeDocumentView archivedAgain = lifecycle.archive(new ArchiveKnowledgeDocumentCommand(draft.id()));

        assertThat(publishedAgain.revision()).isEqualTo(published.revision());
        assertThat(publishedAgain.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(archivedAgain.revision()).isEqualTo(archived.revision());
        assertThat(archivedAgain.archivedAt()).isEqualTo(archived.archivedAt());
    }

    /**
     * 业务目的：替代发布必须在一个事务中发布新文档、归档旧文档并建立双向可读追溯关系。
     */
    @Test
    void replacementPublicationAtomicallyUpdatesBothDocuments() {
        KnowledgeDocumentView old = lifecycle.publish(new PublishKnowledgeDocumentCommand(
                create("Old", KnowledgeScope.global()).id(), null));
        KnowledgeDocumentView candidate = create("New", KnowledgeScope.global());

        KnowledgeDocumentView published = lifecycle.publish(new PublishKnowledgeDocumentCommand(candidate.id(), old.id()));
        KnowledgeDocumentView archived = repository.findById(old.id()).map(document ->
                new io.github.loredock.knowledge.converter.KnowledgeDocumentViewFactory().create(document)).orElseThrow();

        assertThat(published.status()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(published.replacement().replacesDocumentId()).isEqualTo(old.id());
        assertThat(archived.status()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(archived.replacement().replacedByDocumentId()).isEqualTo(candidate.id());
    }

    /**
     * 业务目的：不同范围、自替代和循环链必须在写入前失败，候选与旧文档状态均保持不变。
     */
    @Test
    void invalidReplacementScopeSelfAndCycleLeaveStateUnchanged() {
        KnowledgeDocumentView old = lifecycle.publish(new PublishKnowledgeDocumentCommand(
                create("Old", KnowledgeScope.global()).id(), null));
        Long projectId = insertProject();
        KnowledgeDocumentView otherScope = create("Other scope", KnowledgeScope.project(projectId));

        assertThatThrownBy(() -> lifecycle.publish(new PublishKnowledgeDocumentCommand(otherScope.id(), old.id())))
                .isInstanceOf(DocumentReplacementConflictException.class);
        assertThatThrownBy(() -> lifecycle.publish(new PublishKnowledgeDocumentCommand(otherScope.id(), otherScope.id())))
                .isInstanceOf(DocumentReplacementConflictException.class);

        KnowledgeDocumentView cycleCandidate = create("Cycle", KnowledgeScope.global());
        jdbcTemplate.update("update knowledge_document set replaces_document_id = ? where id = ?",
                cycleCandidate.id(), old.id());
        assertThatThrownBy(() -> lifecycle.publish(new PublishKnowledgeDocumentCommand(cycleCandidate.id(), old.id())))
                .isInstanceOf(DocumentReplacementConflictException.class);
        assertThat(repository.findById(cycleCandidate.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
    }

    /**
     * 业务目的：两个候选并发竞争同一旧文档最多一个成功，命名唯一约束必须稳定映射为替代冲突。
     */
    @Test
    void concurrentReplacementCompetitionAllowsOnlyOneWinner() throws Exception {
        KnowledgeDocumentView old = lifecycle.publish(new PublishKnowledgeDocumentCommand(
                create("Old", KnowledgeScope.global()).id(), null));
        KnowledgeDocumentView first = create("First", KnowledgeScope.global());
        KnowledgeDocumentView second = create("Second", KnowledgeScope.global());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> publishAfter(start, first.id(), old.id()));
            var secondResult = executor.submit(() -> publishAfter(start, second.id(), old.id()));
            start.countDown();
            List<Object> results = List.of(firstResult.get(), secondResult.get());
            assertThat(results.stream().filter(KnowledgeDocumentView.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(DocumentReplacementConflictException.class::isInstance)).hasSize(1);
        }
    }

    /**
     * 业务目的：旧文档归档写入失败必须回滚已执行的新文档发布，且归档提交后普通读取应立即不可见。
     */
    @Test
    void replacementFailureRollsBackAndSuccessfulArchiveImmediatelyRemovesVisibility() {
        KnowledgeDocumentView old = lifecycle.publish(new PublishKnowledgeDocumentCommand(
                create("Old", KnowledgeScope.global()).id(), null));
        KnowledgeDocumentView candidate = create("Candidate", KnowledgeScope.global());
        jdbcTemplate.execute("""
                create function reject_old_archive() returns trigger language plpgsql as $$
                begin if old.id = new.id and new.status = 'ARCHIVED' then raise exception 'archive failed'; end if;
                return new; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_old_archive before update on knowledge_document
                for each row execute function reject_old_archive()
                """);
        try {
            assertThatThrownBy(() -> lifecycle.publish(new PublishKnowledgeDocumentCommand(candidate.id(), old.id())))
                    .isNotInstanceOf(DocumentReplacementConflictException.class);
            assertThat(repository.findById(candidate.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
            assertThat(repository.findById(old.id()).orElseThrow().status()).isEqualTo(DocumentStatus.PUBLISHED);
        } finally {
            jdbcTemplate.execute("drop trigger reject_old_archive on knowledge_document");
            jdbcTemplate.execute("drop function reject_old_archive()");
        }

        lifecycle.archive(new ArchiveKnowledgeDocumentCommand(old.id()));
        assertThatThrownBy(() -> queries.get(new ReadKnowledgeDocumentQuery(
                new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null), old.id())))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
    }

    private KnowledgeDocumentView create(String title, KnowledgeScope scope) {
        return commands.create(new CreateKnowledgeDocumentCommand(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of()),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, null), scope));
    }

    private Long insertProject() {
        Long projectId = 8000000000000000012L;
        jdbcTemplate.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, 'Lifecycle', '', '', 'ENABLED', now(), now(), 'SYSTEM', 'SYSTEM')
                """, projectId, "lifecycle-" + projectId.toString().substring(0, 8));
        return projectId;
    }

    private Object publishAfter(CountDownLatch start, Long candidateId, Long oldId) {
        try {
            start.await();
            return lifecycle.publish(new PublishKnowledgeDocumentCommand(candidateId, oldId));
        } catch (DocumentReplacementConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
