package io.github.loredock.knowledge.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.model.command.KnowledgeSearchChunkWrite;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.result.KnowledgeSearchGenerationMetadata;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
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
class KnowledgeSearchIndexDataServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Long GENERATION_ID = 4566561981804856389L;
    private static final Long DOCUMENT_ID = 5783280990902428195L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_search_repository_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeSearchIndexDataService repository;

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
    void resetDatabase() {
        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        seedGlobalProjection();
    }

    /**
     * 业务目的：模型、分块、融合版本和完整性计数必须原样往返，避免查询阶段使用与构建阶段不一致的配置。
     */
    @Test
    void generationMetadataAndChunkFactsRoundTripWithoutLosingTypes() {
        KnowledgeSearchGenerationMetadata metadata = metadata();
        repository.writeChunks(List.of(chunk(vector(0.01f),
                List.of("标题词"), List.of("恢复标签"), List.of("正文词"),
                List.of("恢复", "api,配置", "quote'"))));

        assertThat(repository.findGeneration(GENERATION_ID)).contains(metadata);
        String searchVector = jdbcTemplate.queryForObject(
                "select search_vector::text from knowledge_search_chunk where generation_id=? and document_id=?",
                String.class, GENERATION_ID, DOCUMENT_ID);
        assertThat(searchVector).contains(":1A", ":2B", ":3C");
        String[] tags = jdbcTemplate.queryForObject(
                "select normalized_tags from knowledge_search_chunk where generation_id=? and document_id=?",
                (resultSet, rowNum) -> (String[]) resultSet.getArray(1).getArray(),
                GENERATION_ID, DOCUMENT_ID);
        assertThat(tags).containsExactly("恢复", "api,配置", "quote'");
        assertThat(jdbcTemplate.queryForObject(
                "select vector_dims(embedding) from knowledge_search_chunk where generation_id=?",
                Integer.class, GENERATION_ID)).isEqualTo(512);
        System.out.printf("测试证据：场景=搜索索引往返，generation=%s，文档数=%d，分块数=%d，维度=%d，标签数=%d%n",
                metadata.generationId(), metadata.documentCount(), metadata.chunkCount(),
                metadata.vectorDimension(), tags.length);
    }

    /**
     * 业务目的：向量维度、NaN 或 Infinity 任一非法时必须在执行批次 SQL 前整体拒绝，
     * 防止留下部分分块或把不可计算向量交给 PostgreSQL。
     */
    @Test
    void invalidVectorsRejectWholeBatchBeforeAnyChunkIsInserted() {
        float[] shortVector = vector(0.01f);
        shortVector = Arrays.copyOf(shortVector, 511);
        float[] nanVector = vector(0.01f);
        nanVector[12] = Float.NaN;
        float[] infiniteVector = vector(0.01f);
        infiniteVector[25] = Float.POSITIVE_INFINITY;

        for (float[] invalid : List.of(shortVector, nanVector, infiniteVector)) {
            KnowledgeSearchChunkWrite validFirst = chunk(vector(0.01f), List.of("标题"), List.of(),
                    List.of("正文"), List.of("safe"));
            KnowledgeSearchChunkWrite invalidSecond = chunk(invalid, List.of("标题"), List.of(),
                    List.of("正文"), List.of("safe"));
            assertThatThrownBy(() -> repository.writeChunks(List.of(validFirst, invalidSecond)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_search_chunk", Long.class))
                    .isZero();
        }
        System.out.println("测试证据：场景=非法向量批次，维度错误/NaN/Infinity均拒绝，落库分块数=0");
    }

    /**
     * 业务目的：词项和正文即使包含 SQL/TSQuery 特殊字符也只能作为绑定参数保存，
     * 防止索引构建输入改变 SQL 结构或删除其他检索事实。
     */
    @Test
    void specialTermsAreBoundAsDataAndCannotChangeSqlStructure() {
        repository.writeChunks(List.of(chunk(vector(0.02f),
                List.of("api'); delete from knowledge_search_chunk; --"),
                List.of("配置:*"), List.of("恢复 OR title:(导出)"), List.of("安全"))));

        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_search_chunk", Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select title_terms from knowledge_search_chunk where generation_id=?",
                String.class, GENERATION_ID)).contains("delete from knowledge_search_chunk");
        System.out.println("测试证据：场景=特殊字符参数绑定，分块表存在，落库分块数=1，输入仅作为数据处理");
    }

    /**
     * 业务目的：进程在批次提交后中断并重试同一批时必须得到同一分块事实，不能因复合键冲突失败或重复计数。
     */
    @Test
    void repeatedChunkBatchIsIdempotentForTheSameGenerationAndChunkKey() {
        KnowledgeSearchChunkWrite chunk = chunk(vector(0.03f),
                List.of("标题"), List.of("标签"), List.of("正文"), List.of("恢复"));

        repository.writeChunks(List.of(chunk));
        repository.writeChunks(List.of(chunk));

        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_search_chunk", Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select embedding::text from knowledge_search_chunk where generation_id=?",
                String.class, GENERATION_ID)).startsWith("[0.03");
        System.out.println("测试证据：场景=分块批次幂等重试，提交次数=2，最终分块数=1，向量值保持一致");
    }

    private KnowledgeSearchGenerationMetadata metadata() {
        return new KnowledgeSearchGenerationMetadata(
                GENERATION_ID,
                "BAAI/bge-small-zh-v1.5",
                "b".repeat(64),
                512,
                "cjk-v1",
                "rrf-v1",
                1,
                1,
                NOW
        );
    }

    private KnowledgeSearchChunkWrite chunk(
            float[] embedding,
            List<String> titleTerms,
            List<String> tagTerms,
            List<String> contentTerms,
            List<String> normalizedTags
    ) {
        return new KnowledgeSearchChunkWrite(
                GENERATION_ID, DOCUMENT_ID, 0, 0, 8, "恢复正文",
                1, "恢复标题", "[\"恢复\"]", null, null, null,
                titleTerms, tagTerms, contentTerms, embedding,
                KnowledgeScopeType.GLOBAL, null, null,
                DocumentFormat.MARKDOWN, DocumentSourceType.MANUAL,
                normalizedTags, NOW
        );
    }

    private float[] vector(float value) {
        float[] vector = new float[512];
        Arrays.fill(vector, value);
        return vector;
    }

    private void seedGlobalProjection() {
        Long jobId = 1674921486353642292L;
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                    source_type, status, revision, published_at, published_by, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'MARKDOWN', '恢复标题', '恢复正文', '', 'GLOBAL', 'MANUAL', 'PUBLISHED', 1,
                    ?, 'publisher', ?, ?, 'test', 'test')
                """, DOCUMENT_ID, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'RUNNING', 50, ?, ?, 'test', 'test')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_index_generation(
                    id, job_id, status, model_id, model_checksum, vector_dimension,
                    chunk_strategy_version, fusion_config_version, document_count, chunk_count, created_at)
                values (?, ?, 'BUILDING', 'BAAI/bge-small-zh-v1.5', ?, 512,
                    'cjk-v1', 'rrf-v1', 1, 1, ?)
                """, GENERATION_ID, jobId, "b".repeat(64), Timestamp.from(NOW));
    }
}
