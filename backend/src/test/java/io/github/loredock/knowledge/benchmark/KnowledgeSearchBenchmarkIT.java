package io.github.loredock.knowledge.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchWarning;
import io.github.loredock.knowledge.model.request.KnowledgeSearchFilters;
import io.github.loredock.knowledge.model.request.KnowledgeSearchQuery;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResponse;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import io.github.loredock.knowledge.service.KnowledgeSearchIndexDataService;
import io.github.loredock.knowledge.service.search.KnowledgeEmbeddingService;
import io.github.loredock.knowledge.service.search.KnowledgeSearchService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
@EnabledIfSystemProperty(named = "loredock.benchmark", matches = "true")
class KnowledgeSearchBenchmarkIT {

    private static final String MODEL_DIRECTORY_PROPERTY = "loredock.benchmark.model-dir";
    private static final String OUTPUT_PROPERTY = "loredock.benchmark.output";
    private static final String MODEL_CHECKSUM =
            "3a40c6eab3abdf2bd07651031a36038c2dfaf4ebb8d62ddc78f2324b2ff4389a";
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String APPLICATION_VERSION = "0.1.0-SNAPSHOT";
    private static final Instant FIXTURE_TIME = Instant.parse("2026-07-30T00:00:00Z");
    private static final long MAX_WARM_QUERY_MILLIS = 3_000L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_search_benchmark")
            .withUsername("loredock")
            .withPassword("loredock_benchmark");

    @Autowired
    private KnowledgeIndexRebuildService rebuilder;

    @Autowired
    private KnowledgeSearchService search;

    @Autowired
    private KnowledgeSearchIndexDataService generations;

