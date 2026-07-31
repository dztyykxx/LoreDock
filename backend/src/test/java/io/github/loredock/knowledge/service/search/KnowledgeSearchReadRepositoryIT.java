package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResolvedScope;
import io.github.loredock.knowledge.service.KnowledgeSearchEligibilityService;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class KnowledgeSearchReadRepositoryIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Long GENERATION_ID = 6130197811678937090L;
    private static final Long PROJECT_A = 6130197811678937091L;
    private static final Long PROJECT_B = 6130197811678937092L;
    private static final Long A_MAIN = 6130197811678937093L;
    private static final Long A_OTHER = 6130197811678937094L;
    private static final Long B_MAIN = 6130197811678937095L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_search_read_repository_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeSearchIndexDataService generations;

    @Autowired
    private KnowledgeSearchEligibilityService eligibility;

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
        jdbcTemplate.update("delete from knowledge_search_generation");
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        seedProject(PROJECT_A, "project-a");
        seedProject(PROJECT_B, "project-b");
        seedBranch(A_MAIN, PROJECT_A, "main");
        seedBranch(A_OTHER, PROJECT_A, "feature/other");
        seedBranch(B_MAIN, PROJECT_B, "main");
    }

    /**
     * 业务目的：搜索只能读取同时 ACTIVE 且拥有完整 V5 元数据的 generation，旧 ACTIVE 缺少搜索元数据时必须视为尚不可搜索。
     */
    @Test
    void activeReaderRequiresAtomicActiveGenerationAndSearchMetadata() {
        seedGeneration();

        Optional<ActiveKnowledgeSearchGeneration> active = generations.findActive();

        assertThat(active).contains(new ActiveKnowledgeSearchGeneration(
                GENERATION_ID, "BAAI/bge-small-zh-v1.5", "b".repeat(64), 512,
                "cjk-v1", "rrf-v1", 3, 4, NOW));
        jdbcTemplate.update("delete from knowledge_search_generation where generation_id=?", GENERATION_ID);
        assertThat(generations.findActive()).isEmpty();
        System.out.printf("测试证据：场景=活动搜索generation读取，完整generation=%s，删除V5元数据后可搜索=false%n",
                active.orElseThrow().generationId());
    }

    /**
     * 业务目的：GLOBAL 实时资格只保留当前 PUBLISHED 通用文档，并保持候选输入顺序，草稿、归档和项目文档必须立即排除。
     */
    @Test
    void globalEligibilityRetainsOnlyCurrentlyPublishedGlobalDocumentsInInputOrder() {
        Long globalSecond = id(11);
        Long globalFirst = id(10);
        Long draft = id(12);
        Long archived = id(13);
        Long project = id(14);
        seedDocument(globalFirst, "PUBLISHED", "GLOBAL", null, null);
        seedDocument(globalSecond, "PUBLISHED", "GLOBAL", null, null);
        seedDocument(draft, "DRAFT", "GLOBAL", null, null);
        seedDocument(archived, "ARCHIVED", "GLOBAL", null, null);
        seedDocument(project, "PUBLISHED", "PROJECT", PROJECT_A, null);

        List<Long> retained = eligibility.retainEligible(
                List.of(globalSecond, archived, project, globalFirst, draft),
                new KnowledgeSearchResolvedScope(KnowledgeBrowseContextType.GLOBAL, null, null, null, null));

        assertThat(retained).containsExactly(globalSecond, globalFirst);
        System.out.printf("测试证据：场景=GLOBAL实时资格，候选数=5，保留PUBLISHED通用=%s，排除数=3%n", retained);
    }

    /**
     * 业务目的：PROJECT 实时资格只允许通用、当前项目和当前分支，文档改到其他分支或项目后必须立即排除且不改变剩余顺序。
     */
    @Test
    void projectEligibilityAppliesCurrentThreeLayerScopeWithoutCrossBranchLeakage() {
        Long global = id(20);
        Long project = id(21);
        Long branch = id(22);
        Long otherBranch = id(23);
        Long otherProject = id(24);
        Long archived = id(25);
        seedDocument(global, "PUBLISHED", "GLOBAL", null, null);
        seedDocument(project, "PUBLISHED", "PROJECT", PROJECT_A, null);
        seedDocument(branch, "PUBLISHED", "BRANCH", PROJECT_A, A_MAIN);
        seedDocument(otherBranch, "PUBLISHED", "BRANCH", PROJECT_A, A_OTHER);
        seedDocument(otherProject, "PUBLISHED", "BRANCH", PROJECT_B, B_MAIN);
        seedDocument(archived, "ARCHIVED", "BRANCH", PROJECT_A, A_MAIN);

        List<Long> retained = eligibility.retainEligible(
                List.of(branch, otherProject, global, archived, project, otherBranch),
                new KnowledgeSearchResolvedScope(
                        KnowledgeBrowseContextType.PROJECT, "project-a", "main", PROJECT_A, A_MAIN));

        assertThat(retained).containsExactly(branch, global, project);
        System.out.printf("测试证据：场景=PROJECT三层实时资格，项目=%s，分支=%s，保留=%s，跨范围泄漏=0%n",
                PROJECT_A, A_MAIN, retained);
    }

    private void seedGeneration() {
        Long jobId = id(1);
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'SUCCEEDED', 100, ?, ?, 'test', 'test')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_index_generation(id, job_id, status, document_count, created_at, activated_at)
                values (?, ?, 'ACTIVE', 3, ?, ?)
                """, GENERATION_ID, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_search_generation(generation_id, model_id, model_checksum, vector_dimension,
                    chunk_strategy_version, fusion_config_version, document_count, chunk_count, created_at)
                values (?, 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 3, 4, ?)
                """, GENERATION_ID, "b".repeat(64), Timestamp.from(NOW));
    }

    private void seedProject(Long id, String identifier) {
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '测试项目', 'Java', 'ENABLED', ?, ?, 'test', 'test')
                """, id, identifier, identifier, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedBranch(Long id, Long projectId, String name) {
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, id, projectId, name, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedDocument(Long id, String status, String scopeType, Long projectId, Long branchId) {
        Timestamp publishedAt = "PUBLISHED".equals(status) ? Timestamp.from(NOW) : null;
        String publishedBy = "PUBLISHED".equals(status) ? "publisher" : null;
        Timestamp archivedAt = "ARCHIVED".equals(status) ? Timestamp.from(NOW) : null;
        String archivedBy = "ARCHIVED".equals(status) ? "archiver" : null;
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, status, revision, published_at, published_by,
                    archived_at, archived_by, created_at, updated_at, created_by, updated_by)
                values (?, 'MARKDOWN', '资格标题', '资格正文', '', ?, ?, ?, 'MANUAL', ?, 1,
                    ?, ?, ?, ?, ?, ?, 'test', 'test')
                """, id, scopeType, projectId, branchId, status, publishedAt, publishedBy,
                archivedAt, archivedBy, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private Long id(int suffix) {
        return (long) suffix;
    }
}
