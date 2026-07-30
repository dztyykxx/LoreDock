package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentStateConflictException;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeScope;
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
class KnowledgeDocumentCommandServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_command_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

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
    void resetDatabase() {
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
    }

    /**
     * 业务目的：Markdown 与纯文本创建必须完整保存全部元数据并统一产生 revision 1 草稿和可信审计。
     */
    @Test
    void createPersistsMarkdownAndPlainTextDraftsWithAudit() {
        KnowledgeDocumentView markdown = commands.create(createCommand(DocumentFormat.MARKDOWN, "# body", "Markdown"));
        KnowledgeDocumentView plain = commands.create(createCommand(DocumentFormat.PLAIN_TEXT, "<b>text</b>", "Plain"));

        assertThat(markdown.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(markdown.revision().value()).isEqualTo(1);
        assertThat(markdown.createdAt()).isNotNull();
        assertThat(markdown.createdBy()).isEqualTo("SYSTEM");
        assertThat(plain.format()).isEqualTo(DocumentFormat.PLAIN_TEXT);
        assertThat(plain.body()).isEqualTo("<b>text</b>");
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isEqualTo(2);
    }

    /**
     * 业务目的：全量编辑必须原子替换元数据并增加 revision，同值 PUT 不改变修订和更新时间。
     */
    @Test
    void editIsAtomicAndSameValueRequestIsIdempotent() {
        KnowledgeDocumentView created = commands.create(createCommand(DocumentFormat.MARKDOWN, "body", "Original"));
        EditKnowledgeDocumentCommand edit = new EditKnowledgeDocumentCommand(
                created.id(), DocumentFormat.PLAIN_TEXT, new DocumentTitle("Edited"), new DocumentBody("new body"),
                new DocumentDirectory("guides/edited"), DocumentTags.of(List.of("new")), upload(),
                KnowledgeScope.global());
        KnowledgeDocumentView changed = commands.edit(edit);
        KnowledgeDocumentView same = commands.edit(edit);

        assertThat(changed.revision().value()).isEqualTo(2);
        assertThat(changed.updatedAt()).isAfterOrEqualTo(created.updatedAt());
        assertThat(same.revision()).isEqualTo(changed.revision());
        assertThat(same.updatedAt()).isEqualTo(changed.updatedAt());
        assertThat(repository.findById(created.id()).orElseThrow().fields().tags().values())
                .extracting(tag -> tag.displayName()).containsExactly("new");
    }

    /**
     * 业务目的：已发布文档编辑后仍保持发布资格，但 revision 必须增加以让索引同步状态可派生为 stale。
     */
    @Test
    void editingPublishedDocumentKeepsPublishedStateAndAdvancesRevision() {
        KnowledgeDocumentView created = commands.create(createCommand(DocumentFormat.MARKDOWN, "body", "Published"));
        var aggregate = repository.findById(created.id()).orElseThrow();
        var published = aggregate.publish(new io.github.loredock.knowledge.domain.DocumentAudit(
                aggregate.updatedAt().plusSeconds(10), "publisher"));
        assertThat(repository.update(published, aggregate.revision())).isTrue();

        KnowledgeDocumentView edited = commands.edit(new EditKnowledgeDocumentCommand(
                created.id(), DocumentFormat.MARKDOWN, new DocumentTitle("Published edit"), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("edited")), manual(), KnowledgeScope.global()));

        assertThat(edited.status()).isEqualTo(DocumentStatus.PUBLISHED);
        assertThat(edited.revision().value()).isEqualTo(3);
    }

    /**
     * 业务目的：归档是终态，编辑请求必须以状态冲突失败且不得改变数据库正文、标签或审计。
     */
    @Test
    void archivedDocumentCannotBeEdited() {
        KnowledgeDocumentView created = commands.create(createCommand(DocumentFormat.MARKDOWN, "body", "Archive"));
        var aggregate = repository.findById(created.id()).orElseThrow();
        var archived = aggregate.archive(new io.github.loredock.knowledge.domain.DocumentAudit(
                aggregate.updatedAt().plusSeconds(10), "archiver"));
        assertThat(repository.update(archived, aggregate.revision())).isTrue();

        assertThatThrownBy(() -> commands.edit(new EditKnowledgeDocumentCommand(
                created.id(), DocumentFormat.MARKDOWN, new DocumentTitle("Changed"), new DocumentBody("changed"),
                new DocumentDirectory(""), DocumentTags.of(List.of()), manual(), KnowledgeScope.global())))
                .isInstanceOf(DocumentStateConflictException.class);
        assertThat(repository.findById(created.id()).orElseThrow()).isEqualTo(archived);
    }

    /**
     * 业务目的：标签写入失败必须回滚主体插入，防止创建接口留下缺失标签或来源的半聚合。
     */
    @Test
    void databaseFailureRollsBackDocumentAndTags() {
        jdbcTemplate.execute("""
                create function reject_knowledge_tag() returns trigger language plpgsql as $$
                begin raise exception 'simulated tag failure'; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_knowledge_tag before insert on knowledge_document_tag
                for each row execute function reject_knowledge_tag()
                """);
        try {
            assertThatThrownBy(() -> commands.create(createCommand(DocumentFormat.MARKDOWN, "body", "Rollback")))
                    .isNotInstanceOf(IllegalArgumentException.class);
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document_tag", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("drop trigger reject_knowledge_tag on knowledge_document_tag");
            jdbcTemplate.execute("drop function reject_knowledge_tag()");
        }
    }

    private CreateKnowledgeDocumentCommand createCommand(DocumentFormat format, String body, String title) {
        return new CreateKnowledgeDocumentCommand(
                format, new DocumentTitle(title), new DocumentBody(body), new DocumentDirectory("guides"),
                DocumentTags.of(List.of("business")), manual(), KnowledgeScope.global());
    }

    private DocumentSource manual() {
        return new DocumentSource(DocumentSourceType.MANUAL, null, null, "人工整理");
    }

    private DocumentSource upload() {
        return new DocumentSource(DocumentSourceType.UPLOAD, null, "guide.txt", "上传整理");
    }
}
