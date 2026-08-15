package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.service.impl.ProjectQaAgentExecutor;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasEvalMetrics.CurationVerdict;
import io.github.loredock.eval.AtlasEvalMetrics.QaVerdict;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.knowledge.model.request.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.service.search.KnowledgeEmbeddingService;
import io.github.loredock.qa.api.QaQuestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

/**
 * 确定性评估框架 IT：真实 PostgreSQL + 可控脚本 ChatModel，验证评估链路
 * 种子数据 → 运行 QA/知识整理用例 → 收集检索原文与工作区 → 计算指标 → 写出报告 全部可执行，
 * 不产生任何外部模型费用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(AtlasAgentEvalDeterministicIT.EvalScriptedModelConfiguration.class)
class AtlasAgentEvalDeterministicIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_agent_eval_deterministic")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private io.github.loredock.qa.api.QaService questions;
    @Autowired private io.github.loredock.agent.service.AgentRetrievalService retrievals;
    @Autowired private io.github.loredock.agent.api.KnowledgeTaskService tasks;
    @Autowired private io.github.loredock.knowledge.api.KnowledgeDraftService drafts;
    @Autowired private io.github.loredock.knowledge.service.KnowledgeDocumentDataService documents;
    @Autowired private io.github.loredock.knowledge.service.KnowledgeIndexRebuildService rebuilder;
    @Autowired private DataSource dataSource;
    @Autowired private EvalScriptedChatModel scriptedModel;
    @MockitoBean private KnowledgeEmbeddingService embedding;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
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
        registry.add("loredock.agent.enabled", () -> "true");
        // 显式固定 ChatModel 关闭与脚本接管，避免外部环境变量（如 LOREDOCK_AGENT_CHAT_PROVIDER=openai）
        // 激活生产执行器与测试脚本执行器产生 Bean 冲突。
        registry.add("spring.ai.model.chat", () -> "none");
        // 脚本模型接管 ChatModel，端点只需满足“已配置”检查，不会被真实调用。
        registry.add("loredock.agent.model.api-key", () -> "eval-test-key");
        registry.add("loredock.agent.model.base-url", () -> "http://127.0.0.1:9");
    }

    @BeforeEach
    void prepareEvalEnvironment() {
        reset(embedding);
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "c".repeat(64), 512));
        when(embedding.embedQuery(any())).thenReturn(new KnowledgeEmbeddingVector(vector()));
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<KnowledgeEmbeddingInput> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> new KnowledgeEmbeddingVector(vector())).toList();
        });
        scriptedModel.resetScript();
        AtlasAgentEvalSeeder seeder = new AtlasAgentEvalSeeder(dataSource, documents, rebuilder);
        seeder.resetDatabase();
        seeder.seed(AtlasAgentEvalFixture.load());
    }

    /**
     * 业务目的：评估框架必须能在真实 PostgreSQL 上完成 种子→运行→采集→指标→报告 全链路，
     * 防止真实模型评估 IT 因框架自身缺陷而失败；同时验证回答、拒答与知识整理三条运行路径。
     */
    @Test
    void evalFrameworkCollectsActualsComputesMetricsAndWritesReport() throws Exception {
        EvalData data = AtlasAgentEvalFixture.load();
        Duration timeout = Duration.ofSeconds(60);
        AtlasQaEvalRunner qaRunner = new AtlasQaEvalRunner(questions, retrievals);
        AtlasCurationEvalRunner curationRunner = new AtlasCurationEvalRunner(tasks, drafts);

        List<QaCase> qaSubset = data.qaCases().stream()
                .filter(qaCase -> Set.of("QA-001", "QA-002", "QA-035").contains(qaCase.caseId()))
                .toList();
        List<QaActual> qaActuals = qaSubset.stream().map(qaCase -> qaRunner.runCase(qaCase, timeout)).toList();
        CurationCase curationCase = data.curationCases().stream()
                .filter(candidate -> candidate.caseId().equals("CUR-001")).findFirst().orElseThrow();
        CurationActual curationActual = curationRunner.runCase(curationCase, timeout);

        QaActual answer = qaActuals.stream().filter(actual -> actual.caseId().equals("QA-001"))
                .findFirst().orElseThrow();
        QaActual refusal = qaActuals.stream().filter(actual -> actual.caseId().equals("QA-035"))
                .findFirst().orElseThrow();
        assertThat(answer.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(answer.resultType()).isEqualTo("ANSWER");
        assertThat(answer.citationDocumentIds()).isNotEmpty();
        assertThat(answer.retrievals()).isNotEmpty();
        assertThat(answer.top5DocumentIds()).hasSizeLessThanOrEqualTo(5);
        assertThat(refusal.status()).isEqualTo(QaQuestion.Status.COMPLETED);
        assertThat(refusal.resultType()).isEqualTo("REFUSAL");
        assertThat(refusal.refusalReason()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(refusal.citationDocumentIds()).isEmpty();
        assertThat(curationActual.status()).isEqualTo(io.github.loredock.agent.api.KnowledgeTaskService.RunStatus.COMPLETED);
        assertThat(curationActual.finalResponse()).isNotBlank();
        assertThat(curationActual.workspace()).isEmpty();

        List<QaVerdict> qaVerdicts = qaActuals.stream()
                .map(actual -> AtlasEvalMetrics.qaVerdict(actual, qaSubset.stream()
                        .filter(qaCase -> qaCase.caseId().equals(actual.caseId())).findFirst().orElseThrow()))
                .toList();
        CurationVerdict curationVerdict = AtlasEvalMetrics.curationVerdict(curationActual, curationCase);
        assertThat(qaVerdicts.stream().filter(QaVerdict::resultTypeMatch)).hasSize(3);
        assertThat(curationVerdict.workspaceMatch()).isTrue();
        assertThat(curationVerdict.actionCorrect()).isTrue();

        Path reportPath = Path.of("target", "atlas-agent-eval-report-deterministic.json").toAbsolutePath().normalize();
        AgentEvalReport.Report report = AgentEvalReport.build(data, qaActuals, List.of(curationActual),
                qaVerdicts, List.of(curationVerdict), null, "deterministic-scripted-model",
                Instant.now().toString());
        AgentEvalReport.Report written = AgentEvalReport.write(report, reportPath);

        assertThat(written.gates().allPassed()).isTrue();
        assertThat(Files.isRegularFile(reportPath)).isTrue();
        String json = Files.readString(reportPath);
        assertThat(json).contains("QA-001").contains("QA-035").contains("CUR-001");
        System.out.printf("测试证据：场景=评估框架确定性链路，QA=%d，知识整理=%d，报告=%s，"
                        + "门禁=%s，Top5候选样本=%s%n",
                qaActuals.size(), 1, reportPath, written.gates(), answer.top5DocumentIds());
    }

    private float[] vector() {
        float[] vector = new float[512];
        java.util.Arrays.fill(vector, 0.01F);
        return vector;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalScriptedModelConfiguration {

        @Bean
        @Primary
        EvalScriptedChatModel evalScriptedChatModel() {
            return new EvalScriptedChatModel();
        }

        // 生产装配对 ChatModel 的条件在测试配置注册前求值，直接声明具体执行器以驱动完整框架链路。
        @Bean
        @Primary
        ProjectQaAgentExecutor projectQaAgentExecutor(
                ChatModel chatModel,
                io.github.loredock.agent.service.ProjectQaToolService tools,
                ObjectMapper objectMapper
        ) {
            return new ProjectQaAgentExecutor(() -> chatModel, tools, objectMapper);
        }
    }

    /**
     * 确定性脚本模型：先按提示内容识别场景——包含工具响应时用真实证据 ID 给出带引用回答，
     * 知识整理目标返回纯文本最终回复，证据不足问题返回明确拒答，其余情况发起 knowledge_search。
     */
    static final class EvalScriptedChatModel implements ChatModel {

        private static final Pattern EVIDENCE_ID = Pattern.compile("evidenceId=(\\d+)");
        private static final String CURATION_GOAL_SIGNAL = "整理候选材料";
        private static final String REFUSAL_QUESTION_SIGNAL = "翻译成西班牙语";

        @Override
        public ChatResponse call(Prompt prompt) {
            return next(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(next(prompt));
        }

        private ChatResponse next(Prompt prompt) {
            Long evidenceId = firstEvidenceId(prompt);
            if (evidenceId != null) {
                return answer(evidenceId);
            }
            String text = allText(prompt);
            if (text.contains(CURATION_GOAL_SIGNAL)) {
                return curationFinalResponse();
            }
            if (text.contains(REFUSAL_QUESTION_SIGNAL)) {
                return refusal();
            }
            return knowledgeSearchCall();
        }

        private Long firstEvidenceId(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                        String data = response.responseData();
                        if (data != null) {
                            Matcher matcher = EVIDENCE_ID.matcher(data);
                            if (matcher.find()) {
                                return Long.valueOf(matcher.group(1));
                            }
                        }
                    }
                }
                String messageText = message.getText();
                if (messageText != null) {
                    Matcher matcher = EVIDENCE_ID.matcher(messageText);
                    if (matcher.find()) {
                        return Long.valueOf(matcher.group(1));
                    }
                }
            }
            return null;
        }

        private static String allText(Prompt prompt) {
            StringBuilder builder = new StringBuilder();
            for (Message message : prompt.getInstructions()) {
                if (message.getText() != null) {
                    builder.append(message.getText()).append('\n');
                }
            }
            return builder.toString();
        }

        private ChatResponse answer(Long evidenceId) {
            String json = """
                    {"resultType":"ANSWER","answerBasis":"BUSINESS_RULE",
                     "text":"评估回答：该结论基于本轮检索到的已发布知识。",
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

        private ChatResponse curationFinalResponse() {
            return response(new AssistantMessage(
                    "本次候选材料与已有的《人工审核与正式发布规则》内容重复，"
                            + "没有形成需要单独发布的新知识，因此本轮不创建重复工作文档。"));
        }

        private ChatResponse knowledgeSearchCall() {
            AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("eval-tool-1", "function", "knowledge_search",
                            "{\"query\":\"候选内容与正式知识\",\"limit\":5}")))
                    .build();
            return response(message);
        }

        private static ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
        }

        void resetScript() {
            // 场景识别完全由提示内容决定，无需维护调用序列状态。
        }
    }
}
