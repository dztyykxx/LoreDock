package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.service.AgentRetrievalService;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasEvalMetrics.QaVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.service.KnowledgeDocumentDataService;
import io.github.loredock.knowledge.service.KnowledgeIndexRebuildService;
import io.github.loredock.qa.api.QaService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
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

/**
 * Atlas Agent 真实模型评估 IT：真实 PostgreSQL + 真实 BGE 语义检索 + 生产 ChatModel，
 * 完整运行 40 条 QA 用例与 8 条知识整理用例，写出机器报告并输出逐条可核验证据。
 *
 * <p>仅在显式设置 {@code -Dloredock.agent-eval=true} 时运行，避免日常测试产生外部调用费用；
 * 语义检索模型目录通过 {@code -Dloredock.agent-eval.model-dir} 指定，
 * ChatModel 密钥读取环境变量 {@code LOREDOCK_AGENT_MODEL_API_KEY}。
 * 冒烟验证可用 {@code -Dloredock.agent-eval.qa-cases=N} 与
 * {@code -Dloredock.agent-eval.curation-cases=N} 限制用例数量，先确认链路再全量执行。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.model.chat=openai", "loredock.agent.enabled=true"})
@Testcontainers
@EnabledIfSystemProperty(named = "loredock.agent-eval", matches = "true")
class AtlasAgentEvalRealModelIT {

    private static final String MODEL_DIRECTORY_PROPERTY = "loredock.agent-eval.model-dir";
    private static final String MODEL_CHECKSUM =
            "3a40c6eab3abdf2bd07651031a36038c2dfaf4ebb8d62ddc78f2324b2ff4389a";
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    /** 单条用例等待终态的超时：生产运行上限 90 秒，留足模型重试与排队余量。 */
    private static final Duration PER_CASE_TIMEOUT = Duration.ofMinutes(5);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_agent_eval")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private QaService questions;
    @Autowired private AgentRetrievalService retrievals;
    @Autowired private KnowledgeTaskService tasks;
    @Autowired private KnowledgeDraftService drafts;
    @Autowired private KnowledgeDocumentDataService documents;
    @Autowired private KnowledgeIndexRebuildService rebuilder;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        Path modelDirectory = modelDirectory();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "评估管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "评估成员");
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
        registry.add("loredock.agent.model.api-key", AtlasAgentEvalRealModelIT::requireApiKey);
        registry.add("loredock.agent.model.base-url", () -> System.getenv().getOrDefault(
                "LOREDOCK_AGENT_MODEL_BASE_URL", "https://api.deepseek.com"));
    }

    @BeforeEach
    void prepareEvalEnvironment() {
        AtlasAgentEvalSeeder seeder = new AtlasAgentEvalSeeder(dataSource, documents, rebuilder);
        seeder.resetDatabase();
        seeder.seed(AtlasAgentEvalFixture.load());
    }

    /**
     * 业务目的：真实模型评估必须完整跑完数据集声明的 QA 与知识整理用例并写出机器报告，
     * 全部运行达到完成终态、报告可反序列化；逐条证据输出到 stdout 供人工核验。
     *
     * <p>支持冒烟验证：通过 {@code -Dloredock.agent-eval.qa-cases=N} 与
     * {@code -Dloredock.agent-eval.curation-cases=N} 限制执行前 N 条用例，
     * 先用少量真实案例确认链路可运行，避免在流程未验证前消耗全量模型调用。</p>
     *
     * <p>支持断点续跑：设置 {@code -Dloredock.agent-eval.resume=true} 时读取输出路径上的
     * 上一轮报告，跳过已 COMPLETED 的用例，只重跑未完成/缺失的用例并合并写回；
     * 续跑模式忽略用例数量限制。</p>
     */
    @Test
    void fullAtlasAgentEvaluationCompletesAndWritesReport() throws Exception {
        long startedNanos = System.nanoTime();
        EvalData data = AtlasAgentEvalFixture.load();
        int qaLimit = Integer.getInteger("loredock.agent-eval.qa-cases", data.qaCases().size());
        int curationLimit = Integer.getInteger("loredock.agent-eval.curation-cases", data.curationCases().size());
        boolean resume = Boolean.getBoolean("loredock.agent-eval.resume");
        Path output = AgentEvalReport.defaultOutputPath();
        AgentEvalReport.Report previous = resume && Files.isRegularFile(output)
                ? objectMapper.readValue(output.toFile(), AgentEvalReport.Report.class) : null;
        Set<String> pendingQa = resume && previous != null
                ? AtlasEvalResume.pendingQaCaseIds(data, previous)
                : data.qaCases().stream().map(AtlasAgentEvalFixture.QaCase::caseId).collect(Collectors.toSet());
        Set<String> pendingCuration = resume && previous != null
                ? AtlasEvalResume.pendingCurationCaseIds(data, previous)
                : data.curationCases().stream().map(AtlasAgentEvalFixture.CurationCase::caseId)
                        .collect(Collectors.toSet());

        AtlasQaEvalRunner qaRunner = new AtlasQaEvalRunner(questions, retrievals);
        AtlasCurationEvalRunner curationRunner = new AtlasCurationEvalRunner(tasks, drafts);

        List<QaActual> qaActuals = new ArrayList<>();
        int executedQa = 0;
        for (AtlasAgentEvalFixture.QaCase qaCase : data.qaCases()) {
            if (!pendingQa.contains(qaCase.caseId())) {
                // 续跑：上一轮已完成，复用实际结果，不再消耗模型调用。
                qaActuals.add(AtlasEvalResume.previousQaActual(previous, qaCase.caseId()));
                continue;
            }
            if (!resume && executedQa >= qaLimit) {
                break;
            }
            executedQa++;
            qaActuals.add(qaRunner.runCase(qaCase, PER_CASE_TIMEOUT));
        }
        List<CurationActual> curationActuals = new ArrayList<>();
        int executedCuration = 0;
        for (AtlasAgentEvalFixture.CurationCase curationCase : data.curationCases()) {
            if (!pendingCuration.contains(curationCase.caseId())) {
                curationActuals.add(AtlasEvalResume.previousCurationActual(previous, curationCase.caseId()));
                continue;
            }
            if (!resume && executedCuration >= curationLimit) {
                break;
            }
            executedCuration++;
            curationActuals.add(curationRunner.runCase(curationCase, PER_CASE_TIMEOUT));
        }

        List<QaVerdict> qaVerdicts = new ArrayList<>();
        for (int index = 0; index < qaActuals.size(); index++) {
            qaVerdicts.add(AtlasEvalMetrics.qaVerdict(qaActuals.get(index), data.qaCases().get(index)));
        }
        List<CurationVerdict> curationVerdicts = new ArrayList<>();
        for (int index = 0; index < curationActuals.size(); index++) {
            curationVerdicts.add(AtlasEvalMetrics.curationVerdict(
                    curationActuals.get(index), data.curationCases().get(index)));
        }

        AgentEvalReport.Report report = AgentEvalReport.build(data, qaActuals, curationActuals,
                qaVerdicts, curationVerdicts, null, environment(), Instant.now().toString());
        AgentEvalReport.Report written = AgentEvalReport.write(report, output);
        AgentEvalReport.printEvidence(report);

        long totalMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        assertThat(written.gates().allPassed())
                .as("实际执行的 QA 与知识整理用例必须全部达到完成终态；失败用例见 stdout 逐条证据")
                .isTrue();
        assertThat(Files.isRegularFile(output)).isTrue();
        String reportJson = Files.readString(output);
        assertThat(reportJson).contains(qaActuals.getLast().caseId()).contains(curationActuals.getLast().caseId());
        System.out.printf("测试证据：场景=真实模型评估完成，数据集=%s，实际QA=%d/%d，实际知识整理=%d/%d，"
                        + "续跑=%s，本次重跑QA=%d，本次重跑知识整理=%d，"
                        + "QA准确率（Top-5出现率）=%.2f%%，召回率（目标找回率）=%.2f%%，Top5命中率=%.2f%%，"
                        + "结果类型匹配率=%.2f%%，"
                        + "知识整理动作正确率=%.2f%%，误写率=%.2f%%，报告=%s，总耗时毫秒=%d%n",
                report.datasetVersion(), qaActuals.size(), data.qaCases().size(),
                curationActuals.size(), data.curationCases().size(), resume, executedQa, executedCuration,
                report.qaMetrics().top5Precision() * 100.0D, report.qaMetrics().top5Recall() * 100.0D,
                report.qaMetrics().top5HitRate() * 100.0D,
                report.qaMetrics().resultTypeMatchRate() * 100.0D,
                report.curationMetrics().actionCorrectRate() * 100.0D,
                report.curationMetrics().unsafeWriteRate() * 100.0D, output, totalMillis);
    }

    private String environment() {
        String postgresVersion = jdbcTemplate.queryForObject("select version()", String.class);
        return String.join(" | ",
                System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
                "Java " + System.getProperty("java.version"),
                "PostgreSQL " + postgresVersion,
                "Testcontainers pgvector/pgvector:0.8.1-pg17",
                "Embedding BAAI/bge-small-zh-v1.5 (ONNX)");
    }

    private static Path modelDirectory() {
        String configured = System.getProperty(MODEL_DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("真实模型评估需要 -D" + MODEL_DIRECTORY_PROPERTY
                    + "=<bge-small-zh-v1.5 模型目录>（包含 model.onnx 与 tokenizer.json）");
        }
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("model.onnx"))
                || !Files.isRegularFile(directory.resolve("tokenizer.json"))) {
            throw new IllegalStateException("真实模型评估模型目录不完整：" + directory);
        }
        return directory;
    }

    private static String requireApiKey() {
        String fromEnv = System.getenv("LOREDOCK_AGENT_MODEL_API_KEY");
        String fromProperty = System.getProperty("loredock.agent-eval.api-key");
        String apiKey = fromProperty != null && !fromProperty.isBlank() ? fromProperty : fromEnv;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("真实模型评估需要设置环境变量 LOREDOCK_AGENT_MODEL_API_KEY"
                    + " 或系统属性 loredock.agent-eval.api-key");
        }
        return apiKey;
    }
}
