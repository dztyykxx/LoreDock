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
import java.util.ArrayList;
import java.util.List;
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
class KnowledgeIndexRefreshIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_knowledge_refresh_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private KnowledgeIndexRebuildService rebuilder;
    @Autowired private KnowledgeDocumentDataService documents;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private KnowledgeEmbeddingService embedding;

    /** 记录每次 Embedding 的实际输入，用于断言增量刷新只重算变更文档。 */
    private final List<KnowledgeEmbeddingInput> recordedInputs = new ArrayList<>();

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
        recordedInputs.clear();
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "c".repeat(64), 512));
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<KnowledgeEmbeddingInput> inputs = invocation.getArgument(0);
            recordedInputs.addAll(inputs);
            return vectorsFor(inputs);
        });
    }

    /**
     * 业务目的：发布新文档后的刷新必须复用原 ACTIVE generation，只重新 Embedding 新文档，
     * 未变更文档的分块原样保留；防止每次发布都全量重建全部文档。
     */
    @Test
    void refreshReindexesOnlyChangedDocuments() {
        KnowledgeDocument existing = published("既有文档");
        documents.insert(existing);
        Long firstGeneration = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()).generationId();
        recordedInputs.clear();

        KnowledgeDocument added = published("新增文档");
        documents.insert(added);
        var result = rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(result.generationId()).isEqualTo(firstGeneration);
        assertThat(activeGenerationId()).isEqualTo(firstGeneration);
        assertThat(chunkCountOf(firstGeneration, existing.id())).isPositive();
        assertThat(chunkCountOf(firstGeneration, added.id())).isPositive();
        assertThat(recordedInputs).isNotEmpty()
                .allMatch(input -> input.documentId().equals(added.id()));
        assertThat(jdbcTemplate.queryForObject(
                "select document_count from knowledge_index_generation where id=?",
                Long.class, firstGeneration)).isEqualTo(2);
        System.out.printf("测试证据：场景=增量只重算新文档，generation=%d，重算文档=%s，embedding批次=%d%n",
                firstGeneration, added.id(), recordedInputs.size());
    }

    /**
     * 业务目的：刷新必须把已编辑文档的 ACTIVE 分块替换为当前修订内容与修订号，
     * 防止搜索命中旧修订正文。
     */
    @Test
    void refreshReplacesChangedDocumentChunks() {
        KnowledgeDocument published = published("原始正文");
        documents.insert(published);
        rebuilder.rebuild(insertJob("RUNNING"), noOpProgress());
        KnowledgeDocument edited = published.edit(fields("编辑后正文"), new DocumentAudit(NOW.plusSeconds(2), "editor"));
        documents.update(edited, published.revision());

        rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(chunkRevisionOf(activeGenerationId(), edited.id())).isEqualTo(edited.revision().value());
        assertThat(chunkContentsOf(activeGenerationId(), edited.id()))
                .containsExactly("编辑后正文");
        System.out.printf("测试证据：场景=刷新替换已编辑文档，文档=%d，修订=%d，内容=编辑后正文%n",
                edited.id(), edited.revision().value());
    }

    /**
     * 业务目的：已归档文档的分块必须在刷新中移除并重算计数，防止检索命中已失去发布资格的文档。
     */
    @Test
    void refreshRemovesChunksOfArchivedDocuments() {
        KnowledgeDocument published = published("待归档文档");
        documents.insert(published);
        KnowledgeDocument kept = published("保留文档");
        documents.insert(kept);
        Long generationId = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()).generationId();
        KnowledgeDocument archived = published.archive(new DocumentAudit(NOW.plusSeconds(3), "archiver"));
        documents.update(archived, published.revision());

        rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(chunkCountOf(generationId, archived.id())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select document_count from knowledge_index_generation where id=?",
                Long.class, generationId)).isEqualTo(1);
        System.out.printf("测试证据：场景=刷新移除归档文档，generation=%d，归档文档=%s，剩余文档=1%n",
                generationId, archived.id());
    }

    /**
     * 业务目的：没有 ACTIVE generation（首次部署）时刷新必须降级为全量重建，
     * 保证索引从零可建且不产生半截数据。
     */
    @Test
    void refreshFallsBackToFullRebuildWithoutActiveGeneration() {
        KnowledgeDocument published = published("首篇文档");
        documents.insert(published);
        assertThat(activeGenerationId()).isNull();

        var result = rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(result.generationId()).isNotNull();
        assertThat(activeGenerationId()).isEqualTo(result.generationId());
        assertThat(chunkCountOf(result.generationId(), published.id())).isPositive();
        System.out.printf("测试证据：场景=无ACTIVE降级全量，新generation=%d，文档=1%n", result.generationId());
    }

    /**
     * 业务目的：当前模型指纹与 ACTIVE generation 不一致时，旧向量与新模型不兼容，
     * 刷新必须降级为全量重建并切换代次，防止混写向量空间。
     */
    @Test
    void refreshFallsBackToFullRebuildOnModelFingerprintMismatch() {
        KnowledgeDocument published = published("模型变更文档");
        documents.insert(published);
        Long previous = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()).generationId();
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "d".repeat(64), 512));

        var result = rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(result.generationId()).isNotEqualTo(previous);
        assertThat(activeGenerationId()).isEqualTo(result.generationId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where id=? and status='RETIRED'",
                Long.class, previous)).isEqualTo(1);
        System.out.printf("测试证据：场景=模型指纹不一致降级全量，旧generation=%d，新generation=%d%n",
                previous, result.generationId());
    }

    /**
     * 业务目的：刷新中途 Embedding 失败时，已处理文档的旧修订分块必须整体保留（事务回滚），
     * 新文档不得残留部分分块，ACTIVE generation 不变，防止搜索读到半截文档。
     */
    @Test
    void refreshKeepsPreviousChunksWhenEmbeddingFails() {
        KnowledgeDocument published = published("失败保留文档");
        documents.insert(published);
        Long generationId = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()).generationId();
        KnowledgeDocument edited = published.edit(fields("失败新正文"), new DocumentAudit(NOW.plusSeconds(2), "editor"));
        documents.update(edited, published.revision());
        KnowledgeDocument pending = published("未开始的新文档");
        documents.insert(pending);
        when(embedding.embedDocuments(anyList()))
                .thenThrow(new IllegalStateException("simulated embedding failure"));

        assertThatThrownBy(() -> rebuilder.refresh(insertJob("RUNNING"), noOpProgress()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(activeGenerationId()).isEqualTo(generationId);
        assertThat(chunkRevisionOf(generationId, edited.id())).isEqualTo(published.revision().value());
        // 失败后仍保留发布时索引的旧修订正文，编辑后的新正文不得进入索引。
        assertThat(chunkContentsOf(generationId, edited.id())).containsExactly("失败保留文档");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_index_generation where status='BUILDING'", Long.class)).isZero();
        System.out.printf("测试证据：场景=刷新失败保留旧修订，generation=%d，文档=%d，修订=%d，BUILDING=0%n",
                generationId, edited.id(), published.revision().value());
    }

    /**
     * 业务目的：无变更时刷新必须是无副作用的幂等操作，不产生任何 Embedding 调用或分块变化。
     */
    @Test
    void refreshWithoutChangesIsNoop() {
        KnowledgeDocument published = published("无变更文档");
        documents.insert(published);
        Long generationId = rebuilder.rebuild(insertJob("RUNNING"), noOpProgress()).generationId();
        recordedInputs.clear();

        var result = rebuilder.refresh(insertJob("RUNNING"), noOpProgress());

        assertThat(result.generationId()).isEqualTo(generationId);
        assertThat(recordedInputs).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_chunk where generation_id=?",
                Long.class, generationId)).isPositive();
        System.out.printf("测试证据：场景=无变更幂等，generation=%d，embedding调用=0%n", generationId);
    }

    private KnowledgeIndexRebuildService.Progress noOpProgress() {
        return new KnowledgeIndexRebuildService.Progress(value -> { }, () -> { });
    }

    private KnowledgeDocument published(String body) {
        KnowledgeDocument draft = KnowledgeDocument.create(TestIds.next(), fields(body),
                new DocumentAudit(NOW, "author"));
        return draft.publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
    }

    private KnowledgeDocumentFields fields(String body) {
        return new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle("标题"), new DocumentBody(body),
                new DocumentDirectory(""), DocumentTags.of(List.of("tag")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"), KnowledgeScope.global());
    }

    private Long insertJob(String status) {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at, created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', ?, 0, ?, ?, 'test', 'test')
                """, id, status, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private Long activeGenerationId() {
        List<Long> active = jdbcTemplate.queryForList(
                "select id from knowledge_index_generation where status='ACTIVE'", Long.class);
        return active.isEmpty() ? null : active.getFirst();
    }

    private long chunkCountOf(Long generationId, Long documentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from knowledge_search_chunk where generation_id=? and document_id=?",
                Long.class, generationId, documentId);
    }

    private Long chunkRevisionOf(Long generationId, Long documentId) {
        return jdbcTemplate.queryForObject(
                "select max(source_revision) from knowledge_search_chunk where generation_id=? and document_id=?",
                Long.class, generationId, documentId);
    }

    private List<String> chunkContentsOf(Long generationId, Long documentId) {
        return jdbcTemplate.queryForList(
                "select content from knowledge_search_chunk where generation_id=? and document_id=? order by chunk_no",
                String.class, generationId, documentId);
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
