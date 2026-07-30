package io.github.loredock.knowledge.infrastructure.indexing;

import io.github.loredock.knowledge.application.KnowledgeDocumentRepository;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildProgress;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuilder;
import io.github.loredock.knowledge.domain.DocumentAudit;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeDocument;
import io.github.loredock.knowledge.domain.KnowledgeDocumentFields;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeIndexRebuilderIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_rebuild_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeIndexRebuilder rebuilder;

    @Autowired
    private KnowledgeDocumentRepository documents;

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
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
    }

    /**
     * 业务目的：成功重建只快照已发布文档，完整写入后原子激活新代次并退休旧代次，草稿不得进入正式投影。
     */
    @Test
    void successfulRebuildSnapshotsOnlyPublishedAndAtomicallySwitchesActiveGeneration() {
        KnowledgeDocument published = published(id(1), "Published");
        documents.insert(published);
        documents.insert(draft(id(2), "Draft"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        UUID jobId = insertJob("RUNNING");

        KnowledgeIndexRebuildResult result = rebuilder.rebuild(jobId, noOpProgress());

        assertThat(result.documentCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_index_generation where id = ?", String.class, oldGeneration))
                .isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select id from knowledge_index_generation where status = 'ACTIVE'", UUID.class))
                .isEqualTo(result.generationId());
        assertThat(jdbcTemplate.queryForList(
                "select document_id from knowledge_index_document where generation_id = ?", UUID.class,
                result.generationId())).containsExactly(published.id());
    }

    /**
     * 业务目的：投影写入或切换失败必须回滚整个 BUILDING 代次，上一个 ACTIVE 继续服务且未完成投影不可见。
     */
    @Test
    void rebuildFailureRollsBackBuildingDataAndPreservesPreviousActive() {
        documents.insert(published(id(1), "Published"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        UUID jobId = insertJob("RUNNING");
        jdbcTemplate.execute("""
                create function reject_index_projection() returns trigger language plpgsql as $$
                begin raise exception 'simulated projection failure'; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_index_projection before insert on knowledge_index_document
                for each row execute function reject_index_projection()
                """);
        try {
            assertThatThrownBy(() -> rebuilder.rebuild(jobId, noOpProgress())).isInstanceOf(RuntimeException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "select id from knowledge_index_generation where status = 'ACTIVE'", UUID.class))
                    .isEqualTo(oldGeneration);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from knowledge_index_generation", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from knowledge_index_document", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("drop trigger reject_index_projection on knowledge_index_document");
            jdbcTemplate.execute("drop function reject_index_projection()");
        }
    }

    /**
     * 业务目的：重建必须使用 REPEATABLE READ 单快照；分批期间并发编辑不得让同一代次混入后到的新修订。
     */
    @Test
    void rebuildUsesRepeatableReadSnapshotAcrossBatches() throws Exception {
        KnowledgeDocument first = published(id(1), "First");
        KnowledgeDocument second = published(id(2), "Second");
        KnowledgeDocument last = published(id(3), "Before edit");
        documents.insert(first);
        documents.insert(second);
        documents.insert(last);
        UUID jobId = insertJob("RUNNING");
        CountDownLatch firstBatchWritten = new CountDownLatch(1);
        CountDownLatch continueBuild = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                rebuilder.rebuild(jobId, new KnowledgeIndexRebuildProgress() {
                    @Override
                    public void update(int percentage) {
                        if (percentage > 0 && percentage < 100 && firstBatchWritten.getCount() > 0) {
                            firstBatchWritten.countDown();
                            await(continueBuild);
                        }
                    }

                    @Override
                    public void heartbeat() {
                    }
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        worker.start();
        assertThat(firstBatchWritten.await(5, TimeUnit.SECONDS)).isTrue();
        KnowledgeDocument edited = last.edit(new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle("After edit"), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")), manual(), KnowledgeScope.global()),
                new DocumentAudit(NOW.plusSeconds(3), "editor"));
        assertThat(documents.update(edited, last.revision())).isTrue();
        continueBuild.countDown();
        worker.join(5000);

        assertThat(failure.get()).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                select title from knowledge_index_document d
                join knowledge_index_generation g on g.id = d.generation_id
                where g.status = 'ACTIVE' and d.document_id = ?
                """, String.class, last.id())).isEqualTo("Before edit");
    }

    /**
     * 业务目的：旧退休代次的成功后清理即使失败，也不得回滚或遮蔽已经原子激活的新代次。
     */
    @Test
    void retiredGenerationCleanupFailureDoesNotAffectNewActive() {
        documents.insert(published(id(1), "Published"));
        UUID oldest = insertGeneration("RETIRED", insertJob("SUCCEEDED"));
        jdbcTemplate.update("update knowledge_index_generation set activated_at = ? where id = ?",
                Timestamp.from(NOW.minusSeconds(20)), oldest);
        UUID previous = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        jdbcTemplate.update("update knowledge_index_generation set activated_at = ? where id = ?",
                Timestamp.from(NOW.minusSeconds(10)), previous);
        UUID jobId = insertJob("RUNNING");
        jdbcTemplate.execute("""
                create function reject_retired_cleanup() returns trigger language plpgsql as $$
                begin raise exception 'simulated cleanup failure'; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_retired_cleanup before delete on knowledge_index_generation
                for each row execute function reject_retired_cleanup()
                """);
        try {
            KnowledgeIndexRebuildResult result = rebuilder.rebuild(jobId, noOpProgress());

            assertThat(jdbcTemplate.queryForObject(
                    "select id from knowledge_index_generation where status = 'ACTIVE'", UUID.class))
                    .isEqualTo(result.generationId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from knowledge_index_generation", Integer.class)).isEqualTo(3);
        } finally {
            jdbcTemplate.execute("drop trigger reject_retired_cleanup on knowledge_index_generation");
            jdbcTemplate.execute("drop function reject_retired_cleanup()");
        }
    }

    private KnowledgeIndexRebuildProgress noOpProgress() {
        return new KnowledgeIndexRebuildProgress() {
            @Override
            public void update(int percentage) {
            }

            @Override
            public void heartbeat() {
            }
        };
    }

    private KnowledgeDocument published(UUID id, String title) {
        KnowledgeDocument draft = draft(id, title);
        return draft.publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
    }

    private KnowledgeDocument draft(UUID id, String title) {
        return KnowledgeDocument.create(id, new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")), manual(), KnowledgeScope.global()),
                new DocumentAudit(NOW, "author"));
    }

    private DocumentSource manual() {
        return new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated");
    }

    private UUID insertJob(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at, created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', ?, 0, ?, ?, 'test', 'test')
                """, id, status, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID insertGeneration(String status, UUID jobId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into knowledge_index_generation(id, job_id, status, document_count, created_at, activated_at)
                values (?, ?, ?, 0, ?, ?)
                """, id, jobId, status, Timestamp.from(NOW),
                "BUILDING".equals(status) ? null : Timestamp.from(NOW));
        return id;
    }

    private UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
