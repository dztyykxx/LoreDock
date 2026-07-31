package io.github.loredock.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.support.TestIds;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

/**
 * 手工真实模型 Smoke：使用真实 PostgreSQL、生产 ChatModel 和真实 Agent 工具链，验证带引用回答与明确拒答。
 * 仅在显式设置 {@code -Dloredock.real-model-smoke=true} 时运行，避免日常测试产生外部调用费用。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.model.chat=openai", "loredock.agent.enabled=true"})
@Testcontainers
@EnabledIfSystemProperty(named = "loredock.real-model-smoke", matches = "true")
class ProjectQaRealModelSmokeIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Long PROJECT_ID = 6649233113080660970L;
    private static final Long BRANCH_ID = 6649233113080660971L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_real_model_smoke")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private QaService questions;
    @Autowired private KnowledgeDocumentDataService documents;
    @Autowired private KnowledgeIndexRebuildService rebuilder;
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
    void resetFacts() {
        for (String table : List.of(
                "web_qa_message", "web_qa_question", "agent_evidence", "agent_run_event", "agent_run",
                "knowledge_search_chunk", "knowledge_index_generation", "knowledge_document", "background_job",
                "project_branch", "project_space", "stored_object")) {
            jdbcTemplate.update("delete from " + table);
        }
        reset(embedding);
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "c".repeat(64), 512));
        when(embedding.embedQuery(any())).thenReturn(new KnowledgeEmbeddingVector(vector()));
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<KnowledgeEmbeddingInput> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> new KnowledgeEmbeddingVector(vector())).toList();
        });
        seedScope();
    }

    /**
     * 业务目的：真实模型必须通过真实检索工具形成带来源回答，并在当前范围没有证据时形成
     * {@code COMPLETED/REFUSAL}，防止只验证模型直连而遗漏 Agent、工具、终态和持久化链路。
     */
    @Test
    void realModelCompletesCitedAnswerAndInsufficientEvidenceRefusal() throws Exception {
        long startedNanos = System.nanoTime();
        KnowledgeDocument published = publishDocument(
                "Atlas 范围隔离规则",
                "Atlas 项目的范围隔离用于防止跨项目召回；问答只能引用当前项目与分支内的已发布知识。"
        );
        rebuilder.rebuild(insertReindexJob(), new KnowledgeIndexRebuildService.Progress(value -> { }, () -> { }));

        QaQuestion answer = awaitTerminal(questions.create(new QaService.CreateRequest(
                "member", "MEMBER", "real-model-answer", "atlas", "main",
                "Atlas 项目的范围隔离规则是什么？请先检索知识并只根据返回证据回答。"
        )).questionId());

        assertThat(answer.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(answer.resultType()).isEqualTo(QaQuestion.ResultType.ANSWER);
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).extracting(QaQuestion.Citation::documentId).contains(published.id());
        assertThat(answer.stepCount()).isPositive();
        assertThat(answer.modelCallCount()).isPositive();
        System.out.printf("真实模型Smoke：场景=带引用回答，questionId=%d，runId=%d，终态=%s，结果=%s，"
                        + "步骤=%d，模型调用=%d，引用数=%d，文档ID=%d%n",
                answer.questionId(), answer.runId(), answer.status(), answer.resultType(), answer.stepCount(),
                answer.modelCallCount(), answer.citations().size(), published.id());

        clearSearchableKnowledge();
        QaQuestion refusal = awaitTerminal(questions.create(new QaService.CreateRequest(
                "member", "MEMBER", "real-model-refusal", "atlas", "main",
                "Atlas 项目是否规定了生产发布审批人？请检索知识；没有证据时按规则明确拒答。"
        )).questionId());

        assertThat(refusal.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(refusal.resultType()).isEqualTo(QaQuestion.ResultType.REFUSAL);
        assertThat(refusal.refusalReason()).isEqualTo(QaQuestion.RefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(refusal.resultText()).contains("当前知识库没有足够依据");
        assertThat(refusal.citations()).isEmpty();
        assertThat(refusal.stepCount()).isPositive();
        assertThat(refusal.modelCallCount()).isPositive();
        System.out.printf("真实模型Smoke：场景=证据不足拒答，questionId=%d，runId=%d，终态=%s，结果=%s，"
                        + "原因=%s，步骤=%d，模型调用=%d，引用数=%d，总耗时毫秒=%d%n",
                refusal.questionId(), refusal.runId(), refusal.status(), refusal.resultType(),
                refusal.refusalReason(), refusal.stepCount(), refusal.modelCallCount(), refusal.citations().size(),
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private QaQuestion awaitTerminal(Long questionId) throws Exception {
        QaQuestion snapshot = null;
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            snapshot = questions.detail(new QaService.DetailQuery("member", "atlas", questionId));
            if (snapshot.status() == QaQuestion.Status.COMPLETED
                    || snapshot.status() == QaQuestion.Status.FAILED
                    || snapshot.status() == QaQuestion.Status.TERMINATED) {
                return snapshot;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("真实模型问答未在超时内达到终态：" + snapshot);
    }

    private KnowledgeDocument publishDocument(String title, String body) {
        KnowledgeDocument draft = KnowledgeDocument.create(TestIds.next(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody(body),
                new DocumentDirectory(""), DocumentTags.of(List.of("范围")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"),
                KnowledgeScope.project(PROJECT_ID)), new DocumentAudit(NOW, "publisher"));
        KnowledgeDocument published = draft.publish(new DocumentAudit(NOW.plusSeconds(1), "publisher"));
        documents.insert(published);
        return published;
    }

    private Long insertReindexJob() {
        Long id = TestIds.next();
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'RUNNING', 0, ?, ?, 'test', 'test')
                """, id, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private void clearSearchableKnowledge() {
        // 已完成运行固定引用原 generation；只移除可检索分块即可构造同一 generation 下的空命中，
        // 不破坏运行快照的外键和可追溯性。
        jdbcTemplate.update("delete from knowledge_search_chunk");
    }

    private void seedScope() {
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID);
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID, PROJECT_ID);
    }

    private float[] vector() {
        float[] vector = new float[512];
        Arrays.fill(vector, 0.01F);
        return vector;
    }
}
