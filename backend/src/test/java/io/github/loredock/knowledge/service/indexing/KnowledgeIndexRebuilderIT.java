package io.github.loredock.knowledge.service.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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
import io.github.loredock.knowledge.model.request.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.service.KnowledgeDocumentDataService;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import io.github.loredock.knowledge.service.search.KnowledgeEmbeddingService;
import io.github.loredock.support.TestIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeIndexRebuilderIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_knowledge_rebuild_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private KnowledgeIndexRebuildService rebuilder;
    @Autowired private KnowledgeDocumentDataService documents;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private KnowledgeEmbeddingService embedding;

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
        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        reset(embedding);
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "c".repeat(64), 512));
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));
    }

    /**
     * 业务目的：成功重建只持久一套 generation 和分块，草稿不进入索引，旧文档投影表必须不存在。
     */
    @Test
    void successfulRebuildUsesOneGenerationAndNoDocumentProjection() {
        KnowledgeDocument published = published("Published");
        documents.insert(published);
        documents.insert(draft("Draft"));

        var result = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress());

        assertThat(result.documentCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where id=? and status='ACTIVE' and chunk_count>0",
                Long.class, result.generationId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(distinct document_id) from knowledge_search_chunk where generation_id=?",
                Long.class, result.generationId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select to_regclass('knowledge_index_document') is null", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select to_regclass('knowledge_search_generation') is null", Boolean.class)).isTrue();
        System.out.printf("测试证据：场景=单generation重建，generation=%d，文档=1，投影表=0%n",
                result.generationId());
    }

    /**
     * 业务目的：新 generation 在向量写入阶段失败时必须被清理，旧 ACTIVE 不变且仍可查询。
     */
    @Test
    void failedRebuildKeepsPreviousActiveGeneration() {
        documents.insert(published("Published"));
        Long previous = insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        when(embedding.embedDocuments(anyList())).thenThrow(new IllegalStateException("simulated embedding failure"));

        assertThatThrownBy(() -> rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select id from knowledge_index_generation where status='ACTIVE'", Long.class)).isEqualTo(previous);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status='BUILDING'", Long.class)).isZero();
        System.out.printf("测试证据：场景=失败保留旧generation，active=%d，遗留building=0%n", previous);
    }

    /**
     * 业务目的：两个重建并发切换时数据库始终只允许一个 ACTIVE，已完成 generation 不会被部分覆盖。
     */
    @Test
    void concurrentRebuildsLeaveExactlyOneActiveGeneration() throws Exception {
        documents.insert(published("Published"));
        insertGeneration("ACTIVE", insertJob("SUCCEEDED"));
        Long firstJob = insertJob("RUNNING");
        Long secondJob = insertJob("RUNNING");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rebuilder.rebuild(firstJob, noOpProgress()));
            var second = executor.submit(() -> rebuilder.rebuild(secondJob, noOpProgress()));
            first.get();
            second.get();
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status='ACTIVE'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status='RETIRED'", Long.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status='BUILDING'", Long.class)).isZero();
        System.out.println("测试证据：场景=并发generation切换，ACTIVE=1，BUILDING=0");
    }

    private KnowledgeIndexRebuildService.Progress noOpProgress() {
        return new KnowledgeIndexRebuildService.Progress(value -> { }, () -> { });
    }

    private KnowledgeDocument published(String title) {
        KnowledgeDocument draft = draft(title);
        return draft.publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
    }

    private KnowledgeDocument draft(String title) {
        return KnowledgeDocument.create(TestIds.next(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody("body"),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"), KnowledgeScope.global()),
                new DocumentAudit(NOW, "author"));
    }

    private Long insertJob(String status) {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at, created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', ?, 0, ?, ?, 'test', 'test')
                """, id, status, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private Long insertGeneration(String status, Long jobId) {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into knowledge_index_generation(
                    id, job_id, status, model_id, model_checksum, vector_dimension,
                    chunk_strategy_version, fusion_config_version, document_count, chunk_count,
                    created_at, activated_at)
                values (?, ?, ?, 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 0, 0, ?, ?)
                """, id, jobId, status, "c".repeat(64), Timestamp.from(NOW),
                "BUILDING".equals(status) ? null : Timestamp.from(NOW));
        return id;
    }

    private List<KnowledgeEmbeddingVector> vectorsFor(List<KnowledgeEmbeddingInput> inputs) {
        return inputs.stream().map(input -> new KnowledgeEmbeddingVector(vector())).toList();
    }

    private float[] vector() {
        float[] vector = new float[512];
        java.util.Arrays.fill(vector, 0.01F);
        return vector;
    }
}
