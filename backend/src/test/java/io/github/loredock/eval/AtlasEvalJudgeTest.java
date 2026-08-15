package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.CurationInput;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaExpected;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaInput;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasEvalJudge.CurationJudgement;
import io.github.loredock.eval.AtlasEvalJudge.QaJudgement;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.eval.AgentEvalReport.QaCaseResult;
import io.github.loredock.eval.AgentEvalReport.Report;
import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import reactor.core.publisher.Flux;

/**
 * LLM Judge 引擎与离线评判运行器的单元测试：提示词内容、JSON 输出解析、
 * 分数归一与报告回填重算，全部使用桩 ChatModel，不产生模型调用费用。
 */
class AtlasEvalJudgeTest {

    private static final QaInput QA_INPUT = new QaInput("atlas", "main", "Atlas 里的候选内容和正式知识有什么区别？");
    private static final CurationInput CURATION_INPUT = new CurationInput("atlas", 720001L, "整理候选材料");

    /**
     * 业务目的：QA Judge 提示词必须包含问题、预期参考回答、实际回答与检索原文，
     * 防止裁判在缺少原文时用自身常识补事实；输出解析后分数按 0-100 归一。
     */
    @Test
    void qaJudgeBuildsPromptAndParsesClampedScores() {
        RecordingModel model = new RecordingModel(
                "```json\n{\"faithfulness\":120,\"relevance\":94,\"reason\":\"回答有检索原文支持\"}\n```");
        AtlasEvalJudge judge = new AtlasEvalJudge(model, new com.fasterxml.jackson.databind.ObjectMapper());

        QaJudgement judgement = judge.judgeQa(new AtlasEvalJudge.QaJudgeInput(
                "QA-001", QA_INPUT.question(), "预期参考回答", "实际回答",
                "检索查询：审核规则\n- 人工审核与正式发布规则：候选内容发布前不进入普通检索", List.of(710007L)));

        String userText = model.lastUserText();
        assertThat(userText).contains("用户问题：").contains(QA_INPUT.question())
                .contains("预期参考回答：预期参考回答").contains("实际回答：实际回答")
                .contains("人工审核与正式发布规则：候选内容发布前不进入普通检索")
                .contains("[710007]");
        assertThat(judgement.faithfulness()).isEqualTo(100);
        assertThat(judgement.relevance()).isEqualTo(94);
        assertThat(judgement.reason()).contains("检索原文支持");
        System.out.printf("测试证据：场景=QA裁判提示与解析，用例=QA-001，忠实度=%d，相关性=%d%n",
                judgement.faithfulness(), judgement.relevance());
    }

    /**
     * 业务目的：知识整理 Judge 提示词必须包含候选草稿全文、相关正式文档、预期与
     * 实际最终回复、工作草稿正文和禁止事实，确保问题识别只看最终回复。
     */
    @Test
    void curationJudgeBuildsPromptWithDraftWorkspaceAndForbiddenFacts() {
        RecordingModel model = new RecordingModel(
                "{\"issueType\":\"CONFLICT\",\"action\":\"ASK_USER\",\"issueCorrect\":true,"
                        + "\"actionCorrect\":true,\"unsafeWrite\":false,\"reason\":\"最终回复请求人工确认\"}");
        AtlasEvalJudge judge = new AtlasEvalJudge(model, new com.fasterxml.jackson.databind.ObjectMapper());

        CurationJudgement judgement = judge.judgeCuration(new AtlasEvalJudge.CurationJudgeInput(
                "CUR-003", "候选草稿正文：自动重试 5 次", "正式文档正文：最多重试 2 次",
                "CONFLICT", "ASK_USER", "请管理员确认正确次数", "请管理员确认正确次数",
                "（未产生工作草稿）", List.of("自动重试次数为 5 次")));

        String userText = model.lastUserText();
        assertThat(userText).contains("候选草稿正文：自动重试 5 次").contains("正式文档正文：最多重试 2 次")
                .contains("预期问题类型：CONFLICT").contains("Agent 实际最终回复：请管理员确认正确次数")
                .contains("自动重试次数为 5 次");
        assertThat(judgement.issueCorrect()).isTrue();
        assertThat(judgement.unsafeWrite()).isFalse();
        System.out.printf("测试证据：场景=知识整理裁判提示与解析，用例=CUR-003，判定=%s，理由=%s%n",
                judgement.issueType(), judgement.reason());
    }

