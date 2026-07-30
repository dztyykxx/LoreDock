package io.github.loredock.knowledge.infrastructure.search;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.search.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.application.search.KnowledgeKeywordCandidatePort;
import io.github.loredock.knowledge.application.search.KnowledgeSearchCandidate;
import io.github.loredock.knowledge.application.search.KnowledgeSearchCandidateRequest;
import io.github.loredock.knowledge.application.search.KnowledgeSearchFilters;
import io.github.loredock.knowledge.application.search.KnowledgeSearchResolvedScope;
import io.github.loredock.knowledge.application.search.KnowledgeSemanticCandidatePort;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSourceType;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PostgresKnowledgeCandidateRepositoryIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final UUID GENERATION_ID = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_A = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT_B = UUID.fromString("51000000-0000-0000-0000-000000000003");
    private static final UUID A_MAIN = UUID.fromString("51000000-0000-0000-0000-000000000004");
    private static final UUID A_FEATURE = UUID.fromString("51000000-0000-0000-0000-000000000005");
    private static final UUID B_MAIN = UUID.fromString("51000000-0000-0000-0000-000000000006");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_candidate_repository_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeKeywordCandidatePort keywordCandidates;

    @Autowired
    private KnowledgeSemanticCandidatePort semanticCandidates;

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
        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_search_generation");
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        seedProjectsAndGeneration();
    }

    /**
     * 业务目的：同一词出现在标题或标签时必须高于仅正文命中，且多个分块按文档和分块序号稳定返回。
     */
    @Test
    void keywordRankingPrefersTitleAndTagsOverBodyWithStableTieBreak() {
        UUID title = id(10);
        UUID tag = id(11);
        UUID body = id(12);
        seedDocument(title, "恢复手册", "普通说明", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of("运行"), axisVector(0, 1));
        seedDocument(tag, "操作手册", "普通说明", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of("恢复"), axisVector(1, 1));
        seedDocument(body, "操作手册", "执行恢复流程", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of("运行"), axisVector(2, 1));

        List<KnowledgeSearchCandidate> found = keywordCandidates.findCandidates(globalRequest(50), "恢复");

        assertThat(found).extracting(KnowledgeSearchCandidate::documentId)
                .containsExactly(title, tag, body);
        assertThat(found).extracting(KnowledgeSearchCandidate::rawScore)
                .isSortedAccordingTo((left, right) -> Double.compare(right, left));
        System.out.printf("测试证据：场景=关键词字段权重，generation=%s，排序=%s，候选数=%d%n",
                GENERATION_ID, found.stream().map(KnowledgeSearchCandidate::documentId).toList(), found.size());
    }

    /**
     * 业务目的：无法形成 CJK 词项的合法短查询必须在固定范围内按字面子串回退，特殊字符不能成为 TSQuery 语法。
     */
    @Test
    void keywordShortQueryUsesBoundedLiteralFallbackWithoutParsingSyntax() {
        UUID literal = id(20);
        UUID otherProject = id(21);
        seedDocument(literal, "* 运维约定", "星号按字面处理", "PROJECT", PROJECT_A, null,
                "PLAIN_TEXT", "MANUAL", List.of("符号"), axisVector(0, 1));
        seedDocument(otherProject, "* 更高结果", "不应泄漏", "PROJECT", PROJECT_B, null,
                "PLAIN_TEXT", "MANUAL", List.of("符号"), axisVector(0, 1));

        List<KnowledgeSearchCandidate> found = keywordCandidates.findCandidates(projectRequest(50), "*");

        assertThat(found).extracting(KnowledgeSearchCandidate::documentId).containsExactly(literal);
        System.out.printf("测试证据：场景=关键词短查询回退，范围项目=%s，命中文档=%s，泄漏数=0%n",
                PROJECT_A, found.getFirst().documentId());
    }

    /**
     * 业务目的：标签、格式和来源过滤必须在关键词候选 SQL 内同时生效，避免融合后才隐藏不合格结果。
     */
    @Test
    void keywordFiltersRequireAllTagsFormatAndSourceBeforeRanking() {
        UUID expected = id(30);
        seedDocument(expected, "恢复指南", "恢复", "GLOBAL", null, null,
                "MARKDOWN", "WIKI", List.of("api", "恢复"), axisVector(0, 1));
        seedDocument(id(31), "恢复指南", "恢复", "GLOBAL", null, null,
                "MARKDOWN", "WIKI", List.of("api"), axisVector(0, 1));
        seedDocument(id(32), "恢复指南", "恢复", "GLOBAL", null, null,
                "PLAIN_TEXT", "WIKI", List.of("api", "恢复"), axisVector(0, 1));
        seedDocument(id(33), "恢复指南", "恢复", "GLOBAL", null, null,
                "MARKDOWN", "UPLOAD", List.of("api", "恢复"), axisVector(0, 1));

        KnowledgeSearchCandidateRequest request = new KnowledgeSearchCandidateRequest(generation(), globalScope(),
                new KnowledgeSearchFilters(List.of("api", "恢复"), DocumentFormat.MARKDOWN,
                        DocumentSourceType.WIKI), 50);
        List<KnowledgeSearchCandidate> found = keywordCandidates.findCandidates(request, "恢复");

        assertThat(found).extracting(KnowledgeSearchCandidate::documentId).containsExactly(expected);
        System.out.printf("测试证据：场景=关键词组合过滤，标签数=2，格式=MARKDOWN，来源=WIKI，候选数=%d%n",
                found.size());
    }

    /**
     * 业务目的：项目查询只允许 GLOBAL、当前 PROJECT 和当前 BRANCH，同项目其他分支及同名外部项目必须零泄漏。
     */
    @Test
    void keywordScopeIsEnforcedBeforeCandidateLimitAcrossProjectsAndBranches() {
        UUID global = id(40);
        UUID project = id(41);
        UUID branch = id(42);
        UUID draft = id(43);
        UUID archived = id(44);
        seedDocument(global, "隔离", "隔离", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        seedDocument(project, "隔离", "隔离", "PROJECT", PROJECT_A, null,
                "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        seedDocument(branch, "隔离", "隔离", "BRANCH", PROJECT_A, A_MAIN,
                "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        seedLifecycleOnlyDocument(draft, "DRAFT", "隔离草稿");
        seedLifecycleOnlyDocument(archived, "ARCHIVED", "隔离归档");
        for (int index = 0; index < 60; index++) {
            seedDocument(id(100 + index), "隔离高分", "隔离隔离隔离", "BRANCH", PROJECT_A, A_FEATURE,
                    "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
            seedDocument(id(200 + index), "隔离高分", "隔离隔离隔离", "BRANCH", PROJECT_B, B_MAIN,
                    "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        }

        List<KnowledgeSearchCandidate> found = keywordCandidates.findCandidates(projectRequest(2), "隔离");
        List<KnowledgeSearchCandidate> globalFound = keywordCandidates.findCandidates(globalRequest(50), "隔离");

        assertThat(found).hasSize(2).allMatch(candidate ->
                candidate.scope().projectId() == null || candidate.scope().projectId().equals(PROJECT_A));
        assertThat(found).noneMatch(candidate -> A_FEATURE.equals(candidate.scope().branchId()));
        assertThat(found).extracting(KnowledgeSearchCandidate::documentId)
                .allMatch(documentId -> List.of(global, project, branch).contains(documentId));
        assertThat(found).extracting(KnowledgeSearchCandidate::documentId).doesNotContain(draft, archived);
        assertThat(globalFound).extracting(KnowledgeSearchCandidate::documentId).containsExactly(global);
        System.out.printf("测试证据：场景=关键词候选前置隔离，候选上限=2，越界高分分块=120，"
                        + "项目结果=%s，全局结果=%s，泄漏数=0%n",
                found.stream().map(KnowledgeSearchCandidate::documentId).toList(),
                globalFound.stream().map(KnowledgeSearchCandidate::documentId).toList());
    }

    /**
     * 业务目的：语义候选必须按精确余弦距离排序，并在距离相同的情况下按文档 ID、分块序号稳定排序。
     */
    @Test
    void semanticCandidatesUseExactCosineRankingAndStableTieBreak() {
        UUID closest = id(50);
        UUID tiedFirst = id(51);
        UUID tiedSecond = id(52);
        seedDocument(closest, "语义一", "语义内容", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        seedDocument(tiedFirst, "语义二", "语义内容", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of(), twoAxisVector(0.8f, 0.6f));
        seedDocument(tiedSecond, "语义三", "语义内容", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of(), twoAxisVector(0.8f, 0.6f));

        List<KnowledgeSearchCandidate> found = semanticCandidates.findCandidates(
                globalRequest(50), new KnowledgeEmbeddingVector(axisVector(0, 1)));

        assertThat(found).extracting(KnowledgeSearchCandidate::documentId)
                .containsExactly(closest, tiedFirst, tiedSecond);
        assertThat(found.getFirst().rawScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        System.out.printf("测试证据：场景=精确余弦排序，generation=%s，排序=%s，最高相似度=%.4f%n",
                GENERATION_ID, found.stream().map(KnowledgeSearchCandidate::documentId).toList(),
                found.getFirst().rawScore());
    }

    /**
     * 业务目的：即使其他项目或其他分支拥有更近向量，也必须先执行范围和组合过滤再应用固定候选上限。
     */
    @Test
    void semanticScopeAndFiltersExcludeCloserOutOfScopeVectorsBeforeLimit() {
        UUID expected = id(60);
        seedDocument(expected, "允许结果", "语义内容", "BRANCH", PROJECT_A, A_MAIN,
                "MARKDOWN", "WIKI", List.of("api", "恢复"), twoAxisVector(0.7f, 0.7f));
        seedDocument(id(61), "错误格式", "语义内容", "BRANCH", PROJECT_A, A_MAIN,
                "PLAIN_TEXT", "WIKI", List.of("api", "恢复"), axisVector(0, 1));
        seedDocument(id(62), "错误标签", "语义内容", "BRANCH", PROJECT_A, A_MAIN,
                "MARKDOWN", "WIKI", List.of("api"), axisVector(0, 1));
        for (int index = 0; index < 60; index++) {
            seedDocument(id(300 + index), "外部分支", "语义内容", "BRANCH", PROJECT_A, A_FEATURE,
                    "MARKDOWN", "WIKI", List.of("api", "恢复"), axisVector(0, 1));
            seedDocument(id(400 + index), "外部项目", "语义内容", "BRANCH", PROJECT_B, B_MAIN,
                    "MARKDOWN", "WIKI", List.of("api", "恢复"), axisVector(0, 1));
        }
        KnowledgeSearchCandidateRequest request = new KnowledgeSearchCandidateRequest(generation(), projectScope(),
                new KnowledgeSearchFilters(List.of("api", "恢复"), DocumentFormat.MARKDOWN,
                        DocumentSourceType.WIKI), 1);

        List<KnowledgeSearchCandidate> found = semanticCandidates.findCandidates(
                request, new KnowledgeEmbeddingVector(axisVector(0, 1)));

        assertThat(found).extracting(KnowledgeSearchCandidate::documentId).containsExactly(expected);
        System.out.printf("测试证据：场景=语义前置隔离与过滤，候选上限=1，越界更近向量=120，泄漏数=0，结果=%s%n",
                found.getFirst().documentId());
    }

    /**
     * 业务目的：语义查询向量维度错误必须在访问数据库前失败，避免 PostgreSQL 低层错误泄漏或隐式截断。
     */
    @Test
    void semanticCandidatesRejectWrongVectorDimensionBeforeSqlExecution() {
        float[] invalid = new float[511];
        Arrays.fill(invalid, 0.1f);

        assertThatThrownBy(() -> semanticCandidates.findCandidates(
                globalRequest(50), new KnowledgeEmbeddingVector(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
        System.out.println("测试证据：场景=语义查询向量维度错误，实际维度=511，数据库候选查询未执行");
    }

    /**
     * 业务目的：首版语义候选必须保持有界精确扫描并记录代表性计划与耗时，不能在没有完整基准证据时偷偷引入 HNSW。
     */
    @Test
    void semanticCandidatePlanRecordsExactBoundedScanWithoutApproximateIndex() {
        seedDocument(id(70), "计划证据", "精确向量检索", "GLOBAL", null, null,
                "MARKDOWN", "MANUAL", List.of(), axisVector(0, 1));
        String queryVector = vectorLiteral(axisVector(0, 1));
        long started = System.nanoTime();
        List<String> plan = jdbcTemplate.queryForList("""
                explain (analyze, buffers, format text)
                select document_id, chunk_no
                from knowledge_search_chunk
                where generation_id = ? and scope_type = 'GLOBAL'
                order by embedding <=> cast(? as vector), document_id, chunk_no
                limit 50
                """, String.class, GENERATION_ID, queryVector);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        long approximateIndexes = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'knowledge_search_chunk'
                  and lower(indexdef) like '%using hnsw%'
                """, Long.class);

        assertThat(plan).isNotEmpty();
        assertThat(String.join(" ", plan)).doesNotContainIgnoringCase("hnsw");
        assertThat(approximateIndexes).isZero();
        System.out.printf("测试证据：场景=语义精确计划，generation=%s，候选上限=50，计划首节点=%s，"
                        + "HNSW索引数=%d，EXPLAIN耗时毫秒=%d%n",
                GENERATION_ID, plan.getFirst().strip(), approximateIndexes, elapsedMillis);
    }

    private KnowledgeSearchCandidateRequest globalRequest(int limit) {
        return new KnowledgeSearchCandidateRequest(generation(), globalScope(),
                new KnowledgeSearchFilters(List.of(), null, null), limit);
    }

    private KnowledgeSearchCandidateRequest projectRequest(int limit) {
        return new KnowledgeSearchCandidateRequest(generation(), projectScope(),
                new KnowledgeSearchFilters(List.of(), null, null), limit);
    }

    private KnowledgeSearchResolvedScope globalScope() {
        return new KnowledgeSearchResolvedScope(KnowledgeBrowseContextType.GLOBAL, null, null, null, null);
    }

    private KnowledgeSearchResolvedScope projectScope() {
        return new KnowledgeSearchResolvedScope(
                KnowledgeBrowseContextType.PROJECT, "project-a", "main", PROJECT_A, A_MAIN);
    }

    private ActiveKnowledgeSearchGeneration generation() {
        return new ActiveKnowledgeSearchGeneration(GENERATION_ID, "BAAI/bge-small-zh-v1.5", "b".repeat(64),
                512, "cjk-v1", "rrf-v1", 1, 1, NOW);
    }

    private void seedProjectsAndGeneration() {
        for (Object[] project : List.of(
                new Object[]{PROJECT_A, "project-a", "项目甲"},
                new Object[]{PROJECT_B, "project-b", "项目乙"})) {
            jdbcTemplate.update("""
                    insert into project_space(id, identifier, name, description, technology_stack, status,
                        created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, '测试项目', 'Java', 'ENABLED', ?, ?, 'test', 'test')
                    """, project[0], project[1], project[2], Timestamp.from(NOW), Timestamp.from(NOW));
        }
        for (Object[] branch : List.of(
                new Object[]{A_MAIN, PROJECT_A, "main"},
                new Object[]{A_FEATURE, PROJECT_A, "feature/same"},
                new Object[]{B_MAIN, PROJECT_B, "main"})) {
            jdbcTemplate.update("""
                    insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 'test', 'test')
                    """, branch[0], branch[1], branch[2], Timestamp.from(NOW), Timestamp.from(NOW));
        }
        UUID jobId = id(1);
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'SUCCEEDED', 100, ?, ?, 'test', 'test')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_index_generation(id, job_id, status, document_count, created_at, activated_at)
                values (?, ?, 'ACTIVE', 0, ?, ?)
                """, GENERATION_ID, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_search_generation(generation_id, model_id, model_checksum, vector_dimension,
                    chunk_strategy_version, fusion_config_version, document_count, chunk_count, created_at)
                values (?, 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 0, 0, ?)
                """, GENERATION_ID, "b".repeat(64), Timestamp.from(NOW));
    }

    private void seedDocument(
            UUID documentId,
            String title,
            String content,
            String scopeType,
            UUID projectId,
            UUID branchId,
            String format,
            String sourceType,
            List<String> tags,
            float[] vector
    ) {
        String wikiUrl = "WIKI".equals(sourceType) ? "https://example.com/wiki/" + documentId : null;
        String filename = "UPLOAD".equals(sourceType) ? "fixture.md" : null;
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, wiki_url, original_filename, status, revision,
                    published_at, published_by, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, 'PUBLISHED', 1, ?, 'publisher', ?, ?, 'test', 'test')
                """, documentId, format, title, content, scopeType, projectId, branchId, sourceType,
                wikiUrl, filename, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        String tagsJson = tags.stream().map(tag -> "\"" + tag + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        jdbcTemplate.update("""
                insert into knowledge_index_document(generation_id, document_id, source_revision, format,
                    title, body, directory_path, tags, scope_type, project_id, branch_id, source_type,
                    wiki_url, original_filename, source_updated_at)
                values (?, ?, 1, ?, ?, ?, '', cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)
                """, GENERATION_ID, documentId, format, title, content, tagsJson, scopeType,
                projectId, branchId, sourceType, wikiUrl, filename, Timestamp.from(NOW));
        CjkKnowledgeTextAnalyzer analyzer = new CjkKnowledgeTextAnalyzer();
        var terms = analyzer.analyzeDocument(title, tags, content);
        jdbcTemplate.update("""
                insert into knowledge_search_chunk(generation_id, document_id, chunk_no, start_offset, end_offset,
                    content, title_terms, tag_terms, content_terms, search_vector, embedding, scope_type,
                    project_id, branch_id, format, source_type, normalized_tags, source_updated_at)
                values (?, ?, 0, 0, ?, ?, ?, ?, ?,
                    setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B') ||
                    setweight(to_tsvector('simple', ?), 'C'), cast(? as vector), ?, ?, ?, ?, ?, ?, ?)
                """, GENERATION_ID, documentId, content.codePointCount(0, content.length()), content,
                String.join(" ", terms.titleTerms()), String.join(" ", terms.tagTerms()),
                String.join(" ", terms.contentTerms()), String.join(" ", terms.titleTerms()),
                String.join(" ", terms.tagTerms()), String.join(" ", terms.contentTerms()), vectorLiteral(vector),
                scopeType, projectId, branchId, format, sourceType, tags.toArray(String[]::new), Timestamp.from(NOW));
        analyzer.close();
    }

    private float[] axisVector(int axis, float value) {
        float[] vector = new float[512];
        vector[axis] = value;
        return vector;
    }

    private void seedLifecycleOnlyDocument(UUID documentId, String status, String title) {
        if ("DRAFT".equals(status)) {
            jdbcTemplate.update("""
                    insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                        source_type, status, revision, created_at, updated_at, created_by, updated_by)
                    values (?, 'MARKDOWN', ?, '隔离', '', 'GLOBAL', 'MANUAL', 'DRAFT', 1,
                        ?, ?, 'test', 'test')
                    """, documentId, title, Timestamp.from(NOW), Timestamp.from(NOW));
            return;
        }
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                    source_type, status, revision, archived_at, archived_by, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'MARKDOWN', ?, '隔离', '', 'GLOBAL', 'MANUAL', 'ARCHIVED', 1,
                    ?, 'archiver', ?, ?, 'test', 'test')
                """, documentId, title, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private float[] twoAxisVector(float first, float second) {
        float[] vector = new float[512];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private UUID id(int suffix) {
        return UUID.fromString("52000000-0000-0000-0000-" + String.format("%012d", suffix));
    }
}
