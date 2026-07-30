package io.github.loredock.knowledge.infrastructure.indexing;

import io.github.loredock.knowledge.application.KnowledgeDocumentRepository;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildProgress;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuilder;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingPort;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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

    @Autowired
    private KnowledgeIndexGenerationRecovery generationRecovery;

    @MockitoBean
    private KnowledgeEmbeddingPort embedding;

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
        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_search_generation");
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        reset(embedding);
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "c".repeat(64), 512));
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));
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
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_generation where generation_id = ?",
                Long.class, result.generationId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_chunk where generation_id = ?",
                Long.class, result.generationId())).isEqualTo(1);
        System.out.printf("测试证据：场景=成功分阶段重建，新generation=%s，投影文档数=%d，检索分块数=%d%n",
                result.generationId(), result.documentCount(), 1);
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
     * 业务目的：短 REPEATABLE READ 事务必须先冻结不可变投影，再在无事实表事务的 CPU 阶段执行 Embedding；
     * 并发编辑不得混入同一 generation，BUILDING 在完整激活前也不得替换旧 ACTIVE。
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
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch continueBuild = new CountDownLatch(1);
        AtomicBoolean transactionActiveDuringEmbedding = new AtomicBoolean(true);
        AtomicReference<List<KnowledgeEmbeddingInput>> embeddedInputs = new AtomicReference<>();
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<KnowledgeEmbeddingInput> inputs = invocation.getArgument(0);
            embeddedInputs.set(List.copyOf(inputs));
            transactionActiveDuringEmbedding.set(TransactionSynchronizationManager.isActualTransactionActive());
            embeddingStarted.countDown();
            await(continueBuild);
            return vectorsFor(inputs);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                rebuilder.rebuild(jobId, noOpProgress());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        worker.start();
        assertThat(embeddingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select id from knowledge_index_generation where status = 'ACTIVE'", UUID.class))
                .isEqualTo(oldGeneration);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status = 'BUILDING'", Long.class))
                .isEqualTo(1);
        KnowledgeDocument edited = last.edit(new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle("After edit"), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")), manual(), KnowledgeScope.global()),
                new DocumentAudit(NOW.plusSeconds(3), "editor"));
        assertThat(documents.update(edited, last.revision())).isTrue();
        continueBuild.countDown();
        worker.join(5000);

        assertThat(failure.get()).isNull();
        assertThat(transactionActiveDuringEmbedding).isFalse();
        assertThat(embeddedInputs.get()).extracting(KnowledgeEmbeddingInput::title)
                .contains("Before edit").doesNotContain("After edit");
        assertThat(jdbcTemplate.queryForObject("""
                select title from knowledge_index_document d
                join knowledge_index_generation g on g.id = d.generation_id
                where g.status = 'ACTIVE' and d.document_id = ?
                """, String.class, last.id())).isEqualTo("Before edit");
        System.out.printf("测试证据：场景=短快照并发编辑，旧ACTIVE=%s，Embedding事务活动=%s，冻结标题=%s%n",
                oldGeneration, transactionActiveDuringEmbedding.get(), "Before edit");
    }

    /**
     * 业务目的：项目、分支、来源与规范化标签必须复制到每个检索分块，防止候选 SQL 只能在召回后补做范围过滤。
     */
    @Test
    void branchScopeSourceAndNormalizedTagsAreMaterializedIntoEveryChunk() {
        UUID projectId = insertProject("search-project");
        UUID branchId = insertBranch(projectId, "main");
        KnowledgeDocument branchDocument = KnowledgeDocument.create(
                id(40),
                new KnowledgeDocumentFields(
                        DocumentFormat.MARKDOWN,
                        new DocumentTitle("分支恢复手册"),
                        new DocumentBody("恢复步骤。".repeat(100)),
                        new DocumentDirectory("runbook"),
                        DocumentTags.of(List.of("恢复", "API")),
                        manual(),
                        KnowledgeScope.branch(projectId, branchId)
                ),
                new DocumentAudit(NOW, "author")
        ).publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
        documents.insert(branchDocument);

        KnowledgeIndexRebuildResult result = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress());

        List<java.util.Map<String, Object>> chunks = jdbcTemplate.queryForList("""
                select scope_type, project_id, branch_id, source_type, normalized_tags
                from knowledge_search_chunk where generation_id = ? order by chunk_no
                """, result.generationId());
        assertThat(chunks).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> {
                    assertThat(chunk.get("scope_type")).isEqualTo("BRANCH");
                    assertThat(chunk.get("project_id")).isEqualTo(projectId);
                    assertThat(chunk.get("branch_id")).isEqualTo(branchId);
                    assertThat(chunk.get("source_type")).isEqualTo("MANUAL");
                });
        String[] tags = jdbcTemplate.queryForObject("""
                select normalized_tags from knowledge_search_chunk
                where generation_id = ? order by chunk_no limit 1
                """, (resultSet, rowNum) -> (String[]) resultSet.getArray(1).getArray(), result.generationId());
        assertThat(tags).containsExactly("api", "恢复");
        System.out.printf("测试证据：场景=分支检索元数据，generation=%s，项目=%s，分支=%s，分块数=%d，标签数=%d%n",
                result.generationId(), projectId, branchId, chunks.size(), tags.length);
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

    /**
     * 业务目的：模型缺失或 checksum 不符合固定契约时必须在任何分块落库前失败，旧 ACTIVE 继续可见。
     */
    @Test
    void missingModelAndChecksumMismatchCleanBuildingAndPreservePreviousActive() {
        documents.insert(published(id(50), "Published"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "invalid-checksum", 512));

        assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(RuntimeException.class);
        assertFailedRebuildPreserved(oldGeneration);

        when(embedding.describeModel()).thenThrow(new KnowledgeEmbeddingUnavailableException());
        assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(RuntimeException.class);
        assertFailedRebuildPreserved(oldGeneration);
        System.out.printf("测试证据：场景=模型缺失与checksum不匹配，旧ACTIVE=%s，BUILDING数=0，分块数=0%n",
                oldGeneration);
    }

    /**
     * 业务目的：Embedding 在后续批次失败时必须清除前一批已提交分块，防止部分 generation 被误激活。
     */
    @Test
    void embeddingFailureAfterCommittedBatchRemovesPartialGeneration() {
        KnowledgeDocument longDocument = KnowledgeDocument.create(
                id(51),
                new KnowledgeDocumentFields(
                        DocumentFormat.MARKDOWN, new DocumentTitle("长文档"),
                        new DocumentBody("知识恢复流程。".repeat(900)), new DocumentDirectory(""),
                        DocumentTags.of(List.of("恢复")), manual(), KnowledgeScope.global()),
                new DocumentAudit(NOW, "author")
        ).publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
        documents.insert(longDocument);
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        AtomicInteger calls = new AtomicInteger();
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return vectorsFor(invocation.getArgument(0));
            }
            throw new IllegalStateException("simulated embedding batch failure");
        });

        assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls).hasValueGreaterThan(1);
        assertFailedRebuildPreserved(oldGeneration);
        System.out.printf("测试证据：场景=Embedding中途失败，已调用批次数=%d，旧ACTIVE=%s，残留分块数=0%n",
                calls.get(), oldGeneration);
    }

    /**
     * 业务目的：分块仓储写入失败或 Embedding 维度错误都必须在激活前清理 BUILDING，不能改变旧 ACTIVE。
     */
    @Test
    void chunkWriteAndVectorDimensionFailuresNeverActivatePartialGeneration() {
        documents.insert(published(id(52), "Published"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        jdbcTemplate.execute("""
                create function reject_search_chunk() returns trigger language plpgsql as $$
                begin raise exception 'simulated search chunk failure'; end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_search_chunk before insert on knowledge_search_chunk
                for each row execute function reject_search_chunk()
                """);
        try {
            assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                    .isInstanceOf(RuntimeException.class);
            assertFailedRebuildPreserved(oldGeneration);
        } finally {
            jdbcTemplate.execute("drop trigger reject_search_chunk on knowledge_search_chunk");
            jdbcTemplate.execute("drop function reject_search_chunk()");
        }

        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<KnowledgeEmbeddingInput> inputs = invocation.getArgument(0);
            return inputs.stream()
                    .map(input -> new KnowledgeEmbeddingVector(new float[511]))
                    .toList();
        });
        assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(IllegalArgumentException.class);
        assertFailedRebuildPreserved(oldGeneration);
        System.out.printf("测试证据：场景=分块写入与维度失败，旧ACTIVE=%s，BUILDING数=0，搜索元数据数=0%n",
                oldGeneration);
    }

    /**
     * 业务目的：即使所有写入 SQL 成功，分块序号或计数校验不完整也必须拒绝激活并删除异常 generation。
     */
    @Test
    void validationMismatchDeletesGenerationBeforeActivation() {
        documents.insert(published(id(53), "Published"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        jdbcTemplate.execute("""
                create function shift_search_chunk_number() returns trigger language plpgsql as $$
                begin new.chunk_no := new.chunk_no + 1; return new; end $$
                """);
        jdbcTemplate.execute("""
                create trigger shift_search_chunk_number before insert on knowledge_search_chunk
                for each row execute function shift_search_chunk_number()
                """);
        try {
            assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                    .isInstanceOf(RuntimeException.class);
            assertFailedRebuildPreserved(oldGeneration);
        } finally {
            jdbcTemplate.execute("drop trigger shift_search_chunk_number on knowledge_search_chunk");
            jdbcTemplate.execute("drop function shift_search_chunk_number()");
        }
        System.out.printf("测试证据：场景=完整性校验失败，旧ACTIVE=%s，异常generation已级联清理%n",
                oldGeneration);
    }

    /**
     * 业务目的：激活事务在退休旧代次之后失败时必须整体回滚，旧 ACTIVE 不能短暂或永久丢失。
     */
    @Test
    void activationFailureRollsBackRetirementAndCleansNewGeneration() {
        documents.insert(published(id(54), "Published"));
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        jdbcTemplate.execute("""
                create function reject_search_activation() returns trigger language plpgsql as $$
                begin
                    if old.status = 'BUILDING' and new.status = 'ACTIVE' then
                        raise exception 'simulated activation failure';
                    end if;
                    return new;
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_search_activation before update on knowledge_index_generation
                for each row execute function reject_search_activation()
                """);
        try {
            assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                    .isInstanceOf(RuntimeException.class);
            assertFailedRebuildPreserved(oldGeneration);
        } finally {
            jdbcTemplate.execute("drop trigger reject_search_activation on knowledge_index_generation");
            jdbcTemplate.execute("drop function reject_search_activation()");
        }
        System.out.printf("测试证据：场景=激活事务失败，旧ACTIVE=%s，状态保持ACTIVE，新BUILDING已清理%n",
                oldGeneration);
    }

    /**
     * 业务目的：进程中断使任务进入 FAILED 后，启动恢复必须级联删除该任务遗留的 BUILDING 投影与搜索元数据，
     * 同时不得触碰上一个 ACTIVE。
     */
    @Test
    void processInterruptedRecoveryRemovesAbandonedBuildingAndPreservesActive() {
        KnowledgeDocument published = published(id(55), "Interrupted build");
        documents.insert(published);
        UUID oldGeneration = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        UUID failedJob = insertJob("FAILED");
        jdbcTemplate.update("""
                update background_job set error_code='PROCESS_INTERRUPTED', error_message='进程中断', finished_at=?
                where id=?
                """, Timestamp.from(NOW), failedJob);
        UUID abandoned = insertGeneration("BUILDING", failedJob);
        insertProjection(abandoned, published);
        jdbcTemplate.update("""
                insert into knowledge_search_generation(generation_id, model_id, model_checksum,
                    vector_dimension, chunk_strategy_version, fusion_config_version, document_count,
                    chunk_count, created_at)
                values (?, 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 1, 1, ?)
                """, abandoned, "d".repeat(64), Timestamp.from(NOW));

        int recovered = generationRecovery.recoverAbandonedBuildingGenerations();

        assertThat(recovered).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select id from knowledge_index_generation where status='ACTIVE'", UUID.class))
                .isEqualTo(oldGeneration);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where id=?", Long.class, abandoned)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_document where generation_id=?", Long.class, abandoned))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_generation where generation_id=?", Long.class, abandoned))
                .isZero();
        System.out.printf("测试证据：场景=进程中断恢复，清理BUILDING数=%d，旧ACTIVE=%s，遗留投影数=0%n",
                recovered, oldGeneration);
    }

    /**
     * 业务目的：成功切换后只能保留当前 ACTIVE 与上一个 RETIRED，防止历史向量长期无界占用数据库；
     * 删除更旧 generation 时必须级联其搜索元数据。
     */
    @Test
    void successfulCleanupKeepsCurrentAndPreviousRetiredOnly() {
        KnowledgeDocument published = published(id(56), "Published");
        documents.insert(published);
        UUID oldest = insertGeneration("RETIRED", insertJob("SUCCEEDED"));
        jdbcTemplate.update("update knowledge_index_generation set activated_at=? where id=?",
                Timestamp.from(NOW.minusSeconds(20)), oldest);
        insertProjection(oldest, published);
        jdbcTemplate.update("""
                insert into knowledge_search_generation(generation_id, model_id, model_checksum,
                    vector_dimension, chunk_strategy_version, fusion_config_version, document_count,
                    chunk_count, created_at)
                values (?, 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 1, 1, ?)
                """, oldest, "e".repeat(64), Timestamp.from(NOW.minusSeconds(20)));
        UUID previous = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        jdbcTemplate.update("update knowledge_index_generation set activated_at=? where id=?",
                Timestamp.from(NOW.minusSeconds(10)), previous);

        KnowledgeIndexRebuildResult result = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress());

        assertThat(jdbcTemplate.queryForList(
                "select id from knowledge_index_generation order by status, id", UUID.class))
                .containsExactlyInAnyOrder(result.generationId(), previous);
        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_index_generation where id=?", String.class, previous))
                .isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_generation where generation_id=?", Long.class, oldest))
                .isZero();
        System.out.printf("测试证据：场景=成功代次清理，当前ACTIVE=%s，保留RETIRED=%s，已删除旧代次=%s%n",
                result.generationId(), previous, oldest);
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

    private void insertProjection(UUID generationId, KnowledgeDocument document) {
        jdbcTemplate.update("""
                insert into knowledge_index_document(generation_id, document_id, source_revision, format,
                    title, body, directory_path, tags, scope_type, source_type, source_updated_at)
                values (?, ?, ?, 'MARKDOWN', ?, ?, '', '["恢复"]'::jsonb, 'GLOBAL', 'MANUAL', ?)
                """, generationId, document.id(), document.revision().value(),
                document.fields().title().value(), document.fields().body().value(),
                Timestamp.from(document.updatedAt()));
    }

    private UUID insertProject(String identifier) {
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', '', 'ENABLED', ?, ?, 'test', 'test')
                """, projectId, identifier, identifier, Timestamp.from(NOW), Timestamp.from(NOW));
        return projectId;
    }

    private UUID insertBranch(UUID projectId, String name) {
        UUID branchId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, branchId, projectId, name, Timestamp.from(NOW), Timestamp.from(NOW));
        return branchId;
    }

    private List<KnowledgeEmbeddingVector> vectorsFor(List<KnowledgeEmbeddingInput> inputs) {
        return inputs.stream().map(input -> new KnowledgeEmbeddingVector(vector())).toList();
    }

    private float[] vector() {
        float[] vector = new float[512];
        java.util.Arrays.fill(vector, 0.01F);
        return vector;
    }

    private void assertFailedRebuildPreserved(UUID oldGeneration) {
        assertThat(jdbcTemplate.queryForObject(
                "select id from knowledge_index_generation where status = 'ACTIVE'", UUID.class))
                .isEqualTo(oldGeneration);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status = 'BUILDING'", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_search_generation", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_search_chunk", Long.class))
                .isZero();
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
