package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeDocumentImportServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Path STORAGE_ROOT = Path.of("target/knowledge-import-it-objects").toAbsolutePath();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_import_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDocumentImportUseCase imports;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstProjectId;
    private UUID secondProjectId;
    private UUID secondBranchId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.storage.root", STORAGE_ROOT::toString);
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
        jdbcTemplate.update("delete from knowledge_import_item");
        jdbcTemplate.update("delete from knowledge_import_batch");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from stored_object");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        firstProjectId = insertProject("first-project");
        secondProjectId = insertProject("second-project");
        secondBranchId = insertBranch(secondProjectId, "main");
    }

    /**
     * 业务目的：合法单文件每次上传都创建全新批次和全新草稿，而非法 UTF-8 只失败当前项且保留可查询证据。
     */
    @Test
    void singleFileCreatesNewDraftEveryTimeAndEncodingFailureIsRecorded() {
        KnowledgeImportBatchView first = imports.importDocuments(command(
                "guide.md", "# guide".getBytes(StandardCharsets.UTF_8), KnowledgeScope.global()));
        KnowledgeImportBatchView second = imports.importDocuments(command(
                "guide.md", "# guide".getBytes(StandardCharsets.UTF_8), KnowledgeScope.global()));
        KnowledgeImportBatchView invalid = imports.importDocuments(command(
                "broken.txt", new byte[]{(byte) 0xC3, 0x28}, KnowledgeScope.global()));

        assertThat(first.status()).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(ImportItemStatus.SUCCEEDED);
            assertThat(item.reason()).isEqualTo(ImportItemReason.IMPORTED);
            assertThat(item.documentId()).isNotNull();
        });
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.items().getFirst().documentId()).isNotEqualTo(first.items().getFirst().documentId());
        assertThat(invalid.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(invalid.items()).singleElement().satisfies(item -> {
            assertThat(item.reason()).isEqualTo(ImportItemReason.INVALID_TEXT_ENCODING);
            assertThat(item.documentId()).isNull();
        });
        assertThat(jdbcTemplate.queryForList("select status from knowledge_document", String.class))
                .containsOnly(DocumentStatus.DRAFT.name());
        assertThat(imports.getBatch(first.id()).items()).extracting(KnowledgeImportItemView::ordinal)
                .containsExactly(0);
    }

    /**
     * 业务目的：安全 ZIP 中成功、编码失败、非 Markdown 和目录必须稳定按中央目录排序并准确汇总为部分成功。
     */
    @Test
    void zipPartialSuccessHasStableOrderingAndExactCounts() {
        byte[] archive = zip(
                fixture("docs/", new byte[0], true),
                fixture("docs/good.md", "good".getBytes(StandardCharsets.UTF_8), false),
                fixture("docs/bad.markdown", new byte[]{(byte) 0xC3, 0x28}, false),
                fixture("image.png", "image".getBytes(StandardCharsets.UTF_8), false));

        KnowledgeImportBatchView batch = imports.importDocuments(command("batch.zip", archive, KnowledgeScope.global()));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.PARTIAL);
        assertThat(batch.succeededCount()).isEqualTo(1);
        assertThat(batch.failedCount()).isEqualTo(1);
        assertThat(batch.ignoredCount()).isEqualTo(2);
        assertThat(batch.items()).extracting(KnowledgeImportItemView::ordinal).containsExactly(1, 2, 3, 4);
        assertThat(batch.items()).extracting(KnowledgeImportItemView::status)
                .containsExactly(ImportItemStatus.IGNORED, ImportItemStatus.SUCCEEDED,
                        ImportItemStatus.FAILED, ImportItemStatus.IGNORED);
        UUID documentId = batch.items().get(1).documentId();
        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_document where id = ?", String.class, documentId))
                .isEqualTo(DocumentStatus.DRAFT.name());
    }

    /**
     * 业务目的：没有任何 Markdown 候选的 ZIP 仍保存全部忽略证据，但总体必须失败并明确没有可导入文档。
     */
    @Test
    void archiveContainingOnlyIgnoredEntriesIsFailed() {
        KnowledgeImportBatchView batch = imports.importDocuments(command("ignored.zip", zip(
                fixture("assets/", new byte[0], true),
                fixture("assets/logo.png", new byte[]{1, 2, 3}, false)), KnowledgeScope.global()));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.succeededCount()).isZero();
        assertThat(batch.failedCount()).isZero();
        assertThat(batch.ignoredCount()).isEqualTo(2);
        assertThat(batch.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(ImportItemStatus.IGNORED);
            assertThat(item.reason()).isIn(
                    ImportItemReason.UNSUPPORTED_ENTRY_TYPE, ImportItemReason.UNSUPPORTED_FILE_TYPE,
                    ImportItemReason.NO_IMPORTABLE_DOCUMENTS);
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isZero();
    }

    /**
     * 业务目的：分支不属于所选项目时必须作为条目范围失败，禁止静默回退到项目级、main 或通用范围。
     */
    @Test
    void mismatchedProjectAndBranchScopeFailsItemWithoutFallback() {
        KnowledgeScope staleScope = KnowledgeScope.branch(firstProjectId, secondBranchId);

        KnowledgeImportBatchView batch = imports.importDocuments(command(
                "scope.md", "body".getBytes(StandardCharsets.UTF_8), staleScope));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(ImportItemStatus.FAILED);
            assertThat(item.reason()).isEqualTo(ImportItemReason.DOCUMENT_SCOPE_INVALID);
            assertThat(item.documentId()).isNull();
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isZero();
    }

    /**
     * 业务目的：ZIP 结构级拒绝必须补偿原始对象，且在任何文档事务开始前保持批次、条目和文档全空。
     */
    @Test
    void invalidArchiveLeavesNoObjectBatchItemOrDocument() {
        assertThatThrownBy(() -> imports.importDocuments(command(
                "broken.zip", new byte[]{'P', 'K', 3, 4, 0}, KnowledgeScope.global())))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from stored_object", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_import_batch", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_import_item", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isZero();
    }

    /**
     * 业务目的：成功项的批次关联若在标签之后写入失败，该项文档和标签必须一起回滚，先前成功项仍保持提交。
     */
    @Test
    void successfulItemLinkFailureRollsBackOnlyThatDocumentAndTags() {
        jdbcTemplate.execute("""
                create function reject_second_success_item() returns trigger language plpgsql as $$
                begin
                    if NEW.ordinal = 2 and NEW.status = 'SUCCEEDED' then
                        raise exception 'simulated item link failure';
                    end if;
                    return NEW;
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_second_success_item before insert on knowledge_import_item
                for each row execute function reject_second_success_item()
                """);
        try {
            KnowledgeImportBatchView batch = imports.importDocuments(command("atomic.zip", zip(
                    fixture("one.md", "one".getBytes(StandardCharsets.UTF_8), false),
                    fixture("two.md", "two".getBytes(StandardCharsets.UTF_8), false)), KnowledgeScope.global()));

            assertThat(batch.status()).isEqualTo(ImportBatchStatus.PARTIAL);
            assertThat(batch.items()).extracting(KnowledgeImportItemView::status)
                    .containsExactly(ImportItemStatus.SUCCEEDED, ImportItemStatus.FAILED);
            assertThat(batch.items().get(1).reason()).isEqualTo(ImportItemReason.DOCUMENT_PERSISTENCE_FAILED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_document_tag", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from knowledge_import_item where document_id is not null", Integer.class))
                    .isEqualTo(1);
        } finally {
            jdbcTemplate.execute("drop trigger reject_second_success_item on knowledge_import_item");
            jdbcTemplate.execute("drop function reject_second_success_item()");
        }
    }

    private KnowledgeImportCommand command(String filename, byte[] content, KnowledgeScope scope) {
        return new KnowledgeImportCommand(
                new KnowledgeImportUpload(filename, "application/octet-stream", new ByteArrayInputStream(content)),
                new KnowledgeImportOptions(
                        scope,
                        new DocumentDirectory("imports"),
                        DocumentTags.of(List.of("uploaded")),
                        new DocumentSource(DocumentSourceType.MANUAL, null, null, "批量导入")));
    }

    private UUID insertProject(String identifier) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', '', 'ENABLED', ?, ?, 'test', 'test')
                """, id, identifier, identifier, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return id;
    }

    private UUID insertBranch(UUID projectId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_branch(
                    id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, id, projectId, name, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return id;
    }

    private byte[] zip(Fixture... fixtures) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
                for (Fixture fixture : fixtures) {
                    ZipArchiveEntry entry = new ZipArchiveEntry(fixture.name());
                    entry.setUnixMode((fixture.directory() ? UnixStat.DIR_FLAG : UnixStat.FILE_FLAG) | 0644);
                    zip.putArchiveEntry(entry);
                    zip.write(fixture.content());
                    zip.closeArchiveEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("fixture generation failed", exception);
        }
    }

    private Fixture fixture(String name, byte[] content, boolean directory) {
        return new Fixture(name, content, directory);
    }

    private record Fixture(String name, byte[] content, boolean directory) {
    }
}