    @Autowired
    private KnowledgeEmbeddingService embedding;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private KnowledgeSearchBenchmarkFixture.Fixture fixture;
    private Map<String, KnowledgeSearchBenchmarkFixture.Project> projects;
    private Map<Long, KnowledgeSearchBenchmarkFixture.Document> documents;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        Path modelDirectory = modelDirectory();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "基准管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "基准成员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.knowledge.search.embedding.model-id", () -> "BAAI/bge-small-zh-v1.5");
        registry.add("loredock.knowledge.search.embedding.model-uri",
                () -> modelDirectory.resolve("model.onnx").toUri().toString());
        registry.add("loredock.knowledge.search.embedding.tokenizer-uri",
                () -> modelDirectory.resolve("tokenizer.json").toUri().toString());
        registry.add("loredock.knowledge.search.embedding.checksum", () -> MODEL_CHECKSUM);
        registry.add("loredock.knowledge.search.embedding.output-name", () -> "sentence_embedding");
        registry.add("loredock.knowledge.search.embedding.max-tokens", () -> "512");
    }

    @BeforeEach
    void prepareBenchmark() {
        fixture = KnowledgeSearchBenchmarkFixture.load();
        new KnowledgeSearchBenchmarkFixtureValidator().validate(fixture);
        projects = new HashMap<>();
        fixture.manifest().projects().forEach(project -> projects.put(project.identifier(), project));
        documents = new HashMap<>();
        fixture.manifest().documents().forEach(document -> documents.put(document.id(), document));
        resetDatabase();
        seedProjects();
        seedDocuments();
    }

    /**
     * 业务目的：正式 T5 基准必须以真实 PostgreSQL、真实离线 BGE、生产分块/重建与搜索应用端口
     * 生成逐题证据；配置变化、执行错误、低于 80% Top-5、范围/生命周期泄漏或预热查询超过三秒都必须失败。
     */
    @Test
    void productionUseCaseMeetsQualityIsolationAndWarmLatencyGates() throws IOException {
        long coldStarted = System.nanoTime();
        KnowledgeEmbeddingModelDescriptor model = embedding.describeModel();
        embedding.embedQuery("知识检索模型预热");
        long coldStartMillis = elapsedMillis(coldStarted);

        Long jobId = insertRebuildJob();
        long rebuildStarted = System.nanoTime();
        KnowledgeIndexRebuildResult rebuild = rebuilder.rebuild(jobId, noOpProgress());
        long rebuildMillis = elapsedMillis(rebuildStarted);
        ActiveKnowledgeSearchGeneration fixedGeneration = requireActiveGeneration();
        assertFixedConfiguration(fixedGeneration, rebuild, model);

        List<QueryExecution> executions = new ArrayList<>();
        List<IsolationViolation> violations = new ArrayList<>();
        for (KnowledgeSearchBenchmarkFixture.Question question : fixture.questions()) {
            for (KnowledgeSearchMode mode : question.modes()) {
                assertThat(requireActiveGeneration()).isEqualTo(fixedGeneration);
                long started = System.nanoTime();
                KnowledgeSearchResponse response = search.search(toQuery(question, mode));
                long elapsedMillis = elapsedMillis(started);
                assertThat(response.generationId()).isEqualTo(fixedGeneration.generationId());
                assertThat(requireActiveGeneration()).isEqualTo(fixedGeneration);
                List<RankedResult> results = rankedResults(response);
                boolean hitTop5 = hitsTopFive(question, results);
                executions.add(new QueryExecution(
                        question.id(), mode, question.hasAnswer(), question.expectedDocumentIds(),
                        results, response.warnings(), results.size(), elapsedMillis, hitTop5, null));
                violations.addAll(findViolations(question, mode, results));
                System.out.printf(
                        "测试证据：场景=T5正式基准，问题=%s，范围=%s/%s/%s，模式=%s，generation=%s，"
                                + "结果ID=%s，Top5命中=%s，结果数=%d，耗时毫秒=%d%n",
                        question.id(), question.contextType(), safe(question.projectIdentifier()), safe(question.branch()),
                        mode, response.generationId(), results.stream().map(RankedResult::documentId).toList(),
                        hitTop5, results.size(), elapsedMillis);
            }
        }

        Map<KnowledgeSearchMode, ModeSummary> summaries = summarize(executions);
        long maxWarmMillis = executions.stream().mapToLong(QueryExecution::elapsedMillis).max().orElseThrow();
        boolean qualityPassed = summaries.get(KnowledgeSearchMode.HYBRID).topFiveRate() >= 0.80D;
        boolean isolationPassed = violations.isEmpty();
        boolean latencyPassed = maxWarmMillis <= MAX_WARM_QUERY_MILLIS;
        BenchmarkResult result = result(
                fixedGeneration, model, coldStartMillis, rebuildMillis, maxWarmMillis,
                summaries, violations, executions, qualityPassed, isolationPassed, latencyPassed);
        writeResult(result);

        assertThat(qualityPassed)
                .as("HYBRID 有答案 Top-5 命中率必须至少 80%%；逐题结果已写入机器报告")
                .isTrue();
        assertThat(violations).as("所有范围和生命周期泄漏必须为零").isEmpty();
        assertThat(maxWarmMillis).as("代表性预热查询必须不超过 3 秒").isLessThanOrEqualTo(MAX_WARM_QUERY_MILLIS);
        assertThat(requireActiveGeneration()).isEqualTo(fixedGeneration);
        assertThat(embedding.describeModel()).isEqualTo(model);
        System.out.printf(
                "测试证据：场景=T5完成门禁，HYBRID Top5=%.2f%%，隔离违规=%d，最大预热耗时毫秒=%d，"
                        + "重建文档=%d，分块=%d，结果文件=%s%n",
                summaries.get(KnowledgeSearchMode.HYBRID).topFiveRate() * 100D, violations.size(), maxWarmMillis,
                fixedGeneration.documentCount(), fixedGeneration.chunkCount(), outputPath());
    }

    private BenchmarkResult result(
            ActiveKnowledgeSearchGeneration generation,
            KnowledgeEmbeddingModelDescriptor model,
            long coldStartMillis,
            long rebuildMillis,
            long maxWarmMillis,
            Map<KnowledgeSearchMode, ModeSummary> summaries,
            List<IsolationViolation> violations,
            List<QueryExecution> executions,
            boolean qualityPassed,
            boolean isolationPassed,
            boolean latencyPassed
    ) {
        String postgresVersion = jdbcTemplate.queryForObject("select version()", String.class);
        Environment environment = new Environment(
                System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(), postgresVersion,
                "Testcontainers pgvector/pgvector:0.8.1-pg17", "CPU");
        Configuration configuration = new Configuration(
                APPLICATION_VERSION, fixture.manifest().benchmarkVersion(), generation.generationId(),
                model.modelId(), model.checksum(), generation.vectorDimension(), generation.chunkStrategyVersion(),
                generation.fusionConfigVersion(), generation.documentCount(), generation.chunkCount(), true);
        Gates gates = new Gates(qualityPassed, isolationPassed, latencyPassed,
                qualityPassed && isolationPassed && latencyPassed);
        return new BenchmarkResult(
                "t5-2026-07-30", Instant.now().toString(), environment, configuration, coldStartMillis,
                rebuildMillis, maxWarmMillis, fixture.questions().size(),
                fixture.questions().stream().filter(KnowledgeSearchBenchmarkFixture.Question::hasAnswer).count(),
                summaries, violations, executions, gates);
    }

    private Map<KnowledgeSearchMode, ModeSummary> summarize(List<QueryExecution> executions) {
        Map<KnowledgeSearchMode, ModeSummary> summaries = new EnumMap<>(KnowledgeSearchMode.class);
        for (KnowledgeSearchMode mode : KnowledgeSearchMode.values()) {
            List<QueryExecution> byMode = executions.stream().filter(result -> result.mode() == mode).toList();
            long answerable = byMode.stream().filter(QueryExecution::hasAnswer).count();
            long hits = byMode.stream().filter(QueryExecution::hasAnswer).filter(QueryExecution::hitTop5).count();
            List<Long> latencies = byMode.stream().map(QueryExecution::elapsedMillis).sorted().toList();
            long p95 = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95D) - 1));
            summaries.put(mode, new ModeSummary(answerable, hits, (double) hits / answerable, p95,
                    latencies.getLast()));
        }
        return Map.copyOf(summaries);
    }

    private List<IsolationViolation> findViolations(
            KnowledgeSearchBenchmarkFixture.Question question,
            KnowledgeSearchMode mode,
            List<RankedResult> results
    ) {
        List<IsolationViolation> violations = new ArrayList<>();
        for (RankedResult result : results) {
            var document = documents.get(result.documentId());
            if (document == null) {
                violations.add(new IsolationViolation(question.id(), mode, result.documentId(), "UNKNOWN_DOCUMENT"));
            } else if (document.status() != DocumentStatus.PUBLISHED) {
                violations.add(new IsolationViolation(question.id(), mode, result.documentId(), "LIFECYCLE"));
            } else if (!visible(question, document)) {
                violations.add(new IsolationViolation(question.id(), mode, result.documentId(), "SCOPE"));
            }
        }
        return List.copyOf(violations);
    }

    private boolean visible(
            KnowledgeSearchBenchmarkFixture.Question question,
            KnowledgeSearchBenchmarkFixture.Document document
    ) {
        if (question.contextType() == KnowledgeBrowseContextType.GLOBAL) {
            return document.scope().type() == KnowledgeScopeType.GLOBAL;
        }
        return switch (document.scope().type()) {
            case GLOBAL -> true;
            case PROJECT -> question.projectIdentifier().equals(document.scope().project());
            case BRANCH -> question.projectIdentifier().equals(document.scope().project())
                    && question.branch().equals(document.scope().branch());
        };
    }

    private List<RankedResult> rankedResults(KnowledgeSearchResponse response) {
        List<RankedResult> results = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            var result = response.results().get(index);
            results.add(new RankedResult(result.documentId(), index + 1, result.matchedBy().name(),
                    result.relevance()));
        }
        return List.copyOf(results);
    }

    private boolean hitsTopFive(
            KnowledgeSearchBenchmarkFixture.Question question,
            List<RankedResult> results
    ) {
        if (!question.hasAnswer()) {
            return false;
        }
        Set<Long> expected = Set.copyOf(question.expectedDocumentIds());
        return results.stream().filter(result -> result.rank() <= 5)
                .anyMatch(result -> expected.contains(result.documentId()));
    }

    private KnowledgeSearchQuery toQuery(
            KnowledgeSearchBenchmarkFixture.Question question,
            KnowledgeSearchMode mode
    ) {
        return new KnowledgeSearchQuery(
                question.contextType(), question.projectIdentifier(), question.branch(), question.query(), mode,
                new KnowledgeSearchFilters(question.filters().tags(), question.filters().format(),
                        question.filters().sourceType()), 10);
    }

    private void assertFixedConfiguration(
            ActiveKnowledgeSearchGeneration generation,
            KnowledgeIndexRebuildResult rebuild,
            KnowledgeEmbeddingModelDescriptor model
    ) {
        assertThat(generation.generationId()).isEqualTo(rebuild.generationId());
        assertThat(generation.modelId()).isEqualTo(model.modelId());
        assertThat(generation.modelChecksum()).isEqualTo(model.checksum()).isEqualTo(MODEL_CHECKSUM);
        assertThat(generation.vectorDimension()).isEqualTo(512);
        assertThat(generation.chunkStrategyVersion()).isEqualTo("cjk-v1");
        assertThat(generation.fusionConfigVersion()).isEqualTo("rrf-v1");
        assertThat(generation.documentCount()).isEqualTo(13);
    }

    private ActiveKnowledgeSearchGeneration requireActiveGeneration() {
        return generations.findActive().orElseThrow(() ->
                new IllegalStateException("formal benchmark requires one complete active generation"));
    }

    private void resetDatabase() {
        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_search_generation");
        jdbcTemplate.update("delete from knowledge_index_document");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document_tag");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
    }

    private void seedProjects() {
        for (var project : fixture.manifest().projects()) {
            jdbcTemplate.update("""
                    insert into project_space(id, identifier, name, description, technology_stack, status,
                        created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, '公开模拟项目', 'Java 21', 'ENABLED', ?, ?, 'benchmark', 'benchmark')
                    """, project.id(), project.identifier(), project.name(), Timestamp.from(FIXTURE_TIME),
                    Timestamp.from(FIXTURE_TIME));
            for (var branch : project.branches()) {
                jdbcTemplate.update("""
                        insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                        values (?, ?, ?, ?, ?, 'benchmark', 'benchmark')
                        """, branch.id(), project.id(), branch.name(), Timestamp.from(FIXTURE_TIME),
                        Timestamp.from(FIXTURE_TIME));
            }
        }
    }

    private void seedDocuments() {
        for (var document : fixture.manifest().documents()) {
            Long projectId = projectId(document.scope().project());
            Long branchId = branchId(document.scope().project(), document.scope().branch());
            Timestamp publishedAt = document.status() == DocumentStatus.DRAFT ? null : Timestamp.from(FIXTURE_TIME);
            Timestamp archivedAt = document.status() == DocumentStatus.ARCHIVED
                    ? Timestamp.from(FIXTURE_TIME.plusSeconds(60)) : null;
            jdbcTemplate.update("""
                    insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                        project_id, branch_id, source_type, status, revision, published_at, published_by,
                        archived_at, archived_by, created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, ?, 'benchmark', ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'benchmark', 'benchmark')
                    """, document.id(), document.format().name(), document.title(),
                    KnowledgeSearchBenchmarkFixture.readText(document.file()), document.scope().type().name(),
                    projectId, branchId, document.sourceType().name(), document.status().name(), publishedAt,
                    publishedAt == null ? null : "benchmark-reviewer", archivedAt,
                    archivedAt == null ? null : "benchmark-reviewer", Timestamp.from(FIXTURE_TIME),
                    Timestamp.from(archivedAt == null ? FIXTURE_TIME : FIXTURE_TIME.plusSeconds(60)));
            for (String tag : document.tags()) {
                jdbcTemplate.update("""
                        insert into knowledge_document_tag(document_id, normalized_name, display_name)
                        values (?, ?, ?)
                        """, document.id(), tag.toLowerCase(java.util.Locale.ROOT), tag);
            }
        }
    }

    private Long projectId(String identifier) {
        return identifier == null ? null : projects.get(identifier).id();
    }

    private Long branchId(String project, String branch) {
        if (project == null || branch == null) {
            return null;
        }
        return projects.get(project).branches().stream()
                .filter(candidate -> candidate.name().equals(branch))
                .findFirst().orElseThrow().id();
    }

    private Long insertRebuildJob() {
        Long jobId = 5919793063548944386L;
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, started_at, heartbeat_at,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'RUNNING', 0, ?, ?, ?, ?, 'benchmark', 'benchmark')
                """, jobId, Timestamp.from(FIXTURE_TIME), Timestamp.from(FIXTURE_TIME),
                Timestamp.from(FIXTURE_TIME), Timestamp.from(FIXTURE_TIME));
        return jobId;
    }

    private KnowledgeIndexRebuildService.Progress noOpProgress() {
        return new KnowledgeIndexRebuildService.Progress(percentage -> { }, () -> { });
    }

    private void writeResult(BenchmarkResult result) throws IOException {
        Path output = outputPath();
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private static Path modelDirectory() {
        String configured = System.getProperty(MODEL_DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("formal benchmark requires loredock.benchmark.model-dir");
        }
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("model.onnx"))
                || !Files.isRegularFile(directory.resolve("tokenizer.json"))) {
            throw new IllegalStateException("formal benchmark model resources are incomplete");
        }
        return directory;
    }

    private Path outputPath() {
        return Path.of(System.getProperty(OUTPUT_PROPERTY, "target/knowledge-search-benchmark-result.json"))
                .toAbsolutePath().normalize();
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }

    record RankedResult(Long documentId, int rank, String matchedBy, double relevance) {
    }

    record QueryExecution(
            String questionId,
            KnowledgeSearchMode mode,
            boolean hasAnswer,
            List<Long> expectedDocumentIds,
            List<RankedResult> results,
            List<KnowledgeSearchWarning> warnings,
            int resultCount,
            long elapsedMillis,
            boolean hitTop5,
            String errorCode
    ) {
        QueryExecution {
            expectedDocumentIds = List.copyOf(expectedDocumentIds);
            results = List.copyOf(results);
            warnings = List.copyOf(warnings);
        }
    }

    record IsolationViolation(String questionId, KnowledgeSearchMode mode, Long documentId, String reason) {
    }

    record ModeSummary(long answerableCount, long topFiveHitCount, double topFiveRate, long p95Millis,
                       long maxMillis) {
    }

    record Environment(String os, String architecture, String javaVersion, int availableProcessors,
                       long maxHeapBytes, String postgresVersion, String databaseImage, String inferenceDevice) {
    }

    record Configuration(String applicationVersion, String benchmarkVersion, Long generationId, String modelId,
                         String modelChecksum, int vectorDimension, String chunkStrategyVersion,
                         String fusionConfigVersion, long documentCount, long chunkCount, boolean modelPrewarmed) {
    }

    record Gates(boolean hybridTopFivePassed, boolean isolationPassed, boolean warmLatencyPassed,
                 boolean allPassed) {
    }

    record BenchmarkResult(
            String runId,
            String executedAt,
            Environment environment,
            Configuration configuration,
            long coldStartMillis,
            long rebuildMillis,
            long maxWarmQueryMillis,
            int questionCount,
            long answerableCount,
            Map<KnowledgeSearchMode, ModeSummary> modeSummaries,
            List<IsolationViolation> isolationViolations,
            List<QueryExecution> executions,
            Gates gates
    ) {
        BenchmarkResult {
            modeSummaries = Map.copyOf(modeSummaries);
            isolationViolations = List.copyOf(isolationViolations);
            executions = executions.stream()
                    .sorted(Comparator.comparing(QueryExecution::questionId).thenComparing(QueryExecution::mode))
                    .toList();
        }
    }
}
