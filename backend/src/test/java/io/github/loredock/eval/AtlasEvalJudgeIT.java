package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AgentEvalReport.QaCaseResult;
import io.github.loredock.eval.AgentEvalReport.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * LLM Judge 离线评判 IT：读取已生成的评估报告，用真实 ChatModel 逐条评判并回填，
 * 不重跑 Agent、不需要数据库。
 *
 * <p>仅在显式设置 {@code -Dloredock.agent-eval.judge=true} 时运行；报告来源
 * {@code loredock.agent-eval.output}（默认 target/atlas-agent-eval-report.json），
 * 评判报告输出 {@code loredock.agent-eval.judge-output}（默认
 * target/atlas-agent-eval-report-judged.json），ChatModel 密钥与环境变量
 * {@code LOREDOCK_AGENT_MODEL_API_KEY} 一致。</p>
 *
 * <p>裁判模型独立于 Agent 模型：环境变量 {@code LOREDOCK_EVAL_JUDGE_MODEL}（或系统属性
 * {@code loredock.agent-eval.judge-model}）指定，默认与 Agent 相同；建议使用与 Agent
 * 不同源的更强模型（如 qwen3.7-plus）避免同模型自评偏差。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.model.chat=openai"})
@EnabledIfSystemProperty(named = "loredock.agent-eval.judge", matches = "true")
class AtlasEvalJudgeIT {

    @Autowired private ChatModel chatModel;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", AtlasEvalJudgeIT::requireApiKey);
        registry.add("spring.ai.openai.base-url", () -> System.getenv().getOrDefault(
                "LOREDOCK_AGENT_MODEL_BASE_URL", "https://api.deepseek.com"));
        registry.add("spring.ai.openai.chat.options.model", AtlasEvalJudgeIT::judgeModel);
    }

    /** @return 裁判模型：系统属性 loredock.agent-eval.judge-model > 环境变量 LOREDOCK_EVAL_JUDGE_MODEL > Agent 模型 */
    static String judgeModel() {
        String property = System.getProperty("loredock.agent-eval.judge-model");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("LOREDOCK_EVAL_JUDGE_MODEL");
        return env != null && !env.isBlank() ? env
                : System.getenv().getOrDefault("LOREDOCK_AGENT_MODEL_NAME", "deepseek-v4-flash");
    }

    /**
     * 业务目的：离线评判必须对报告全部用例回填 Judge 判定并重算指标，
     * 使忠实度/相关性/问题识别 F1 可进入汇报；评判失败或输出越界必须显式暴露。
     */
    @Test
    void judgesSavedReportAndWritesJudgedReport() throws Exception {
        Path reportPath = Path.of(System.getProperty(
                "loredock.agent-eval.output", "target/atlas-agent-eval-report.json")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(reportPath)) {
            throw new IllegalStateException("找不到评估报告：" + reportPath
                    + "，请先运行 AtlasAgentEvalRealModelIT 生成报告后再评判");
        }
        Report report = objectMapper.readValue(reportPath.toFile(), Report.class);
        EvalData data = AtlasAgentEvalFixture.load();
        AtlasEvalJudge judge = new AtlasEvalJudge(chatModel, objectMapper);
        Report judged = AtlasEvalJudgeRunner.judge(report, data, judge);

        Path output = Path.of(System.getProperty(
                "loredock.agent-eval.judge-output", "target/atlas-agent-eval-report-judged.json"))
                .toAbsolutePath().normalize();
        AgentEvalReport.Report written = AgentEvalReport.write(judged, output);
        AtlasEvalJudgeRunner.printJudgeEvidence(judged);

        for (QaCaseResult result : written.qaResults()) {
            assertThat(result.verdict().faithfulness())
                    .as(result.caseId() + " 忠实度").isNotNull().isBetween(0, 100);
            assertThat(result.verdict().relevance())
                    .as(result.caseId() + " 相关性").isNotNull().isBetween(0, 100);
            assertThat(result.verdict().reason()).as(result.caseId() + " 评判理由").isNotBlank();
        }
        assertThat(written.curationResults())
                .allSatisfy(result -> assertThat(result.verdict().issueCorrect())
                        .as(result.caseId() + " 问题识别判定").isNotNull());
        assertThat(written.qaMetrics().averageFaithfulness()).isNotNull();
        assertThat(written.qaMetrics().averageRelevance()).isNotNull();
        assertThat(written.curationMetrics().issueTypeF1()).isNotEmpty();
        assertThat(Files.isRegularFile(output)).isTrue();
        assertThat(Files.readString(output)).contains("faithfulness").contains("issueCorrect");
        System.out.printf("测试证据：场景=离线评判完成，裁判模型=%s，报告=%s，评判报告=%s，QA=%d，知识整理=%d，"
                        + "平均忠实度=%s，平均相关性=%s，问题类型F1=%s%n",
                judgeModel(), reportPath, output, written.qaResults().size(), written.curationResults().size(),
                written.qaMetrics().averageFaithfulness(), written.qaMetrics().averageRelevance(),
                written.curationMetrics().issueTypeF1());
    }

    private static String requireApiKey() {
        String fromEnv = System.getenv("LOREDOCK_AGENT_MODEL_API_KEY");
        String fromProperty = System.getProperty("loredock.agent-eval.api-key");
        String apiKey = fromProperty != null && !fromProperty.isBlank() ? fromProperty : fromEnv;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("离线评判需要设置环境变量 LOREDOCK_AGENT_MODEL_API_KEY"
                    + " 或系统属性 loredock.agent-eval.api-key");
        }
        return apiKey;
    }
}
