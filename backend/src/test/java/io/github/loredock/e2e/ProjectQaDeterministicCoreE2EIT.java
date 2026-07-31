package io.github.loredock.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.service.AgentRuntime;
import io.github.loredock.agent.service.ProjectQaToolService;
import io.github.loredock.agent.service.impl.SpringAiAlibabaAgentRuntime;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

/**
 * 确定性核心 E2E：真实 PostgreSQL + 可控脚本 ChatModel，走通项目/分支 → 发布知识 → 真实建索引 →
 * 创建问答 → 真实 knowledge_search 工具取证据 → 带引用的回答或明确拒答 → 问答详情与助手消息一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(ProjectQaDeterministicCoreE2EIT.ScriptedModelConfiguration.class)
class ProjectQaDeterministicCoreE2EIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Long PROJECT_ID = 6649233113080659970L;
    private static final Long BRANCH_ID = 6649233113080659971L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_core_e2e_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private QaService questions;
    @Autowired private KnowledgeDocumentDataService documents;
    @Autowired private KnowledgeIndexRebuildService rebuilder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ScriptedChatModel scriptedModel;
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
        registry.add("loredock.agent.enabled", () -> "true");
        // 脚本模型接管 ChatModel，端点只需满足“已配置”检查，不会被真实调用。
        registry.add("loredock.agent.model.api-key", () -> "e2e-test-key");
        registry.add("loredock.agent.model.base-url", () -> "http://127.0.0.1:9");
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
        scriptedModel.resetScript();
        seedScopeAndSkill();
    }

    /**
     * 业务目的：发布知识并真实建索引后，脚本模型通过真实 knowledge_search 工具取得带真实 ID 的证据，
     * 最终回答必须携带引用，问答详情、助手消息与引用面板一致。
     */
    @Test
    void answerWithCitationAfterRealIndexAndKnowledgeTool() throws Exception {
        long startedNanos = System.nanoTime();
        KnowledgeDocument published = publishDocument(
                "范围隔离规则", "范围隔离用于防止跨项目召回，不允许用模型常识补齐内部事实。");
        Long jobId = insertReindexJob();
        rebuilder.rebuild(jobId, noOpProgress());
        scriptedModel.scriptToolCall("knowledge_search", "{\"query\":\"范围隔离\",\"limit\":3}");

        QaQuestion created = questions.create(new QaService.CreateRequest(
                "member", "MEMBER", "e2e-answer-key", "atlas", "main", "范围隔离规则是什么？"));
        QaQuestion detail = awaitTerminal(created.questionId());

        assertThat(detail.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(detail.resultType()).isEqualTo(QaQuestion.ResultType.ANSWER);
        assertThat(detail.citations()).hasSize(1);
        assertThat(detail.citations().get(0).documentId()).isEqualTo(published.id());
        assertThat(detail.citations().get(0).projectIdentifier()).isEqualTo("atlas");
        assertThat(detail.messages()).extracting(message -> message.role())
                .containsExactly(QaQuestion.MessageRole.USER, QaQuestion.MessageRole.ASSISTANT);
        assertThat(detail.messages().get(1).content()).contains("范围隔离规则");
        assertThat(detail.messages().get(1).resultType())
                .isEqualTo(QaQuestion.ResultType.ANSWER);
        assertThat(scriptedModel.calls()).isGreaterThanOrEqualTo(2);
        System.out.printf("测试证据：场景=确定性核心E2E带引用回答，questionId=%d，runId=%d，终态=%s，"
                        + "结果=%s，引用数=%d，证据ID=%d，文档ID=%d，消息数=%d，模型调用=%d，耗时毫秒=%d%n",
                detail.questionId(), detail.runId(), detail.status(), detail.resultType(),
                detail.citations().size(), detail.citations().get(0).evidenceId(),
                detail.citations().get(0).documentId(), detail.messages().size(), scriptedModel.calls(),
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    /**
     * 业务目的：没有任何已索引知识时，运行必须在真实入口下以明确拒答收敛，不得用模型失败或超时替代拒答。
     */
    @Test
    void refusalWhenNoIndexedKnowledgeExists() throws Exception {
        long startedNanos = System.nanoTime();
        QaQuestion created = questions.create(new QaService.CreateRequest(
                "member", "MEMBER", "e2e-refusal-key", "atlas", "main", "这个项目有部署指南吗？"));
        QaQuestion detail = awaitTerminal(created.questionId());

        assertThat(detail.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(detail.resultType()).isEqualTo(QaQuestion.ResultType.REFUSAL);
        assertThat(detail.refusalReason()).isEqualTo(QaQuestion.RefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(detail.citations()).isEmpty();
        assertThat(detail.messages().get(1).role()).isEqualTo(QaQuestion.MessageRole.ASSISTANT);
        assertThat(detail.messages().get(1).content()).isEqualTo("当前知识库没有足够依据");
        System.out.printf("测试证据：场景=确定性核心E2E明确拒答，questionId=%d，runId=%d，终态=%s，"
                        + "结果=%s，原因=%s，引用数=%d，消息数=%d，模型调用=%d，耗时毫秒=%d%n",
                detail.questionId(), detail.runId(), detail.status(), detail.resultType(),
                detail.refusalReason(), detail.citations().size(), detail.messages().size(),
                scriptedModel.calls(), Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private QaQuestion awaitTerminal(Long questionId) throws Exception {
        QaQuestion snapshot = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            snapshot = questions.detail(new QaService.DetailQuery("member", "atlas", questionId));
            var status = snapshot.status();
            if (status == QaQuestion.Status.COMPLETED
                    || status == QaQuestion.Status.FAILED
                    || status == QaQuestion.Status.TERMINATED) {
                return snapshot;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("项目问答未在超时内达到终态：" + snapshot.status());
    }

    private KnowledgeDocument publishDocument(String title, String body) {
        KnowledgeDocument draft = KnowledgeDocument.create(TestIds.next(), new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody(body),
                new DocumentDirectory(""), DocumentTags.of(List.of("范围")),
                new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated"),
                KnowledgeScope.project(PROJECT_ID)),
                new DocumentAudit(NOW, "publisher"));
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

    private KnowledgeIndexRebuildService.Progress noOpProgress() {
        return new KnowledgeIndexRebuildService.Progress(value -> { }, () -> { });
    }

    private void seedScopeAndSkill() {
        jdbcTemplate.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('skill-one', 'AVAILABLE', 'skill.md', 'text/markdown', 10, ?, now(), now(), 'test', 'test')
                """, "a".repeat(64));
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
        java.util.Arrays.fill(vector, 0.01F);
        return vector;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        // 生产装配对 ChatModel 的 @ConditionalOnBean 在测试配置注册前求值，
        // 这里直接声明真实 AgentRuntime，保证调度线程拿到脚本模型驱动完整工具链路。
        @Bean
        @Primary
        AgentRuntime projectQaAgentRuntime(
                ChatModel chatModel,
                ProjectQaToolService tools,
                ObjectMapper objectMapper
        ) {
            return new SpringAiAlibabaAgentRuntime(() -> chatModel, tools, objectMapper);
        }
    }

    /**
     * 确定性脚本模型：先按预置顺序返回工具调用，工具执行后从真实证据块提取证据 ID，
     * 给出带引用的回答；没有任何证据时返回明确拒答。
     */
    static final class ScriptedChatModel implements ChatModel {

        private final List<ChatResponse> toolResponses = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        void resetScript() {
            toolResponses.clear();
            calls.set(0);
        }

        void scriptToolCall(String name, String arguments) {
            AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(Long.toString(calls.get() + 1), "function", name, arguments)))
                    .build();
            toolResponses.add(response(message));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return next(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(next(prompt));
        }

        private ChatResponse next(Prompt prompt) {
            int index = calls.getAndIncrement();
            if (index < toolResponses.size()) {
                return toolResponses.get(index);
            }
            Long evidenceId = firstEvidenceId(prompt);
            if (evidenceId != null) {
                return answer(evidenceId);
            }
            return refusal();
        }

        private Long firstEvidenceId(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                        String data = response.responseData();
                        if (data != null) {
                            Matcher matcher = Pattern.compile("evidenceId=(\\d+)").matcher(data);
                            if (matcher.find()) {
                                return Long.valueOf(matcher.group(1));
                            }
                        }
                    }
                }
                String text = message.getText();
                if (text != null) {
                    Matcher matcher = Pattern.compile("evidenceId=(\\d+)").matcher(text);
                    if (matcher.find()) {
                        return Long.valueOf(matcher.group(1));
                    }
                }
            }
            return null;
        }

        private ChatResponse answer(Long evidenceId) {
            String json = """
                    {"resultType":"ANSWER","answerBasis":"BUSINESS_RULE",
                     "text":"范围隔离规则：防止跨项目召回，回答基于已发布知识。",
                     "citations":["%d"],"refusalReason":null,"sourceConflict":false}
                    """.formatted(evidenceId);
            return response(new AssistantMessage(json));
        }

        private ChatResponse refusal() {
            return response(new AssistantMessage("""
                    {"resultType":"REFUSAL","answerBasis":"BUSINESS_RULE","text":"当前知识库没有足够依据",
                     "citations":[],"refusalReason":"INSUFFICIENT_EVIDENCE","sourceConflict":false}
                    """));
        }

        private static ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
        }
    }
}