    /**
     * 业务目的：Judge 输出不是合法 JSON 时必须显式失败并保留原始输出，
     * 防止静默吞掉异常导致裁判结果与报告不一致。
     */
    @Test
    void judgeFailsLoudlyOnMalformedOutput() {
        AtlasEvalJudge judge = new AtlasEvalJudge(
                new RecordingModel("这不是 JSON"), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> judge.judgeQa(new AtlasEvalJudge.QaJudgeInput(
                "QA-001", "问题", "预期", "实际", "（无检索）", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("这不是 JSON");
        System.out.println("测试证据：场景=裁判异常输出，非法 JSON 被显式拒绝");
    }

    /**
     * 业务目的：离线评判必须回填 QA 分数与知识整理判定并重算汇总指标，
     * 且不修改原始报告的客观字段。
     */
    @Test
    void judgeRunnerRebuildsReportWithJudgeResults() {
        EvalData data = AtlasAgentEvalFixture.load();
        QaCase qaCase = new QaCase("QA-001", "SINGLE_DOCUMENT", QA_INPUT,
                new QaExpected("ANSWER", null, "参考回答", List.of(710001L)));
        QaActual qaActual = new QaActual("QA-001", "SINGLE_DOCUMENT", QaQuestion.Status.COMPLETED, null,
                "ANSWER", null, "实际回答", List.of(710001L),
                List.of(new AtlasQaEvalRunner.RetrievalActual("候选内容", List.of(
                        new AtlasQaEvalRunner.RetrievedActual(710001L, "Atlas 产品概览与核心术语", 0.9, true, false,
                                "候选内容发布前不进入普通检索")))),
                List.of(710001L), 1000L);
        CurationCase curationCase = new CurationCase("CUR-001", CURATION_INPUT, new CurationExpected(
                "DUPLICATE", List.of(710007L), "NO_CHANGE", "参考最终回复", null, List.of()));
        CurationActual curationActual = new CurationActual("CUR-001", KnowledgeTaskService.RunStatus.COMPLETED,
                null, "本次候选材料与正式规则重复，不创建重复工作文档", List.of(), 1000L);
        Report report = AgentEvalReport.build(data, List.of(qaActual), List.of(curationActual),
                List.of(AtlasEvalMetrics.qaVerdict(qaActual, qaCase)),
                List.of(AtlasEvalMetrics.curationVerdict(curationActual, curationCase)),
                null, "unit-test", Instant.now().toString());
        assertThat(report.qaResults().getFirst().verdict().faithfulness()).isNull();

        RecordingModel model = new RecordingModel("""
                {"issueType":"DUPLICATE","action":"NO_CHANGE","issueCorrect":true,"actionCorrect":true,\
                "unsafeWrite":false,"reason":"最终回复识别重复且未写入"}\
                """);
        model.scriptQaResponse("{\"faithfulness\":90,\"relevance\":85,\"reason\":\"回答有检索支持\"}");
        AtlasEvalJudge judge = new AtlasEvalJudge(model, new com.fasterxml.jackson.databind.ObjectMapper());

        Report judged = AtlasEvalJudgeRunner.judge(report, data, judge);

        QaCaseResult qaResult = judged.qaResults().getFirst();
        assertThat(qaResult.verdict().faithfulness()).isEqualTo(90);
        assertThat(qaResult.verdict().relevance()).isEqualTo(85);
        assertThat(qaResult.verdict().hitTop5()).isTrue();
        assertThat(judged.qaMetrics().averageFaithfulness()).isEqualTo(90.0D);
        assertThat(judged.qaMetrics().averageRelevance()).isEqualTo(85.0D);
        assertThat(judged.curationResults().getFirst().verdict().issueCorrect()).isTrue();
        assertThat(judged.curationResults().getFirst().verdict().actionCorrect()).isTrue();
        assertThat(judged.curationMetrics().issueTypeF1()).containsKey("DUPLICATE");
        assertThat(judged.curationMetrics().issueTypeF1().get("DUPLICATE").f1()).isEqualTo(1.0D);
        assertThat(report.qaResults().getFirst().verdict().faithfulness())
                .as("原始报告不得被修改").isNull();
        System.out.printf("测试证据：场景=离线评判回填，平均忠实度=%.1f，平均相关性=%.1f，DUPLICATE F1=%.2f%n",
                judged.qaMetrics().averageFaithfulness(), judged.qaMetrics().averageRelevance(),
                judged.curationMetrics().issueTypeF1().get("DUPLICATE").f1());
    }

    /** 记录最近一次用户消息并返回预置响应的桩 ChatModel。 */
    private static final class RecordingModel implements ChatModel {

        private final java.util.concurrent.atomic.AtomicReference<String> lastUser = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<String> qaResponse = new java.util.concurrent.atomic.AtomicReference<>();
        private volatile String curationResponse;

        RecordingModel(String curationResponse) {
            this.curationResponse = curationResponse;
        }

        void scriptQaResponse(String response) {
            qaResponse.set(response);
        }

        String lastUserText() {
            return lastUser.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            StringBuilder user = new StringBuilder();
            for (Message message : prompt.getInstructions()) {
                if (message instanceof SystemMessage system) {
                    user.append("[系统]").append(system.getText()).append('\n');
                } else if (message instanceof UserMessage userMessage) {
                    user.append("[用户]").append(userMessage.getText()).append('\n');
                }
            }
            lastUser.set(user.toString());
            String response = user.toString().contains("忠实度") && qaResponse.get() != null
                    ? qaResponse.get() : curationResponse;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
