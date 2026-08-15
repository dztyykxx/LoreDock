package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasCurationEvalRunner.CurationActual;
import io.github.loredock.eval.AtlasQaEvalRunner.QaActual;
import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 报告组装完整性测试：实际回答与期望参考回答逐字相同必须被拒绝，
 * 防止期望答案泄漏给 Agent 或结果被伪造时报告仍然"通过"。
 */
class AgentEvalReportTest {

    /**
     * 业务目的：QA 实际回答与数据集期望参考回答逐字相同时报告必须拒绝组装，
     * 防止"模型照抄答案"或脚本伪造结果的报告进入评估统计。
     */
    @Test
    void buildRejectsQaAnswerVerbatimEqualToExpected() {
        EvalData data = AtlasAgentEvalFixture.load();
        AtlasAgentEvalFixture.QaCase qaCase = data.qaCases().get(0);
        QaActual leaked = new QaActual(qaCase.caseId(), qaCase.caseType(), QaQuestion.Status.COMPLETED, null,
                "ANSWER", null, qaCase.expected().resultText(),
                qaCase.expected().documentIds(), List.of(), qaCase.expected().documentIds(), 1000L);

        assertThatThrownBy(() -> AgentEvalReport.build(data, List.of(leaked), List.of(),
                List.of(AtlasEvalMetrics.qaVerdict(leaked, qaCase)), List.of(),
                null, "unit-test", Instant.now().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐字相同");
        System.out.printf("测试证据：场景=报告完整性，用例=%s 实际回答与期望逐字相同被拒绝%n", qaCase.caseId());
    }

    /**
     * 业务目的：知识整理实际最终回复与数据集期望参考回答逐字相同时报告必须拒绝组装，
     * 防止知识整理评估出现同样的泄漏或伪造假象。
     */
    @Test
    void buildRejectsCurationFinalResponseVerbatimEqualToExpected() {
        EvalData data = AtlasAgentEvalFixture.load();
        AtlasAgentEvalFixture.CurationCase curationCase = data.curationCases().get(0);
        CurationActual leaked = new CurationActual(curationCase.caseId(), KnowledgeTaskService.RunStatus.COMPLETED,
                null, curationCase.expected().finalResponse(), List.of(), 1000L);

        assertThatThrownBy(() -> AgentEvalReport.build(data, List.of(), List.of(leaked),
                List.of(),
                List.of(AtlasEvalMetrics.curationVerdict(leaked, curationCase)),
                null, "unit-test", Instant.now().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐字相同");
        System.out.printf("测试证据：场景=报告完整性，用例=%s 最终回复与期望逐字相同被拒绝%n", curationCase.caseId());
    }
}
